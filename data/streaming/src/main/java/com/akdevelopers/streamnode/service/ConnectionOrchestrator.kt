package com.akdevelopers.streamnode.service

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import com.akdevelopers.streamnode.audio.AudioControlClient
import com.akdevelopers.streamnode.audio.AudioWebSocketClient
import com.akdevelopers.streamnode.audio.ScreenCaptureEngine
import com.akdevelopers.streamnode.audio.ScreenWebSocketClient
import com.akdevelopers.streamnode.audio.WebRTCEngine
import com.akdevelopers.streamnode.core.AppConstants
import com.akdevelopers.streamnode.remote.FirebaseRemoteController
import com.akdevelopers.streamnode.system.NetworkChangeReceiver
import com.akdevelopers.streamnode.util.streamnodePrefs
import com.akdevelopers.streamnode.service.CameraFacing
import com.akdevelopers.streamnode.service.StreamLogger
import com.akdevelopers.streamnode.service.TransportMode

/**
 * ConnectionOrchestrator — owns every network connection for a streaming session.
 *
 * WebRTC path (default, [AppConstants.PREF_WEBRTC_ENABLED] = true):
 *   [WebRTCEngine] handles audio track and, optionally, screen/camera video tracks.
 *   [SignalingClient] (owned inside WebRTCEngine) connects to /signal.
 *   Legacy [AudioWebSocketClient] is NOT started in this mode.
 *
 * Legacy WebSocket path (fallback, PREF_WEBRTC_ENABLED = false):
 *   Identical to the pre-WebRTC behaviour:
 *   [AudioWebSocketClient] → binary Opus frames → /stream
 *   [ScreenWebSocketClient] → H.264 NAL units → /screen
 *   [CameraOrchestrator]   → H.264 NAL units → /camera-front|back
 *
 * Toggle by writing false to PREF_WEBRTC_ENABLED SharedPreferences key.
 */
class ConnectionOrchestrator(private val context: Context) : StreamOrchestrator {

    companion object { private const val TAG = "AC_ConnOrch" }

    override var onStatusChange: ((StreamStatus) -> Unit)?              = null
    override var onConnectionStateChange: ((Boolean) -> Unit)?          = null
    override var onCommand: ((String, String, String, String) -> Unit)? = null

    override var sampleRate: Int = 48_000
        set(v) { field = v; wsClient?.sampleRate = v; wsClient?.sendCodecAnnounce() }
    // Feature 1: frameMs syncs to both legacy WS path AND WebRTC SDP ptime
    override var frameMs: Int = 20  // FIX: 60 → 20 to match HIGH_QUALITY.frameMs
        set(v) { field = v; wsClient?.frameMs = v; webRtcEngine?.currentFrameMs = v }

    // ── Legacy WS collaborators ───────────────────────────────────────────────
    private var controlClient:      AudioControlClient?       = null
    private var wsClient:           AudioWebSocketClient?     = null
    private var firebaseController: FirebaseRemoteController? = null
    private var networkReceiver:    NetworkChangeReceiver?    = null

    // Phase 2 OPT: Removed periodic Firebase URL poll (urlRefreshRunnable / urlRefreshHandler).
    // The live onServerUrlChanged push listener (Gap 3 fix) already handles URL changes in
    // real-time with <3 s latency. The poll was redundant and woke the CPU every 30 min,
    // allocating a new FirebaseRemoteController instance on each iteration.
    // Three other reconnect layers (NetworkChangeReceiver, serverStartedAt watcher,
    // WatchdogWorker) provide sufficient belt-and-suspenders coverage.

    // ── Screen capture (legacy WS path) ──────────────────────────────────────
    private var screenEngine:   ScreenCaptureEngine?   = null
    private var screenWsClient: ScreenWebSocketClient? = null
    @Volatile private var _isScreenSharing = false
    override val isScreenSharing: Boolean get() = _isScreenSharing

    // ── Camera (legacy WS path) ───────────────────────────────────────────────
    private var cameraOrchestrator: CameraOrchestrator? = null
    override val isCameraFrontSharing: Boolean get() = cameraOrchestrator?.isFrontActive == true
    override val isCameraBackSharing:  Boolean get() = cameraOrchestrator?.isBackActive  == true

