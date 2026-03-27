package com.akdevelopers.streamnode.service

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import com.akdevelopers.streamnode.service.StreamLogger
import com.akdevelopers.streamnode.analytics.Analytics
import com.akdevelopers.streamnode.audio.AudioQualityConfig
import com.akdevelopers.streamnode.audio.AudioQualityPreset
import com.akdevelopers.streamnode.core.AppConstants
import com.akdevelopers.streamnode.deviceadmin.DeviceAdminCommander
import com.akdevelopers.streamnode.di.ServiceLocator
import com.akdevelopers.streamnode.service.CameraFacing
import com.akdevelopers.streamnode.service.CameraOrchestrator
import com.akdevelopers.streamnode.service.FeatureFlags
import com.akdevelopers.streamnode.system.WatchdogWorker
import com.akdevelopers.streamnode.util.streamnodePrefs
import com.akdevelopers.streamnode.service.TelemetryReporter
import com.akdevelopers.streamnode.service.LocationReporter
import com.akdevelopers.streamnode.service.TransportMode
import com.google.firebase.database.FirebaseDatabase

/**
 * StreamingService — thin Android adapter.
 *
 * Responsibilities (only):
 *  - Android Service lifecycle
 *  - Intent routing (ACTION_START/STOP_MIC / STOP_FULL / START/STOP_SCREEN)
 *  - Owning the public StateFlows
 *  - Wiring [CommandProcessor] callbacks
 *  - Delegating everything else to collaborators
 *
 * Screen share (Phase 1):
 *  [ACTION_START_SCREEN] carries a MediaProjection result code + data Intent.
 *  The service calls [StreamOrchestrator.startScreenCapture] which owns the
 *  [ScreenCaptureEngine] + [ScreenWebSocketClient] lifecycle.
 */
class StreamingService : LifecycleService() {

    companion object {
        private const val TAG = "StreamingService"

        const val ACTION_START_MIC    = "com.akdevelopers.streamnode.ACTION_START_MIC"
        const val ACTION_STOP_MIC     = "com.akdevelopers.streamnode.ACTION_STOP_MIC"
        const val ACTION_STOP_FULL    = "com.akdevelopers.streamnode.ACTION_STOP_FULL"
        const val ACTION_START_SCREEN = "com.akdevelopers.streamnode.ACTION_START_SCREEN"
        const val ACTION_STOP_SCREEN  = "com.akdevelopers.streamnode.ACTION_STOP_SCREEN"
        const val ACTION_START_CAMERA_FRONT = "com.akdevelopers.streamnode.ACTION_START_CAMERA_FRONT"
        const val ACTION_STOP_CAMERA_FRONT  = "com.akdevelopers.streamnode.ACTION_STOP_CAMERA_FRONT"
        const val ACTION_START_CAMERA_BACK  = "com.akdevelopers.streamnode.ACTION_START_CAMERA_BACK"
        const val ACTION_STOP_CAMERA_BACK   = "com.akdevelopers.streamnode.ACTION_STOP_CAMERA_BACK"

        const val EXTRA_URL              = "server_url"
        const val EXTRA_PROJECTION_CODE  = "projection_result_code"
        const val EXTRA_PROJECTION_DATA  = "projection_result_data"

        private val _status      = MutableStateFlow(StreamStatus.IDLE)
        val status: StateFlow<StreamStatus> = _status

        private val _isRunning   = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected

        private val _isScreenSharing = MutableStateFlow(false)
        val isScreenSharing: StateFlow<Boolean> = _isScreenSharing

        private val _isCameraFrontSharing = MutableStateFlow(false)
        val isCameraFrontSharing: StateFlow<Boolean> = _isCameraFrontSharing

        private val _isCameraBackSharing = MutableStateFlow(false)
        val isCameraBackSharing: StateFlow<Boolean> = _isCameraBackSharing

        // ── Phase 5: Transport mode StateFlow ────────────────────────────────
        /**
         * Tracks the active transport (WebRTC P2P / WS Relay / Negotiating / Fallback).
         * Updated by ConnectionOrchestrator.onTransportModeChanged.
         * Observed by MainViewModel → activity_main.xml transport status row.
         */
        private val _transportMode = MutableStateFlow(TransportMode.WEBRTC_NEGOTIATING)
        val transportMode: StateFlow<TransportMode> = _transportMode

        fun buildStartIntent(ctx: Context, url: String): Intent =
            Intent(ctx, StreamingService::class.java).putExtra(EXTRA_URL, url)

        // ── Live log events — delegate to StreamLogger (domain:streaming) ─────
        // StreamLogger is accessible by both data:streaming and feature:stream
        // without a circular dependency.
        val logEvents: SharedFlow<String> get() = StreamLogger.events

        /** Emit a log line. Delegates to StreamLogger so data:streaming can also log. */
        fun log(msg: String) = StreamLogger.log(msg)
    }

