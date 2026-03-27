package com.akdevelopers.streamnode.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.akdevelopers.streamnode.analytics.Analytics
import com.akdevelopers.streamnode.analytics.CrashManager
import com.akdevelopers.streamnode.core.AppConstants
import com.akdevelopers.streamnode.service.MetricsPublisher
import com.akdevelopers.streamnode.service.RemoteCommandSource
import com.akdevelopers.streamnode.service.StatusPublisher
import com.akdevelopers.streamnode.service.StreamIdentity
import com.akdevelopers.streamnode.service.StreamLogger
import com.akdevelopers.streamnode.service.StreamStatus

/**
 * FirebaseRemoteController — multi-user Firebase RTDB remote controller.
 *
 * Implements the three typed seams defined in domain:streaming:
 *  • [RemoteCommandSource] — attaches / detaches the control listener
 *  • [StatusPublisher]     — writes stream status to /users/{id}/status
 *  • [MetricsPublisher]    — writes audio metrics to /users/{id}/realtime
 *
 * All paths are scoped to /users/{streamId}/ so each device has its own
 * isolated control lane, status reporting, and realtime metrics.
 *
 * Database layout:
 *   /streamnode_config/serverUrl_v2  — v2 APK server URL (read by this build)
 *   /users/{streamId}/control      — commands for this device
 *   /users/{streamId}/status       — this device's live stream status
 *   /users/{streamId}/realtime     — this device's audio metrics
 *   /streams/{streamId}            — global registry (for dashboard)
 */
