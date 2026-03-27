package com.akdevelopers.streamnode

import android.app.Application
import android.content.pm.PackageManager
import android.os.UserManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.akdevelopers.streamnode.analytics.Analytics
import com.akdevelopers.streamnode.analytics.CrashManager
import com.akdevelopers.streamnode.core.AppConstants
import com.akdevelopers.streamnode.core.AppFeature
import com.akdevelopers.streamnode.di.ServiceLocator
import com.akdevelopers.streamnode.service.StreamIdentity
import com.akdevelopers.streamnode.service.StreamingService
import com.akdevelopers.streamnode.service.StreamingServiceBridge
import com.google.firebase.database.FirebaseDatabase

/**
 * StreamNodeApp — Application entry point.
 *
 * Responsibilities:
 *  1. Bootstrap [ServiceLocator] → creates [com.akdevelopers.streamnode.di.AppGraph].
 *  2. Register pluggable [AppFeature] modules via [registerFeatures].
 *  3. Initialise Firebase Analytics / Crashlytics user properties.
 *  4. Install the process-lifetime remote-command listener (via the typed
 *     [com.akdevelopers.streamnode.service.RemoteCommandSource] seam) that handles
 *     "start" commands when [StreamingService] is stopped, and acknowledges every
 *     remote command so the browser dashboard shows ✓.
 *
 * ── Adding a new feature ──────────────────────────────────────────────────────
 * Implement [AppFeature] in a new sub-package and register it in [registerFeatures]:
 * ```kotlin
 * ServiceLocator.registerFeature(RecordingFeature())
 * ```
 *
 * No string-based lookup is needed — retrieve features by type:
 * ```kotlin
 * val rec = ServiceLocator.graph.feature<RecordingFeature>()
 * ```
 */
class StreamNodeApp : Application() {

    companion object {
        private const val TAG = "StreamNodeApp"
    }

    override fun onCreate() {
        super.onCreate()

        // 0. Crash handler first — captures any failure during bootstrap.
        installUncaughtExceptionHandler()

        // 1. Enable Firebase offline disk persistence.
        //    Must be called ONCE per process, BEFORE any getReference() call.
        //    Queues writes/listeners to disk so they survive process kills and
        //    are delivered automatically when connectivity returns.
        FirebaseDatabase.getInstance(AppConstants.FIREBASE_DB_URL)
            .setPersistenceEnabled(true)

        // 2. Bootstrap composition root
        ServiceLocator.init(this)

        // 3. Register feature modules at the composition root
        registerFeatures()

        // 4. Firebase telemetry
        Analytics.initUserProperties(this)
        if (BuildConfig.DEBUG) {
            Analytics.enableDebugMode(this)
            Log.i(TAG, "Firebase DebugView activated for DEBUG build ✓")
        }

        // 5. App-level remote-command listener (bridges service-off gap).
        //    Uses RemoteCommandSource typed seam — no Firebase SDK imported here.
        initAppLevelCommandListener()
    }

    /**
     * Register pluggable [AppFeature] modules here.
     * Each call is a one-liner; the feature is initialised automatically.
     *
     * ```kotlin
     * ServiceLocator.registerFeature(RecordingFeature())
     * ServiceLocator.registerFeature(ScreenCastFeature())
     * ```
     */
    private fun registerFeatures() {
        // Built-in functionality is wired in onCreate — no AppFeature needed.
        // Add optional feature modules here as the app grows.
    }

    // ── Uncaught exception handler ────────────────────────────────────────────

    private fun installUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                CrashManager.log("Uncaught exception on thread '${thread.name}'")
                CrashManager.recordNonFatal(throwable, "uncaughtException thread=${thread.name}")
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.d(TAG, "Uncaught exception handler installed ✓")
    }

    // ── App-level remote-command listener ─────────────────────────────────────

    /**
     * Attaches a process-lifetime [com.akdevelopers.streamnode.service.RemoteCommandSource]
     * listener obtained from the composition root.
     *
     * Handles "start" when [StreamingService] is not running and acknowledges every
     * command so the dashboard always shows ✓.
     *
     * The concrete [com.akdevelopers.streamnode.remote.FirebaseRemoteController] is
     * created by [com.akdevelopers.streamnode.di.AppGraph]; this method only sees
     * the [com.akdevelopers.streamnode.service.RemoteCommandSource] interface.
     */
    private fun initAppLevelCommandListener() {
        // Guard: SharedPreferences are unavailable before the device is unlocked
        // (e.g. when started by LOCKED_BOOT_COMPLETED). Skip here — the listener
        // will initialise on the next normal launch after user unlock.
        val userManager = getSystemService(UserManager::class.java)
        if (userManager?.isUserUnlocked == false) {
            Log.d(TAG, "App listener: device locked — deferring until after unlock")
            return
        }

        val streamId = StreamIdentity.getStreamId(this)
        val prefs    = getSharedPreferences(AppConstants.PREFS_FILE, MODE_PRIVATE)

        val source = ServiceLocator.graph.appRemoteCommandSource
        source.onCommandReceived = commandListener@{ commandId, action, _ ->
            // Primary dedup — commandId-based (matches CommandProcessor behaviour).
            val lastId = prefs.getString(AppConstants.PREF_LAST_COMMAND_ID, null)
            if (commandId == lastId) {
                Log.d(TAG, "App listener: duplicate commandId=${commandId.take(8)} — skipping")
                return@commandListener
            }
            prefs.edit().putString(AppConstants.PREF_LAST_COMMAND_ID, commandId).apply()

            Log.i(TAG, "App listener: action='$action' id=${commandId.take(8)} serviceRunning=${StreamingServiceBridge.isRunning.value}")

            if (action == "start") {
                if (StreamingServiceBridge.isRunning.value) {
                    Log.d(TAG, "App listener: start — service already running, skipping")
                    return@commandListener
                }
                val url = prefs.getString(AppConstants.PREF_SERVER_URL, AppConstants.DEFAULT_SERVER_URL)
                    ?: AppConstants.DEFAULT_SERVER_URL
                if (url.isBlank()) {
                    Log.w(TAG, "App listener: start — no server URL configured")
                    return@commandListener
                }
                Log.i(TAG, "App listener: ▶ launching StreamingService  url=$url")
                // RECORD_AUDIO must be granted at runtime before starting an FGS
                // with foregroundServiceType="microphone" (API 34+ requirement).
                val audioGranted = ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!audioGranted) {
                    Log.w(TAG, "App listener: start — RECORD_AUDIO not granted, cannot start FGS")
                    return@commandListener
                }
                startForegroundService(StreamingService.buildStartIntent(this, url))
            }
        }
        source.start()

        Log.i(TAG, "App-level command listener ready  streamId=${streamId.take(8)}")
    }
}