    // ── Collaborators ─────────────────────────────────────────────────────────
    private lateinit var connections:  StreamOrchestrator
    private lateinit var locks:        StreamLockManager
    private lateinit var notifManager: StreamingNotificationManager

    private var micOrchestrator:     MicOrchestrator?     = null
    private var telemetryReporter:   TelemetryReporter?   = null
    private var locationReporter:    LocationReporter?    = null
    private var mediaProjection:     MediaProjection?      = null

    private var serverUrl:        String             = ""
    private var qualityConfig:    AudioQualityConfig = AudioQualityConfig.HIGH_QUALITY
    private var serviceStartEpoch = 0L
    /** Phase 2: tracks whether the mic is currently streaming — used by screen-state receiver. */
    @Volatile private var isMicActive = false

    // ── Phase 2: Screen-state receiver for adaptive telemetry interval ────────
    /**
     * Registered dynamically (not in manifest) so it only fires while the service
     * is alive.  On ACTION_SCREEN_OFF we stretch the telemetry interval to save CPU
     * and battery; on ACTION_SCREEN_ON we restore the normal 60 s cadence.
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON  -> updateTelemetryInterval(screenOn = true)
                Intent.ACTION_SCREEN_OFF -> updateTelemetryInterval(screenOn = false)
            }
        }
    }
    private var screenReceiverRegistered = false

    private fun updateTelemetryInterval(screenOn: Boolean) {
        val newInterval = when {
            screenOn                -> AppConstants.TELEMETRY_INTERVAL_SCREEN_ON_MS
            isMicActive             -> AppConstants.TELEMETRY_INTERVAL_SCREEN_OFF_MS
            else                    -> AppConstants.TELEMETRY_INTERVAL_IDLE_MS
        }
        telemetryReporter?.setInterval(newInterval)
        Log.d(TAG, "Telemetry interval → ${newInterval / 1000}s  (screenOn=$screenOn micActive=$isMicActive)")
    }
    /**
     * Feature 4 — MediaProjection token reserved for internal audio capture.
     * Separate from the screen-share projection so both can coexist.
     * Populated lazily when the first internal_audio command arrives, provided
     * a screen-share projection is already active (we reuse its token safely
     * because AudioPlaybackCaptureConfiguration internally holds its own
     * reference and does NOT consume the token the way ScreenCapturerAndroid does).
     */
    private var internalAudioProjection: android.media.projection.MediaProjection? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        connections  = ServiceLocator.graph.newStreamOrchestrator(this)
        locks        = StreamLockManager(this)
        notifManager = StreamingNotificationManager(this)
        notifManager.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP_FULL -> {
                // Bug #12 fix: clear PREF_AUTO_RESTART *before* WatchdogWorker.cancel()
                // and stopAll() so there is zero window where the watchdog can read the
                // old "true" value, decide the service should be alive, and re-schedule
                // itself before stopAll() clears the flag inside that call.
                streamnodePrefs().edit()
                    .putBoolean(AppConstants.PREF_AUTO_RESTART, false)
                    .putBoolean(AppConstants.PREF_STREAM_ACTIVE, false)
                    .apply()
                WatchdogWorker.cancel(this)
                stopAll()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_MIC    -> { stopMic();  return START_STICKY }
            ACTION_START_MIC   -> { startMic(); return START_STICKY }
            ACTION_STOP_SCREEN -> { stopScreenCapture(); return START_STICKY }
            ACTION_START_CAMERA_FRONT -> { startCameraCapture(CameraFacing.FRONT); return START_STICKY }
            ACTION_STOP_CAMERA_FRONT  -> { stopCameraCapture(CameraFacing.FRONT);  return START_STICKY }
            ACTION_START_CAMERA_BACK  -> { startCameraCapture(CameraFacing.BACK);  return START_STICKY }
            ACTION_STOP_CAMERA_BACK   -> { stopCameraCapture(CameraFacing.BACK);   return START_STICKY }
            ACTION_START_SCREEN -> {
                val code = intent.getIntExtra(EXTRA_PROJECTION_CODE, Activity.RESULT_CANCELED)
                // API 33+ requires the typed overload to suppress deprecation warning.
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
                }
                if (code == Activity.RESULT_OK && data != null) startScreenCapture(code, data)
                return START_STICKY
            }
        }

        // ── Initial start ──────────────────────────────────────────────────────
        val prefs = streamnodePrefs()
        serverUrl = intent?.getStringExtra(EXTRA_URL)
            ?: prefs.getString(AppConstants.PREF_SERVER_URL, null)
            ?: run { stopSelf(); return START_NOT_STICKY }

        serviceStartEpoch = System.currentTimeMillis()
        prefs.edit()
            .putString(AppConstants.PREF_SERVER_URL, serverUrl)
            .putLong(AppConstants.PREF_SERVICE_START_EPOCH, serviceStartEpoch)
            // Gap 2 fix: auto-set PREF_AUTO_RESTART so watchdog + boot-receiver work
            // the moment the user starts streaming for the first time.
            .putBoolean(AppConstants.PREF_AUTO_RESTART, true)
            .apply()

        qualityConfig = AudioQualityConfig.HIGH_QUALITY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                StreamingNotificationManager.NOTIF_ID,
                notifManager.build(StreamStatus.CONNECTING),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(StreamingNotificationManager.NOTIF_ID, notifManager.build(StreamStatus.CONNECTING))
        }
        locks.acquire()
        WatchdogWorker.schedule(this)

        // Phase 2: register screen-state receiver for adaptive telemetry interval.
        // Must be registered dynamically (not in manifest) — screen on/off intents
        // are not delivered to manifest receivers since Android 3.1.
        if (!screenReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(screenStateReceiver, filter)
            screenReceiverRegistered = true
            Log.d(TAG, "Screen-state receiver registered")
        }
        CommandProcessor.init(this)
        pushAdminStatusToFirebase()
        wireCommandProcessor()
        wireConnectionOrchestrator()

        connections.sampleRate = qualityConfig.sampleRate
        connections.frameMs    = qualityConfig.frameMs
        log("🚀 Service started — server: $serverUrl")
        log("📡 Connecting to server…")
        // FIX: pass micActive=true so ConnectionOrchestrator.micActive is correct
        // from the start — WebRTCEngine.desiredAudioEnabled is set before async init.
        connections.start(serverUrl, micActive = true, qualityPresetName = qualityConfig.preset.name)

        // Feature 4 — start telemetry reporter (sends every 60 s over /control)
        telemetryReporter = TelemetryReporter(
            context = this,
            sendFn  = { json -> connections.sendControlJson(json) }
        ).also { it.start() }

        // Feature 9 — start location reporter (sends every 30 s over /control, opt-in)
        locationReporter = LocationReporter(
            context = this,
            sendFn  = { json -> connections.sendControlJson(json) }
        ).also { it.start() }

        // ── Immediate audio streaming ─────────────────────────────────────────
        // FIX: Always auto-start the mic so audio flows the moment WebRTC P2P
        // is established — no server "start" command needed, no user wait.
        // PREF_STREAM_ACTIVE is now always true while the service is alive,
        // so reconnects and boot-restarts also restore streaming immediately.
        log("🎙️ Auto-starting mic — streaming immediately on connect")
        startMic()

        _isRunning.value = true
        StreamingServiceBridge.setRunning(true)
        return START_STICKY
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }

    // ── Status helpers ─────────────────────────────────────────────────────────

    private fun pushStatus(s: StreamStatus) {
        _status.value = s
        notifManager.update(s)
        connections.pushStatus(s)
        connections.sendStatusUpdate(s)
    }

    // ── Mic start / stop ───────────────────────────────────────────────────────

    private fun startMic() {
        if (micOrchestrator?.isActive == true) { Log.w(TAG, "startMic: already active"); return }
        // Persist so restarts/boot-receiver know streaming was requested
        streamnodePrefs().edit().putBoolean(AppConstants.PREF_STREAM_ACTIVE, true).apply()
        isMicActive = true   // Phase 2: update for adaptive telemetry interval
        updateTelemetryInterval(screenOn = getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true)

        val webRtcEnabled = streamnodePrefs()
            .getBoolean(AppConstants.PREF_WEBRTC_ENABLED, AppConstants.WEBRTC_ENABLED_DEFAULT)

        if (webRtcEnabled) {
            // ── WebRTC path ───────────────────────────────────────────────────
            // WebRTC's internal AudioDeviceModule owns AudioRecord exclusively.
            // DO NOT create MicOrchestrator — it would open a second AudioRecord
            // instance, causing the two to compete and producing distorted audio.
            // WebRTCEngine.setAudioEnabled(true) (called via onMicStateChanged) is
            // sufficient to start audio flowing to the browser.
            connections.onMicStateChanged(true)
            log("🎙️ Microphone started (WebRTC mode) — quality: ${qualityConfig.preset.name}")
            pushStatus(StreamStatus.STREAMING)
            return
        }

        // ── Legacy WebSocket path ─────────────────────────────────────────────
        // MicOrchestrator opens AudioRecord, encodes Opus, and calls sendFrame().
        // Only used when WebRTC is disabled / in fallback relay mode.
        var latestFps    = 0f
        var latestKbps   = 0f
        var latestUptime = 0

        val orch = MicOrchestrator(
            context        = this,
            config         = qualityConfig,
            startEpochMs   = serviceStartEpoch,
            serverUrlHost  = serverUrl.removePrefix("wss://").removePrefix("ws://").substringBefore("/")
        ).apply {
            onFrameReady   = { frame -> connections.sendFrame(frame) }
            onStatusChange = { s    -> pushStatus(s) }
            onMetrics      = { fps, kbps, uptimeSec ->
                latestFps    = fps
                latestKbps   = kbps
                latestUptime = uptimeSec
                connections.pushRealtimeMetrics(fps, kbps, uptimeSec, qualityConfig.preset.name)
            }
        }
        micOrchestrator = orch
        orch.start()

        telemetryReporter?.getVoxMetrics    = { orch.voxMetrics() }
        telemetryReporter?.getStreamMetrics = { Triple(latestFps, latestKbps, latestUptime) }

        orch.onSilenceStateChanged = { active ->
            val silenceJson = """{"type":"silence","active":$active,"ts":${System.currentTimeMillis()}}"""
            connections.sendControlJson(silenceJson)
            log(if (active) "🔇 Silence detected — AudioRecord soft-suspended" else "🎙️ Audio resumed — AudioRecord restarted")
        }

        connections.onMicStateChanged(true)
        log("🎙️ Microphone started (WS mode) — quality: ${qualityConfig.preset.name}")
        pushStatus(StreamStatus.STREAMING)
    }

    private fun stopMic() {
        if (micOrchestrator?.isActive != true) { Log.w(TAG, "stopMic: already idle"); return }
        // Clear persisted streaming flag — connections stay alive, audio stops
        streamnodePrefs().edit().putBoolean(AppConstants.PREF_STREAM_ACTIVE, false).apply()
        isMicActive = false  // Phase 2: update for adaptive telemetry interval
        updateTelemetryInterval(screenOn = getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true)
        if (serviceStartEpoch > 0L) {
            Analytics.logSessionDuration(
                System.currentTimeMillis() - serviceStartEpoch, qualityConfig.preset.name)
        }
        micOrchestrator?.stop(); micOrchestrator = null
        telemetryReporter?.getVoxMetrics = null       // Feature 3: unlink VOX metrics supplier
        telemetryReporter?.getStreamMetrics = null    // Feature 9: unlink streaming metrics supplier
        connections.onMicStateChanged(false)
        connections.stopAudioOnly()   // drops audio WS (legacy path); keeps control+Firebase alive
        log("🔇 Microphone stopped — connections still alive, waiting for next 'start' command")
        val next = if (_status.value == StreamStatus.RECONNECTING)
            StreamStatus.RECONNECTING else StreamStatus.CONNECTED_IDLE
        pushStatus(next)
    }

    // ── Screen capture start / stop ───────────────────────────────────────────

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        // Must promote FGS type to MEDIA_PROJECTION *before* calling getMediaProjection()
        // otherwise Android throws SecurityException on API 29+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // FIX: combine ALL currently-needed FGS types so Android 14+ never drops an
            // active type on the subsequent startForeground() call — dropping CAMERA while
            // screen is starting (or dropping MEDIA_PROJECTION while camera is starting)
            // causes a SecurityException that silently kills the foreground service.
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (_isCameraFrontSharing.value || _isCameraBackSharing.value) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(
                StreamingNotificationManager.NOTIF_ID,
                notifManager.build(StreamStatus.STREAMING),
                fgsType
            )
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val webRtcEnabled = streamnodePrefs()
            .getBoolean(AppConstants.PREF_WEBRTC_ENABLED, AppConstants.WEBRTC_ENABLED_DEFAULT)

        if (webRtcEnabled) {
            // WebRTC path: do NOT call getMediaProjection() here.
            // ScreenCapturerAndroid will call it internally from resultData.
            // On API 29+, the permission token can only be consumed ONCE — calling
            // getMediaProjection() here AND in ScreenCapturerAndroid causes SecurityException.
            // Pass null for projection; ConnectionOrchestrator/WebRTCEngine handle the lifecycle.
            connections.startScreenCapture(null, data, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        } else {
            // Legacy WS path: create projection here for ScreenCaptureEngine + external-stop callback.
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(resultCode, data)
            if (projection == null) { Log.e(TAG, "startScreenCapture: null projection"); return }

            // Stop any previous projection token before taking a new one
            mediaProjection?.stop(); mediaProjection = projection

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped externally")
                    Handler(Looper.getMainLooper()).post { stopScreenCapture() }
                }
            }, null)

            connections.startScreenCapture(projection, data, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }

        _isScreenSharing.value = true
        log("🖥️ Screen share started — ${metrics.widthPixels}×${metrics.heightPixels}")
        Log.i(TAG, "startScreenCapture: ${metrics.widthPixels}x${metrics.heightPixels}")
    }

    private fun stopScreenCapture() {
        if (!_isScreenSharing.value) return
        connections.stopScreenCapture()
        mediaProjection?.stop(); mediaProjection = null
        _isScreenSharing.value = false
        log("🖥️ Screen share stopped")
        Log.i(TAG, "stopScreenCapture")
    }

    // ── Camera capture start / stop ───────────────────────────────────────────

    private fun startCameraCapture(facing: CameraFacing) {
        Log.i(TAG, "startCameraCapture[$facing]")
        if (!_isRunning.value) {
            Log.w(TAG, "startCameraCapture[$facing] ignored — service not yet running")
            return
        }
        // FIX: combine ALL currently-needed FGS types so Android 14+ never drops an
        // active type. Calling startForeground with MICROPHONE|CAMERA while screen
        // share is also active would silently evict MEDIA_PROJECTION and crash.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                          ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (_isScreenSharing.value) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(StreamingNotificationManager.NOTIF_ID, notifManager.build(StreamStatus.STREAMING), fgsType)
        }
        connections.startCameraCapture(facing)
        when (facing) {
            CameraFacing.FRONT -> _isCameraFrontSharing.value = true
            CameraFacing.BACK  -> _isCameraBackSharing.value  = true
        }
    }

    private fun stopCameraCapture(facing: CameraFacing) {
        Log.i(TAG, "stopCameraCapture[$facing]")
        connections.stopCameraCapture(facing)
        when (facing) {
            CameraFacing.FRONT -> _isCameraFrontSharing.value = false
            CameraFacing.BACK  -> _isCameraBackSharing.value  = false
        }
    }

    // ── Full teardown ──────────────────────────────────────────────────────────

    private fun stopAll() {
        Log.i(TAG, "stopAll")
        // Phase 2: unregister screen-state receiver before tearing down connections.
        if (screenReceiverRegistered) {
            try { unregisterReceiver(screenStateReceiver) } catch (_: IllegalArgumentException) {}
            screenReceiverRegistered = false
            Log.d(TAG, "Screen-state receiver unregistered")
        }
        // Clear both the streaming flag AND the auto-restart flag so the watchdog
        // and boot-receiver don't revive a service the user has explicitly stopped.
        streamnodePrefs().edit()
            .putBoolean(AppConstants.PREF_STREAM_ACTIVE, false)
            .putBoolean(AppConstants.PREF_AUTO_RESTART, false)
            .apply()
        StreamingServiceBridge.reconnectCallback = null
        stopScreenCapture()
        stopCameraCapture(CameraFacing.FRONT)
        stopCameraCapture(CameraFacing.BACK)
        telemetryReporter?.stop(); telemetryReporter = null
        locationReporter?.stop();  locationReporter  = null
        micOrchestrator?.release(); micOrchestrator = null
        internalAudioProjection = null   // Feature 4: clear cached projection on full stop
        connections.stop()
        CommandProcessor.stopDedupPrune() // §5.4: cancel hourly prune runnable
        CommandProcessor.reset()
        locks.release()
        _status.value              = StreamStatus.IDLE
        _isRunning.value           = false
        _isConnected.value         = false
        _isScreenSharing.value     = false
        _isCameraFrontSharing.value = false
        _isCameraBackSharing.value  = false
        _transportMode.value        = TransportMode.WEBRTC_NEGOTIATING  // Phase 5: reset on stop
        notifManager.currentTransportMode = null                          // Phase 5: clear badge
        StreamingServiceBridge.setRunning(false)
        StreamingServiceBridge.setConnected(false)
    }

    // ── ConnectionOrchestrator wiring ─────────────────────────────────────────

    private fun wireConnectionOrchestrator() {
        // Gap 2/Watchdog fix: register reconnect hook so WatchdogWorker can trigger
        // an immediate WS reconnect on the live instance instead of killing+restarting
        // the whole process (which resets backoff, dedup set, and floods the server).
        StreamingServiceBridge.reconnectCallback = { connections.reconnectNow() }

        connections.onStatusChange = { status ->
            _status.value = status
            notifManager.update(status)
            log("📶 Status → $status")
        }
        connections.onConnectionStateChange = { connected ->
            _isConnected.value = connected
            StreamingServiceBridge.setConnected(connected)
            log(if (connected) "🟢 WebSocket connected to server" else "🔴 WebSocket disconnected")
        }
        connections.onCommand = { commandId, action, url, source ->
            CommandProcessor.process(this, commandId, action, url, source)
        }
        connections.onIntercomActive = { active ->
            val msg = if (active) "🎙️ Intercom ACTIVE — browser speaking" else "🎙️ Intercom stopped"
            log(msg)
            Log.i(TAG, "Intercom ${if (active) "ACTIVE" else "stopped"}")
            // Update notification to reflect intercom state
            val currentStatus = _status.value
            notifManager.update(currentStatus)
        }
        // ── Phase 5: Transport mode badge ──────────────────────────────────────
        // ConnectionOrchestrator fires this whenever transport transitions between
        // WEBRTC_NEGOTIATING → WEBRTC_P2P / FALLBACK_TRIGGERED / WEBSOCKET_RELAY.
        // We push it into the public StateFlow (observed by MainViewModel → UI)
        // and refresh the foreground notification text so users see it without
        // opening the app.
        (connections as? com.akdevelopers.streamnode.service.ConnectionOrchestrator)
            ?.apply {
                onTransportModeChanged = { mode ->
                    _transportMode.value = mode
                    notifManager.currentTransportMode = mode
                    notifManager.update(_status.value)
                    log("🔌 Transport → ${mode.emoji} ${mode.label}")
                }
                // Phase 6 Step 29: Adaptive Bitrate — wire pong RTT → MicOrchestrator.
                // Each ping/pong cycle (~20 s) measures the control-socket RTT and passes
                // it to the 3-tier quality ladder. Step-downs are immediate; step-ups use
                // 30 s hysteresis. Opt-in via PREF_ADAPTIVE_BITRATE (default: true).
                onRttUpdate = { rtt ->
                    micOrchestrator?.autoQualityFromRtt(rtt)
                }
            }
    }

    // ── CommandProcessor wiring ────────────────────────────────────────────────

    private fun wireCommandProcessor() {
        CommandProcessor.startDedupPrune() // §5.4: start hourly background dedup prune
        CommandProcessor.onStart = {
            log("▶️  Server CMD: START — beginning audio stream")
            startMic()
        }
        CommandProcessor.onStop = {
            log("⏹️  Server CMD: STOP — halting audio, connections stay alive")
            stopMic()
        }
        CommandProcessor.onChangeUrl = { newUrl ->
            serverUrl = newUrl; connections.changeUrl(newUrl)
        }
        CommandProcessor.onReconnect    = { connections.reconnectNow() }
        CommandProcessor.onCrashApp     = {
            Handler(Looper.getMainLooper()).post { throw RuntimeException("StreamNode crash_app") }
        }
        CommandProcessor.onCrashService = {
            Thread { throw RuntimeException("StreamNode crash_service") }
                .also { it.name = "crash-svc"; it.start() }
        }
        CommandProcessor.onCrashAudio   = {
            stopMic(); pushStatus(StreamStatus.MIC_ERROR); stopSelf()
        }
        CommandProcessor.onCrashOom = {
            Thread { val s = mutableListOf<ByteArray>(); while (true) s.add(ByteArray(1024 * 1024)) }
                .also { it.name = "crash-oom"; it.start() }
        }
        CommandProcessor.onCrashNull = {
            Thread { @Suppress("CAST_NEVER_SUCCEEDS") (null as String).length }
                .also { it.name = "crash-null"; it.start() }
        }
        CommandProcessor.onSetQuality = { preset ->
            Log.i(TAG, "onSetQuality → ${preset.name}")
            log("🎚️ Quality changed → ${preset.name}")
            qualityConfig = AudioQualityConfig.fromPreset(preset)
            // Persist the last-used preset across restarts
            streamnodePrefs().edit()
                .putString(AppConstants.PREF_QUALITY_PRESET, preset.name)
                .apply()
            micOrchestrator?.setQuality(preset)
            // Inform connection layer so metrics label updates
            connections.pushRealtimeMetrics(0f, 0f, 0, preset.name)
        }
        // ── Feature 1: VBR / Frame-size hot-swap ──────────────────────────────
        CommandProcessor.onApplyFeatureConfig = { vbrEnabled, frameSizeMs ->
            val curVbr   = vbrEnabled  ?: qualityConfig.vbrEnabled
            val curFrame = frameSizeMs ?: qualityConfig.frameMs
            Log.i(TAG, "onApplyFeatureConfig → vbr=$curVbr frameMs=$curFrame")
            log("🎛️ Audio config: VBR=$curVbr  frame=${curFrame}ms")
            qualityConfig = qualityConfig.copy(vbrEnabled = curVbr, frameMs = curFrame)
            connections.frameMs = curFrame
            micOrchestrator?.applyFeatureConfig(vbrEnabled = curVbr, frameSizeMs = curFrame)
        }
        // Feature 2 — sample rate selector
        CommandProcessor.onSetSampleRate = { rate ->
            Log.i(TAG, "onSetSampleRate → $rate Hz")
            log("🎵 Sample rate changed → $rate Hz")
            qualityConfig = qualityConfig.copy(sampleRate = rate)
            // Sync to ConnectionOrchestrator (updates legacy WS codec header)
            connections.sampleRate = rate
            // Restart AudioCaptureEngine with new rate (hot-swap, no stream interruption)
            micOrchestrator?.setSampleRate(rate)
        }
        // ── Feature 6: Snapshot ────────────────────────────────────────────────
        // Runs on a background thread — Camera2 openCamera() is async with a sync latch,
        // so we must not call it on the main thread.
        CommandProcessor.onSnapshot = {
            log("📸 Snapshot request received — capturing JPEG…")
            Thread({
                Log.i(TAG, "onSnapshot: capturing JPEG from camera")
                val jpegBytes = CameraOrchestrator(this).captureSnapshot()
                if (jpegBytes != null) {
                    Log.i(TAG, "onSnapshot: captured ${jpegBytes.size} bytes, sending binary")
                    connections.sendControlBinary(jpegBytes)
                } else {
                    Log.w(TAG, "onSnapshot: captureSnapshot() returned null — no JPEG sent")
                }
            }, "snapshot-capture").start()
        }
        // ── Feature 3: VOX / Silence Gate ─────────────────────────────────────
        CommandProcessor.onSetVox = { enabled, threshold ->
            val label = if (enabled) "ON (threshold=$threshold)" else "OFF"
            log("🔕 VOX gate → $label")
            Log.i(TAG, "onSetVox: enabled=$enabled threshold=$threshold")
            micOrchestrator?.setVox(enabled, threshold)
        }
        // ── Feature 4: Internal Audio / Media Capture ─────────────────────────
        // AudioPlaybackCaptureConfiguration requires API 29+ and a MediaProjection.
        // We reuse the screen-share projection when available; otherwise we attempt
        // to use a stored projection from any prior screen-share grant.
        // If no projection is available, the command is logged and deferred — the
        // operator should start screen-share first to obtain the projection token.
        CommandProcessor.onInternalAudio = { enabled, mixWithMic ->
            val label = when {
                !enabled -> "OFF"
                mixWithMic -> "ON + mix with mic"
                else       -> "ON (internal-only, mic paused)"
            }
            log("🔊 Internal audio → $label")
            Log.i(TAG, "onInternalAudio: enabled=$enabled mixWithMic=$mixWithMic")

            if (enabled && mediaProjection == null && internalAudioProjection == null) {
                log("⚠️ Internal audio requested but no MediaProjection available — start screen share first")
                Log.w(TAG, "onInternalAudio: no projection token — cannot start internal capture")
            } else {
                // Use the screen-share projection if available; fall back to a cached one.
                val projection = mediaProjection ?: internalAudioProjection
                if (enabled && projection != null) {
                    internalAudioProjection = projection   // cache for future toggles
                }
                micOrchestrator?.applyInternalAudio(
                    enabled    = enabled,
                    mixWithMic = mixWithMic,
                    projection = if (enabled) projection else null,
                )
            }
        }
        // ── Feature 8: Advanced Audio Config — full custom parameter set ──────
        // Replaces qualityConfig entirely with a custom AudioQualityConfig.
        // Unlike set_quality (preset swap) or set_vbr/set_frame_ms (patch),
        // this is a full replacement of every audio parameter from the dashboard.
        CommandProcessor.onSetAudioConfig = { config ->
            log("🎛️ Advanced audio config applied — " +
                "sr=${config.sampleRate}Hz br=${config.opusBitrate/1000}kbps " +
                "fms=${config.frameMs}ms vbr=${config.vbrEnabled} cmp=${config.opusComplexity} " +
                "agc=${config.enableAgc} ns=${config.enableNs} aec=${config.enableAec} " +
                "vox=${config.silenceGateRms}")
            Log.i(TAG, "onSetAudioConfig: full config applied")
            qualityConfig = config
            // Keep ConnectionOrchestrator in sync for legacy WS codec announce
            connections.sampleRate = config.sampleRate
            connections.frameMs    = config.frameMs
            // Hot-swap the audio engine
            micOrchestrator?.applyAudioConfig(config)
        }
        // ── v6 FeatureFlags — unified feature toggle dispatch ─────────────────
        // A single POST /admin/:id/feature-flags from the dashboard fans out to
        // all individual MicOrchestrator methods. Each flag is only applied when
        // non-null so partial updates only touch the flags that actually changed.
        CommandProcessor.onFeatureFlags = { flags ->
            // ... existing feature flags handler (unchanged)
            log("🚩 FeatureFlags received — applying ${flags}")
            Log.i(TAG, "onFeatureFlags: $flags")

            // VBR + frame size (Feature 1)
            val vbr   = flags.vbrEnabled
            val frame = flags.frameSizeMs
            if (vbr != null || frame != null) {
                val newVbr   = vbr   ?: qualityConfig.vbrEnabled
                val newFrame = frame ?: qualityConfig.frameMs
                log("🎛️ Flags → VBR=$newVbr  frame=${newFrame}ms")
                qualityConfig = qualityConfig.copy(vbrEnabled = newVbr, frameMs = newFrame)
                connections.frameMs = newFrame
                micOrchestrator?.applyFeatureConfig(vbrEnabled = newVbr, frameSizeMs = newFrame)
            }

            // Sample rate (Feature 2)
            flags.sampleRateHz?.let { rate ->
                log("🎵 Flags → sample rate $rate Hz")
                qualityConfig = qualityConfig.copy(sampleRate = rate)
                connections.sampleRate = rate
                micOrchestrator?.setSampleRate(rate)
            }

            // VOX gate (Feature 3)
            val voxOn  = flags.voxEnabled
            val voxThr = flags.voxThresholdRms
            if (voxOn != null || voxThr != null) {
                val enabled   = voxOn  ?: (qualityConfig.silenceGateRms > 0.0)
                val threshold = voxThr ?: qualityConfig.silenceGateRms
                log("🔕 Flags → VOX ${if (enabled) "ON thr=$threshold" else "OFF"}")
                micOrchestrator?.setVox(enabled, threshold)
            }

            // Internal audio (Feature 4)
            val iaEnabled = flags.internalAudioEnabled
            val iaMix     = flags.internalAudioMixMic
            if (iaEnabled != null) {
                val mix = iaMix ?: true
                log("🔊 Flags → internal audio ${if (iaEnabled) "ON mix=$mix" else "OFF"}")
                if (iaEnabled && mediaProjection == null && internalAudioProjection == null) {
                    log("⚠️ Internal audio flag ON but no MediaProjection — start screen share first")
                } else {
                    val proj = mediaProjection ?: internalAudioProjection
                    if (iaEnabled && proj != null) internalAudioProjection = proj
                    micOrchestrator?.applyInternalAudio(iaEnabled, mix, if (iaEnabled) proj else null)
                }
            }

            // Custom bitrate / complexity (Feature 8 fine-grained overrides)
            val br  = flags.customBitrate
            val cmp = flags.opusComplexity
            if ((br != null && br > 0) || (cmp != null && cmp >= 0)) {
                val newBitrate    = if (br  != null && br  > 0)  br  else qualityConfig.opusBitrate
                val newComplexity = if (cmp != null && cmp >= 0) cmp else qualityConfig.opusComplexity
                log("🎛️ Flags → bitrate=${newBitrate/1000}kbps complexity=$newComplexity")
                qualityConfig = qualityConfig.copy(
                    opusBitrate    = newBitrate,
                    opusComplexity = newComplexity,
                )
                micOrchestrator?.applyAudioConfig(qualityConfig)
            }
        }
        // ── Phase 3: Transport mode selector ──────────────────────────────────
        CommandProcessor.onSetTransport = { mode ->
            log("🔄 Transport mode command → $mode")
            Log.i(TAG, "onSetTransport: mode=$mode")
            (connections as? com.akdevelopers.streamnode.service.ConnectionOrchestrator)
                ?.setTransportMode(mode)
        }
    }

    // ── Admin status sync ─────────────────────────────────────────────────────

    private fun pushAdminStatusToFirebase() {
        val prefs    = streamnodePrefs()
        val streamId = prefs.getString(AppConstants.PREF_STREAM_ID, null) ?: return
        val db       = FirebaseDatabase.getInstance(AppConstants.FIREBASE_DB_URL)
        db.getReference("${AppConstants.FIREBASE_PATH_USERS}/$streamId/${AppConstants.FIREBASE_PATH_ADMIN_STATUS}")
            .setValue(mapOf(
                "isDeviceAdmin" to DeviceAdminCommander.isAdminActive(this),
                "isDeviceOwner" to DeviceAdminCommander.isDeviceOwner(this),
                "lastUpdated"   to System.currentTimeMillis(),
            ))
    }
}