class FirebaseRemoteController(private val context: Context)
    : RemoteCommandSource, StatusPublisher, MetricsPublisher {

    // ── RemoteCommandSource ────────────────────────────────────────────────────
    override var onCommandReceived: ((commandId: String, action: String, url: String) -> Unit)? = null

    /**
     * Gap 3 fix — live server URL change notification.
     * Invoked whenever Firebase pushes a new value for serverUrl_v2 that differs
     * from the URL currently in use.  Wired by ConnectionOrchestrator so the app
     * reconnects to a new server domain without requiring a restart.
     */
    var onServerUrlChanged: ((newUrl: String) -> Unit)? = null

    /**
     * Gap 2 fix — server restart notification.
     * Invoked when Firebase pushes a new serverStartedAt timestamp, indicating the
     * server has restarted. ConnectionOrchestrator calls reconnectNow() immediately,
     * bypassing the Phase-2 backoff (up to 5 min) for instant reconnection.
     */
    var onServerRestarted: (() -> Unit)? = null

    // ── Per-device scoped paths ────────────────────────────────────────────────
    private val streamId      = StreamIdentity.getStreamId(context)
    private val prefs         = context.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
    private val db            = FirebaseDatabase.getInstance(AppConstants.FIREBASE_DB_URL)

    private val controlRef    = db.getReference("${AppConstants.FIREBASE_PATH_USERS}/$streamId/${AppConstants.FIREBASE_PATH_CONTROL}")
    private val statusRef     = db.getReference("${AppConstants.FIREBASE_PATH_USERS}/$streamId/${AppConstants.FIREBASE_PATH_STATUS}")
    private val realtimeRef   = db.getReference("${AppConstants.FIREBASE_PATH_USERS}/$streamId/${AppConstants.FIREBASE_PATH_REALTIME}")
    private val configRef     = db.getReference(AppConstants.FIREBASE_PATH_STREAMNODE_CFG)
    private val streamsDirRef = db.getReference("${AppConstants.FIREBASE_PATH_STREAMS}/$streamId")

    /** Bug #13 fix: track last processed commandId so that Firebase re-fires
     *  (triggered when we write processed/processedAt back to the same node)
     *  are suppressed even when the ts hasn't changed.  The ts guard catches
     *  stale commands from a previous session; the commandId guard catches the
     *  re-fire within the same session. */
    private var lastProcessedCommandId: String = ""
    private var listener: ValueEventListener?      = null
    /** Persistent listener on serverUrl_v2 for live URL-change detection (Gap 3). */
    private var serverUrlListener: ValueEventListener? = null
    /** Persistent listener on serverStartedAt for server-restart detection (Gap 2). */
    private var serverStartedWatcher: ValueEventListener? = null

    // Heartbeat — writes "online: true + ts" to /users/{id}/status every 60 s
    // so the dashboard always shows the device as reachable even while idle.
    private val heartbeatHandler  = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            statusRef.child("online").setValue(true)
            statusRef.child("lastHeartbeat").setValue(System.currentTimeMillis())
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    companion object {
        private const val TAG              = "AC_Firebase"
        const val  DB_URL                  = AppConstants.FIREBASE_DB_URL
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
    }

    // ── RemoteCommandSource: fetchServerUrl ────────────────────────────────────

    override fun fetchServerUrl(onSuccess: (String) -> Unit, onFailure: (() -> Unit)?) {
        val mainHandler = Handler(Looper.getMainLooper())
        var settled = false

        val timeoutRunnable = Runnable {
            if (!settled) {
                settled = true
                Log.e(TAG, "fetchServerUrl: TIMEOUT — Firebase never responded")
                onFailure?.invoke()
            }
        }
        mainHandler.postDelayed(timeoutRunnable, AppConstants.FIREBASE_FETCH_TIMEOUT_MS)
        Log.d(TAG, "fetchServerUrl: listening…  streamId=${streamId.take(8)}")

        configRef.child(AppConstants.FIREBASE_PATH_SERVER_URL)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (settled) return
                    settled = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    val url = snapshot.getValue(String::class.java)
                    if (!url.isNullOrBlank()) { Log.i(TAG, "fetchServerUrl → $url"); onSuccess(url) }
                    else {
                        Log.w(TAG, "fetchServerUrl: Firebase returned empty serverUrl_v2 — will use DEFAULT_SERVER_URL fallback")
                        onFailure?.invoke()
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    if (settled) return
                    settled = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    Log.e(TAG, "fetchServerUrl cancelled: ${error.message}")
                    onFailure?.invoke()
                }
            })
    }

    // ── RemoteCommandSource: start / stop ──────────────────────────────────────

    override fun start() {
        publishToStreamDirectory()
        // Gap 4 fix: register onDisconnect() triggers so Firebase servers automatically
        // write online=false if this client crashes, is killed, or loses connectivity
        // without calling stop().  Executes server-side even on process kill.
        statusRef.child("online").onDisconnect().setValue(false)
        statusRef.child("lastHeartbeat").onDisconnect().setValue(System.currentTimeMillis())
        Log.i(TAG, "onDisconnect() handlers registered — ghost-online prevention active")

        // Start keepalive heartbeat so dashboard shows device as online while idle
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.post(heartbeatRunnable)
        Log.i(TAG, "Heartbeat started — writing online=true every ${HEARTBEAT_INTERVAL_MS/1000}s")

        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val ts     = snapshot.child("ts").getValue(Long::class.java) ?: return
                val lastTs = prefs.getLong(AppConstants.PREF_FIREBASE_LAST_TS, 0L)
                if (ts <= lastTs) {
                    Log.d(TAG, "Stale command skipped (ts=$ts <= lastTs=$lastTs)")
                    return
                }

                // Bug #13 fix: addValueEventListener fires again when we write
                // processed=true / processedAt back to the same node.  The ts guard
                // above misses this re-fire because ts hasn't changed.  Deduplicate
                // by commandId so each logical command is processed exactly once.
                val incomingId = snapshot.child("commandId").getValue(String::class.java) ?: ""
                if (incomingId.isNotEmpty() && incomingId == lastProcessedCommandId) {
                    Log.d(TAG, "Duplicate commandId skipped (re-fire after processed write): $incomingId")
                    return
                }

                prefs.edit().putLong(AppConstants.PREF_FIREBASE_LAST_TS, ts).apply()

                controlRef.child("processed").setValue(true)
                controlRef.child("processedAt").setValue(System.currentTimeMillis())

                val cmd = snapshot.child("command").getValue(String::class.java)
                    ?: run { Log.w(TAG, "No 'command' field in snapshot"); return }
                val url       = snapshot.child("url").getValue(String::class.java) ?: ""
                val commandId = incomingId.ifEmpty { "firebase-$ts" }

                // Record commandId before the callback so a re-entrant listener call
                // during onCommandReceived() is also suppressed.
                lastProcessedCommandId = commandId

                Log.i(TAG, "▶ cmd='$cmd' id=${commandId.take(8)} ts=$ts  streamId=${streamId.take(8)}")
                Analytics.logFirebaseCommand(cmd)
                StreamLogger.log("🔥 Firebase CMD: action='$cmd'  id=${commandId.take(8)}")
                onCommandReceived?.invoke(commandId, cmd, url)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Listener cancelled: ${error.message}")
                CrashManager.recordNonFatal(error.toException(), "Firebase command listener cancelled")
            }
        }
        listener = l
        controlRef.addValueEventListener(l)
        Log.i(TAG, "Started — listening on /users/${streamId.take(8)}…/control")
    }

    // ── Gap 2 fix: server restart watcher ────────────────────────────────────

    /**
     * Attaches a persistent listener to [AppConstants.FIREBASE_PATH_SERVER_STARTED_AT].
     * When the server writes a new (higher) epoch ms here on startup, [onServerRestarted]
     * is invoked so ConnectionOrchestrator can call reconnectNow() immediately —
     * bypassing up to 5 minutes of Phase-2 backoff.
     *
     * [currentServerStartedAt] is the last known value from SharedPreferences, used to
     * suppress the initial cache-hit (Firebase delivers the cached value immediately on
     * listener attach before fetching from the server).
     */
    fun startServerStartedWatcher(currentServerStartedAt: Long) {
        stopServerStartedWatcher()
        var lastKnownTs = currentServerStartedAt
        val ref = configRef.child(AppConstants.FIREBASE_PATH_SERVER_STARTED_AT)
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newTs = snapshot.getValue(Long::class.java) ?: return
                if (newTs <= lastKnownTs) {
                    Log.d(TAG, "serverStartedWatcher: ts=$newTs <= lastKnown=$lastKnownTs — suppressing (cache hit or duplicate)")
                    return
                }
                Log.i(TAG, "Server restart detected: serverStartedAt changed $lastKnownTs → $newTs")
                StreamLogger.log("🔄 Server restarted (ts=$newTs) — triggering immediate reconnect")
                lastKnownTs = newTs
                prefs.edit().putLong(AppConstants.PREF_SERVER_STARTED_AT, newTs).apply()
                onServerRestarted?.invoke()
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "serverStartedWatcher cancelled: ${error.message}")
            }
        }
        serverStartedWatcher = l
        ref.addValueEventListener(l)
        Log.i(TAG, "Server restart watcher started — listening for serverStartedAt changes")
    }

    private fun stopServerStartedWatcher() {
        serverStartedWatcher?.let {
            configRef.child(AppConstants.FIREBASE_PATH_SERVER_STARTED_AT).removeEventListener(it)
        }
        serverStartedWatcher = null
    }

    // ── Gap 3 fix: live server URL watcher ───────────────────────────────────

    /**
     * Attaches a persistent Firebase listener to [AppConstants.FIREBASE_PATH_SERVER_URL].
     * Every time the operator changes the server URL in Firebase the app is notified
     * in real time (no polling delay) and [onServerUrlChanged] is invoked so
     * ConnectionOrchestrator can call changeUrl() and reconnect immediately.
     *
     * The listener is removed by [stop] so it does not leak after the service stops.
     *
     * Note: Firebase already caches the last known value on-device, so the first
     * onDataChange fires immediately with the cached value — this is intentionally
     * ignored via the [lastKnownServerUrl] guard to avoid a spurious changeUrl()
     * on every service start.
     */
    fun startServerUrlWatcher(currentUrl: String) {
        stopServerUrlWatcher()
        var lastKnownUrl = currentUrl
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newUrl = snapshot.getValue(String::class.java)
                if (newUrl.isNullOrBlank() || newUrl == lastKnownUrl) return
                Log.i(TAG, "serverUrl_v2 changed: $lastKnownUrl → $newUrl")
                StreamLogger.log("🔥 Firebase: server URL changed → reconnecting to $newUrl")
                lastKnownUrl = newUrl
                // Persist so the next cold-start uses the new URL immediately.
                prefs.edit().putString(AppConstants.PREF_SERVER_URL, newUrl).apply()
                onServerUrlChanged?.invoke(newUrl)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "serverUrlWatcher cancelled: ${error.message}")
            }
        }
        serverUrlListener = l
        configRef.child(AppConstants.FIREBASE_PATH_SERVER_URL).addValueEventListener(l)
        Log.i(TAG, "Server URL watcher started — listening for live URL changes")
    }

    private fun stopServerUrlWatcher() {
        serverUrlListener?.let {
            configRef.child(AppConstants.FIREBASE_PATH_SERVER_URL).removeEventListener(it)
        }
        serverUrlListener = null
    }

    override fun stop() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        // Gap 4 fix: cancel onDisconnect() triggers so the server-side write doesn't
        // race with our own manual online=false write below.
        statusRef.child("online").onDisconnect().cancel()
        statusRef.child("lastHeartbeat").onDisconnect().cancel()
        statusRef.child("online").setValue(false)
        statusRef.child("lastHeartbeat").setValue(System.currentTimeMillis())
        listener?.let { controlRef.removeEventListener(it) }
        listener = null
        lastProcessedCommandId = ""   // Bug #13 fix: reset so next session starts fresh
        stopServerStartedWatcher()
        stopServerUrlWatcher()
        streamsDirRef.removeValue()
        Log.i(TAG, "Stopped — heartbeat cancelled, online=false, watchers removed")
    }

    // ── StatusPublisher ────────────────────────────────────────────────────────

    override fun pushStatus(status: StreamStatus, serverUrl: String) {
        statusRef.setValue(mapOf(
            "status"    to status.name,
            "serverUrl" to serverUrl,
            "updatedAt" to System.currentTimeMillis()
        ))
    }

    // ── MetricsPublisher ───────────────────────────────────────────────────────

    override fun pushRealtimeMetrics(framesPerSec: Float, kbps: Float, uptimeSec: Int, quality: String) {
        realtimeRef.setValue(mapOf(
            "framesPerSec" to framesPerSec,
            "kbps"         to kbps,
            "uptimeSec"    to uptimeSec,
            "quality"      to quality,
            "updatedAt"    to System.currentTimeMillis()
        ))
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun publishToStreamDirectory() {
        streamsDirRef.setValue(mapOf(
            "displayName" to StreamIdentity.getDisplayName(context),
            "streamId"    to streamId,
            "updatedAt"   to System.currentTimeMillis()
        ))
    }
}