    // ── WebRTC path ───────────────────────────────────────────────────────────
    private var webRtcEngine: WebRTCEngine? = null

    // ── Phase 3: Fallback state machine ──────────────────────────────────────
    /**
     * Standby WebSocket client: pre-connected but not sending frames (audioSuspended=true).
     * When WebRTC is active this client stays ready so it can take over within <50 ms
     * if ICE fails — producing zero audible gap during the fallback switch.
     */
    private var standbyWsClient: AudioWebSocketClient? = null

    /** Current transport mode. Reported to server via sendTransportStatus(). */
    @Volatile private var currentTransportMode: TransportMode = TransportMode.WEBRTC_NEGOTIATING

    /** Callback invoked on main thread whenever the transport mode changes. */
    var onTransportModeChanged: ((TransportMode) -> Unit)? = null

    /** Phase 6 Step 29: Invoked on the main thread each time the control-socket
     * ping/pong RTT is measured (~every 20 s). StreamingService wires this to
     * MicOrchestrator.autoQualityFromRtt() to drive the adaptive bitrate ladder.
     */
    var onRttUpdate: ((Long) -> Unit)? = null

    /**
     * BUG FIX #4: Store the most-recently-measured control-socket RTT so that
     * sendTransportStatus() can include it in the transportStatus message sent to
     * the server. Without this, ch.transportRttMs on the server was always null,
     * which broke the dashboard's RTT display and the adaptive bitrate debug view.
     * Updated inside connectControlSocket() whenever AudioControlClient fires
     * onRttMeasured.
     */
    @Volatile private var lastKnownRttMs: Long = 0L

    /**
     * Switch the active transport at runtime without interrupting audio.
     * Called from CommandProcessor when set_transport_webrtc/ws/auto arrives.
     */
    fun setTransportMode(mode: String) {
        Log.i(TAG, "setTransportMode → $mode")
        val prefs = context.streamnodePrefs().edit()
        when (mode) {
            "websocket" -> {
                prefs.putBoolean(AppConstants.PREF_WEBRTC_ENABLED, false)
                    .putBoolean(AppConstants.PREF_TRANSPORT_AUTO, false).apply()
                activateFallback()
            }
            "webrtc" -> {
                prefs.putBoolean(AppConstants.PREF_WEBRTC_ENABLED, true)
                    .putBoolean(AppConstants.PREF_TRANSPORT_AUTO, false).apply()
                // Tear down WS audio, restart WebRTC negotiation
                standbyWsClient?.suspendAudio()
                if (webRtcEngine == null) startWebRtcEngine()
                transitionTo(TransportMode.WEBRTC_NEGOTIATING)
            }
            "auto" -> {
                prefs.putBoolean(AppConstants.PREF_WEBRTC_ENABLED, true)
                    .putBoolean(AppConstants.PREF_TRANSPORT_AUTO, true).apply()
                standbyWsClient?.suspendAudio()
                if (webRtcEngine == null) startWebRtcEngine()
                transitionTo(TransportMode.WEBRTC_NEGOTIATING)
            }
        }
    }

    private fun transitionTo(mode: TransportMode) {
        currentTransportMode = mode
        onTransportModeChanged?.invoke(mode)
        sendTransportStatus(mode)
        StreamLogger.log("🔄 Transport → ${mode.label}")
    }

    private fun sendTransportStatus(mode: TransportMode) {
        // BUG FIX #4a: The old code set iceState = mode.wireValue which is the same
        // as the mode field — that's wrong. The server stores iceState and transportMode
        // separately (ch.iceState vs ch.transportMode). iceState must be the canonical
        // WebRTC ICE connection-state string so the dashboard badge matches spec:
        //   WEBRTC_P2P        → "connected"
        //   WEBRTC_NEGOTIATING → "checking"
        //   FALLBACK_TRIGGERED → "failed"
        //   WEBSOCKET_RELAY   → "closed"
        // BUG FIX #4b: The old code omitted the rtt field entirely, so
        // ch.transportRttMs was always null on the server.
        val iceState = when (mode) {
            TransportMode.WEBRTC_P2P         -> "connected"
            TransportMode.WEBRTC_NEGOTIATING -> "checking"
            TransportMode.FALLBACK_TRIGGERED -> "failed"
            TransportMode.WEBSOCKET_RELAY    -> "closed"
        }
        val rtt = lastKnownRttMs
        val json = """{"type":"transportStatus","mode":"${mode.wireValue}","iceState":"$iceState","rtt":$rtt,"ts":${System.currentTimeMillis()}}"""
        controlClient?.send(json)
    }

    /** ICE succeeded — switch to P2P, put standby WS to sleep. */
    private fun onWebRtcIceConnected() {
        Log.i(TAG, "ICE connected → switching to WEBRTC_P2P, suspending standby WS")
        standbyWsClient?.suspendAudio()
        transitionTo(TransportMode.WEBRTC_P2P)
    }

    /** ICE failed — activate standby WebSocket immediately, keep WebRTC for recovery. */
    private fun activateFallback() {
        Log.i(TAG, "activateFallback → activating standby WebSocket")
        transitionTo(TransportMode.FALLBACK_TRIGGERED)
        // Activate the standby client if it exists; create it if not
        if (standbyWsClient == null) {
            standbyWsClient = AudioWebSocketClient().also {
                it.sampleRate = sampleRate
                it.frameMs    = frameMs
                it.connect(serverUrl, context)
            }
        }
        // BUG FIX 5 — Wire status callbacks on the fallback WS client so that
        // StreamingService._isConnected / notification update correctly after fallback.
        // Previously standbyWsClient was created without onStatusChange, meaning
        // onConnectionStateChange never fired after the switch, leaving the UI showing
        // stale connection state and the notification showing wrong transport badge.
        standbyWsClient?.onStatusChange = { wsStatus ->
            val isConnected = wsStatus == StreamStatus.CONNECTED_IDLE ||
                              wsStatus == StreamStatus.STREAMING
            onConnectionStateChange?.invoke(isConnected)
            val effective = resolveEffectiveStatus(wsStatus)
            onStatusChange?.invoke(effective)
            firebaseController?.pushStatus(effective, serverUrl)
            controlClient?.sendStatusUpdate(effective)
        }
        standbyWsClient?.activateAudio()
        // wsClient is the frame-send channel — point it at the standby client
        wsClient = standbyWsClient
        transitionTo(TransportMode.WEBSOCKET_RELAY)
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private var serverUrl:      String  = ""
    private var micActive:      Boolean = false
    private var qualityPreset:  String  = "HIGH_QUALITY"
    private var callModeActive: Boolean = false

    override val isWsOpen: Boolean get() = wsClient?.isOpen == true

    private fun isWebRtcEnabled(): Boolean =
        context.streamnodePrefs()
            .getBoolean(AppConstants.PREF_WEBRTC_ENABLED, AppConstants.WEBRTC_ENABLED_DEFAULT)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun start(url: String, micActive: Boolean, qualityPresetName: String) {
        serverUrl      = normalizeStreamUrl(url)
        this.micActive = micActive
        qualityPreset  = qualityPresetName
        Log.i(TAG, "start url=$serverUrl webRtc=${isWebRtcEnabled()}")

        // Bug #9/#10 fix: if no URL is available yet (Firebase not yet fetched,
        // DEFAULT_SERVER_URL is now empty), defer connection until changeUrl() is
        // called by FirebaseRemoteController's URL watcher.  connectControlSocket()
        // with an empty URL would open a malformed WebSocket and loop on reconnect.
        if (serverUrl.isEmpty()) {
            Log.w(TAG, "start: no server URL yet — deferring connection until Firebase delivers URL")
            setupFirebase()   // still attach Firebase so we get the URL push
            return
        }

        // Always connect control socket + Firebase.
        // Audio/WebRTC streaming is NOT started here — it waits for a "start" server command.
        connectControlSocket()

        if (isWebRtcEnabled()) {
            startWebRtcEngine()
            // Phase 3: Pre-connect standby WS so fallback is instant on ICE failure.
            // audioSuspended=true by default — no frames sent until activateFallback() fires.
            if (standbyWsClient == null) {
                standbyWsClient = AudioWebSocketClient().also {
                    it.sampleRate = sampleRate
                    it.frameMs    = frameMs
                    it.connect(serverUrl, context)
                }
                Log.i(TAG, "Standby WS pre-connected for fallback readiness")
            }
            networkReceiver?.unregister()
            networkReceiver = NetworkChangeReceiver(
                context            = context,
                onNetworkAvailable = {
                    controlClient?.let { c -> if (!c.isOpen) c.reconnectNow() }
                    webRtcEngine?.reconnectSignaling()
                },
                onNetworkLost = {}
            ).also { it.register() }
        }
        // Legacy WebSocket audio client is intentionally NOT started here;
        // connectAudioWebSocket() is called only when micActive becomes true.
    }

    override fun stop() {
        Log.i(TAG, "stop — full teardown")
        stopScreenCapture()
        stopAllCameras()
        networkReceiver?.unregister();  networkReceiver  = null
        standbyWsClient?.disconnect();  standbyWsClient  = null   // Phase 3
        wsClient?.disconnect();         wsClient         = null
        webRtcEngine?.stop();           webRtcEngine     = null
        controlClient?.disconnect();    controlClient    = null
        firebaseController?.apply { pushStatus(StreamStatus.IDLE, serverUrl); stop() }
        firebaseController = null
    }

    /** Stop only the audio stream (mic + WS/WebRTC audio). Keep control+Firebase alive. */
    override fun stopAudioOnly() {
        Log.i(TAG, "stopAudioOnly — keeping control+Firebase connections")
        if (!isWebRtcEnabled()) {
            wsClient?.disconnect(); wsClient = null
        }
        // For WebRTC the audio track mute is handled by MicOrchestrator;
        // the PeerConnection and signaling remain open.
    }

    // ── Intercom callback — forwarded from WebRTCEngine ───────────────────────
    /** Invoked on main thread: true = browser mic active, false = stopped. */
    override var onIntercomActive: ((Boolean) -> Unit)? = null

    // ── WebRTC path ───────────────────────────────────────────────────────────

    private fun startWebRtcEngine() {
        webRtcEngine?.stop()
        webRtcEngine = WebRTCEngine(context).also {
            it.onIntercomActive = { active -> onIntercomActive?.invoke(active) }
            // Phase 3: wire ICE state callbacks into the fallback state machine
            it.onIceConnected = { onWebRtcIceConnected() }
            it.onIceFailed    = { activateFallback() }
            it.start(serverUrl, context)
            it.setAudioEnabled(micActive)
        }
        transitionTo(TransportMode.WEBRTC_NEGOTIATING)
        onConnectionStateChange?.invoke(true)
        Log.i(TAG, "WebRTCEngine started — micActive=$micActive")
    }

    // ── Frame / mic transport (legacy WS path only) ───────────────────────────

    override fun sendFrame(data: ByteArray) = wsClient?.sendFrame(data) ?: Unit

    override fun onMicStateChanged(active: Boolean) {
        micActive = active
        if (!isWebRtcEnabled()) {
            if (active && wsClient?.isOpen != true) {
                // Lazy-connect audio WebSocket only when server has commanded start
                Log.i(TAG, "onMicStateChanged: mic active — connecting audio WebSocket")
                connectAudioWebSocket()
            }
            wsClient?.sendStreamingState(active)
        } else {
            // BUG FIX #3: Mute/unmute the WebRTC audio track to honour the start/stop
            // contract.  Before this fix the audio track was always enabled (default)
            // so audio streamed to the browser from the moment P2P was established,
            // regardless of whether the server had issued a "start" command.
            webRtcEngine?.setAudioEnabled(active)
            // Also send a statusUpdate so the dashboard reflects the correct state.
            val status = if (active) StreamStatus.STREAMING else StreamStatus.CONNECTED_IDLE
            controlClient?.sendStatusUpdate(status)
            Log.i(TAG, "onMicStateChanged [WebRTC]: audio ${if (active) "UN-MUTED" else "MUTED"}, sent statusUpdate $status to /control")
        }
    }

    // ── Screen capture ────────────────────────────────────────────────────────

    override fun startScreenCapture(projection: MediaProjection?, resultData: Intent, width: Int, height: Int, dpi: Int) {
        if (_isScreenSharing) { Log.w(TAG, "startScreenCapture: already sharing"); return }
        Log.i(TAG, "startScreenCapture: ${width}x${height}")
        _isScreenSharing = true

        if (isWebRtcEnabled()) {
            // WebRTC path: projection is null here (StreamingService intentionally does NOT call
            // getMediaProjection() for WebRTC so ScreenCapturerAndroid can consume the token once).
            webRtcEngine?.addScreenTrack(resultData, width, height, dpi)
        } else {
            // Legacy WS path: projection is non-null (created by StreamingService).
            requireNotNull(projection) { "Legacy WS screen capture requires a non-null MediaProjection" }
            screenWsClient = ScreenWebSocketClient().also { it.connect(serverUrl, context) }
            screenEngine   = ScreenCaptureEngine(
                projection  = projection, width = width, height = height, densityDpi = dpi,
                onNalUnit   = { nal -> screenWsClient?.sendFrame(nal) },
                onError     = { msg -> Log.e(TAG, "ScreenCapture: $msg"); stopScreenCapture() }
            ).also { it.start() }
        }
    }

    override fun stopScreenCapture() {
        if (!_isScreenSharing) return
        Log.i(TAG, "stopScreenCapture")
        _isScreenSharing = false
        if (isWebRtcEnabled()) {
            webRtcEngine?.removeVideoTrack()
        } else {
            screenEngine?.stop();         screenEngine   = null
            screenWsClient?.disconnect(); screenWsClient = null
        }
    }

    // ── Camera capture ────────────────────────────────────────────────────────

    override fun startCameraCapture(facing: CameraFacing) {
        Log.i(TAG, "startCameraCapture[$facing]")
        if (isWebRtcEnabled()) {
            webRtcEngine?.addCameraTrack(facing)
        } else {
            if (cameraOrchestrator == null) cameraOrchestrator = CameraOrchestrator(context)
            cameraOrchestrator?.startCamera(facing, serverUrl)
        }
    }

    override fun stopCameraCapture(facing: CameraFacing) {
        Log.i(TAG, "stopCameraCapture[$facing]")
        if (isWebRtcEnabled()) {
            webRtcEngine?.removeVideoTrack()
        } else {
            cameraOrchestrator?.stopCamera(facing)
            if (cameraOrchestrator?.isFrontActive == false && cameraOrchestrator?.isBackActive == false)
                cameraOrchestrator = null
        }
    }

    override fun stopAllCameras() {
        if (isWebRtcEnabled()) {
            webRtcEngine?.removeVideoTrack()
        } else {
            cameraOrchestrator?.stopAll()
            cameraOrchestrator = null
        }
    }

    // ── Reconnect / URL change ────────────────────────────────────────────────

    override fun reconnectNow() {
        Log.i(TAG, "reconnectNow — micActive=$micActive webRtc=${isWebRtcEnabled()}")
        if (isWebRtcEnabled()) {
            controlClient?.reconnectNow()
            webRtcEngine?.stop()
            webRtcEngine = WebRTCEngine(context).also {
                it.onIntercomActive = { active -> onIntercomActive?.invoke(active) }
                it.onIceConnected   = { onWebRtcIceConnected() }   // Phase 3
                it.onIceFailed      = { activateFallback() }       // Phase 3
                it.start(serverUrl, context)
                it.setAudioEnabled(micActive)   // FIX: persist desired audio state before async init
            }
            // Phase 3: reconnect standby WS as well so it's ready for the next fallback
            standbyWsClient?.reconnectNow()
            transitionTo(TransportMode.WEBRTC_NEGOTIATING)
        } else {
            controlClient?.reconnectNow()
            if (micActive) wsClient?.reconnectNow()
        }
    }

    override fun changeUrl(newUrl: String) {
        serverUrl = normalizeStreamUrl(newUrl)
        context.streamnodePrefs().edit()
            .putString(AppConstants.PREF_SERVER_URL, serverUrl).apply()
        // Control socket always follows URL changes (both paths)
        controlClient?.reconnectTo(serverUrl, context)
        if (isWebRtcEnabled()) {
            webRtcEngine?.stop()
            webRtcEngine = WebRTCEngine(context).also {
                it.onIntercomActive = { active -> onIntercomActive?.invoke(active) }
                it.onIceConnected   = { onWebRtcIceConnected() }   // Phase 3
                it.onIceFailed      = { activateFallback() }       // Phase 3
                it.start(serverUrl, context)
                it.setAudioEnabled(micActive)   // FIX: persist audio state across URL changes
            }
            // Phase 3: reconnect standby WS to the new URL
            standbyWsClient?.disconnect()
            standbyWsClient = AudioWebSocketClient().also {
                it.sampleRate = sampleRate; it.frameMs = frameMs
                it.connect(serverUrl, context)
            }
            transitionTo(TransportMode.WEBRTC_NEGOTIATING)
            networkReceiver?.unregister()
            networkReceiver = NetworkChangeReceiver(
                context            = context,
                onNetworkAvailable = {
                    controlClient?.let { c -> if (!c.isOpen) c.reconnectNow() }
                    webRtcEngine?.reconnectSignaling()
                },
                onNetworkLost = {}
            ).also { it.register() }
        } else {
            if (micActive) wsClient?.connect(serverUrl, context)
        }
        Log.i(TAG, "changeUrl → $serverUrl  micActive=$micActive")
    }

    // ── Status / metrics push ─────────────────────────────────────────────────

    override fun pushStatus(status: StreamStatus) =
        firebaseController?.pushStatus(status, serverUrl) ?: Unit

    override fun pushRealtimeMetrics(fps: Float, kbps: Float, uptimeSec: Int, quality: String) =
        firebaseController?.pushRealtimeMetrics(fps, kbps, uptimeSec, quality) ?: Unit

    override fun sendStatusUpdate(status: StreamStatus) {
        callModeActive = (status == StreamStatus.CALL_ACTIVE)
        controlClient?.sendStatusUpdate(status) ?: Unit
    }

    override fun sendControlJson(json: String) {
        controlClient?.send(json)
    }

    /** Feature 6 — forward JPEG bytes from snapshot capture to the /control WebSocket. */
    override fun sendControlBinary(data: ByteArray) {
        controlClient?.sendBinary(data)
    }

    override fun fetchServerUrl(onSuccess: (String) -> Unit, onFailure: (() -> Unit)?) {
        FirebaseRemoteController(context).fetchServerUrl(onSuccess, onFailure)
    }

    // ── Private wiring ────────────────────────────────────────────────────────

    private fun connectControlSocket() {
        controlClient?.disconnect()
        controlClient = AudioControlClient().apply {
            onStatusChange = { wsStatus ->
                when (wsStatus) {
                    StreamStatus.CONNECTED_IDLE -> {
                        StreamLogger.log("🔌 Control WebSocket connected — waiting for server command")
                        if (!micActive)
                            this@ConnectionOrchestrator.onStatusChange?.invoke(StreamStatus.CONNECTED_IDLE)
                    }
                    StreamStatus.RECONNECTING -> {
                        StreamLogger.log("🔄 Control WebSocket reconnecting…")
                        this@ConnectionOrchestrator.onStatusChange?.invoke(StreamStatus.RECONNECTING)
                    }
                    StreamStatus.CONNECTING -> {
                        StreamLogger.log("📡 Control WebSocket connecting…")
                    }
                    else -> {}
                }
            }
            onCmd = { commandId, action, url ->
                StreamLogger.log("📨 WS CMD received: action='$action'  id=${commandId.take(8)}")
                onCommand?.invoke(commandId, action, url, "websocket")
            }
            onInput = { kind, x, y, x1, y1, x2, y2, durationMs, keycode ->
                CommandProcessor.processInput(kind, x, y, x1, y1, x2, y2, durationMs, keycode)
            }
            // Phase 6 Step 29: forward pong-based RTT measurements to the adaptive
            // bitrate ladder via the onRttUpdate callback (wired in StreamingService).
            // BUG FIX #4c: ALSO store the latest RTT locally so sendTransportStatus()
            // can include it in the transportStatus message. Without this, the server's
            // ch.transportRttMs was always null, breaking the RTT display on the dashboard.
            onRttMeasured = { rtt ->
                lastKnownRttMs = rtt
                onRttUpdate?.invoke(rtt)
            }
            connect(serverUrl, context)
        }
        setupFirebase()
    }

    private fun connectAudioWebSocket() {
        wsClient?.disconnect()
        val client = AudioWebSocketClient().also {
            it.sampleRate = sampleRate
            it.frameMs    = frameMs
            wsClient      = it
        }
        client.onStatusChange = { wsStatus ->
            val isConnected = wsStatus == StreamStatus.CONNECTED_IDLE ||
                              wsStatus == StreamStatus.STREAMING
            onConnectionStateChange?.invoke(isConnected)
            val effective = resolveEffectiveStatus(wsStatus)
            onStatusChange?.invoke(effective)
            firebaseController?.pushStatus(effective, serverUrl)
            controlClient?.sendStatusUpdate(effective)
            if (wsStatus == StreamStatus.CONNECTED_IDLE) client.sendStreamingState(micActive)
        }
        networkReceiver?.unregister()
        networkReceiver = NetworkChangeReceiver(
            context            = context,
            onNetworkAvailable = {
                if (!client.isOpen) client.reconnectNow()
                if (controlClient?.isOpen != true) controlClient?.reconnectNow()
            },
            onNetworkLost = {}
        ).also { it.register() }
        client.connect(serverUrl, context)
    }

    private fun setupFirebase() {
        firebaseController?.stop()
        firebaseController = FirebaseRemoteController(context).apply {
            // Push CONNECTED_IDLE so the dashboard shows the device is online but not yet streaming
            pushStatus(StreamStatus.CONNECTED_IDLE, serverUrl)
            onCommandReceived = { commandId, action, url ->
                onCommand?.invoke(commandId, action, url, "firebase")
            }
            // Gap 3 fix: attach live serverUrl_v2 push listener so URL changes are
            // handled in real-time without waiting for a poll interval.
            onServerUrlChanged = { newUrl ->
                Log.i(TAG, "Firebase live URL change → $newUrl")
                changeUrl(newUrl)
            }
            // Gap 2 fix: attach server-restart watcher so when the server comes back
            // after being offline, all devices reconnect in <3 s (Firebase push latency)
            // instead of waiting up to 5 min for the Phase-2 backoff timer to fire.
            onServerRestarted = {
                Log.i(TAG, "Server restart detected via Firebase — resetting backoff and reconnecting")
                StreamLogger.log("🔄 Server restarted — reconnecting immediately")
                reconnectNow()
            }
            start()
            // Gap 3 fix: attach the persistent serverUrl watcher after start() so
            // the initial cached value (= currentUrl) is suppressed by the guard.
            startServerUrlWatcher(serverUrl)
            // Gap 2 fix: attach server-restart watcher, seeding with stored epoch so
            // a cache-hit from the last session doesn't trigger a spurious reconnect.
            startServerStartedWatcher(
                context.streamnodePrefs().getLong(AppConstants.PREF_SERVER_STARTED_AT, 0L)
            )
        }
        Log.i(TAG, "Firebase listener started — device visible on dashboard as CONNECTED_IDLE")
    }

    private fun resolveEffectiveStatus(wsStatus: StreamStatus): StreamStatus = when {
        wsStatus == StreamStatus.CONNECTED_IDLE && callModeActive -> StreamStatus.CALL_ACTIVE
        wsStatus == StreamStatus.CONNECTED_IDLE && micActive      -> StreamStatus.STREAMING
        else -> wsStatus
    }

    private fun normalizeStreamUrl(url: String): String {
        if (url.isBlank()) return ""   // Bug #9 fix: propagate empty URL as-is
        // Strip trailing slashes first so we never produce "host//stream".
        val trimmed = url.trimEnd('/')
        return try {
            val uri  = java.net.URI(trimmed)
            // uri.path never contains the query string — "/stream?" is impossible here.
            val path = uri.path?.trimEnd('/') ?: ""
            when {
                // Bug #10 fix: ALL known server paths must be explicitly listed here so
                // a URL that was already normalized (e.g. "wss://host/stream" stored in
                // prefs) is returned as-is instead of becoming "wss://host/stream/stream".
                path == "/stream"   -> trimmed
                path == "/control"  -> trimmed
                path == "/listen"   -> trimmed
                path == "/signal"   -> trimmed
                // Any other non-empty path segment → leave as-is (custom deployment).
                path.isNotEmpty() && path != "/" -> trimmed
                // Bare host (no path) — append the default stream path.
                else -> "$trimmed/stream"
            }
        } catch (e: Exception) {
            Log.w(TAG, "normalizeStreamUrl: could not parse '$trimmed': ${e.message}")
            // Fallback: string-based check covers already-normalised URLs
            if ("/stream" in trimmed || "/control" in trimmed ||
                "/signal" in trimmed || "/listen" in trimmed) trimmed
            else "$trimmed/stream"
        }
    }
}
