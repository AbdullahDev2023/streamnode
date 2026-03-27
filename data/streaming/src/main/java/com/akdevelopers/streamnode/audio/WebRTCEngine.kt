package com.akdevelopers.streamnode.audio

import android.content.Context
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import com.akdevelopers.streamnode.service.CameraFacing
import com.akdevelopers.streamnode.service.StreamLogger
import com.akdevelopers.streamnode.service.StreamIdentity
import com.akdevelopers.streamnode.core.AppConstants
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * WebRTCEngine — manages one RTCPeerConnection for StreamNode streaming.
 *
 * Tracks to add:
 *   - Audio:  always added from the device microphone via WebRTC's AudioDeviceModule.
 *   - Video:  optional. Call addScreenTrack() or addCameraTrack() after start().
 *
 * Lifecycle:
 *   start(serverUrl, context)  → fetches ICE config, creates PeerConnection, opens signaling WS
 *   addScreenTrack(projection) → adds screen capture video track (if screen share is active)
 *   addCameraTrack(facing)     → adds camera video track (front or back)
 *   removeVideoTrack()         → removes any active video track (screen / camera)
 *   stop()                     → tears down everything cleanly
 *
 * The engine is the *offerer*: whenever a browser connects via signaling,
 * [SignalingClient.onConnected]/phone-ready triggers [createAndSendOffer].
 * Each browser that connects results in a fresh offer → answer exchange.
 */
class WebRTCEngine(private val context: Context) {

    private val log = com.akdevelopers.streamnode.analytics.StreamNodeLogger.forModule("WebRTC")
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── WebRTC core objects ───────────────────────────────────────────────────
    private var factory:        PeerConnectionFactory? = null
    private var pc:             PeerConnection?        = null
    private var signalingClient: SignalingClient?      = null
    private var eglBase:        EglBase?               = null

    // ── Tracks ────────────────────────────────────────────────────────────────
    private var audioTrack: AudioTrack? = null

    // Screen share track
    private var screenVideoTrack:   VideoTrack?  = null
    private var screenVideoSource:  VideoSource? = null
    private var screenCapturer:     VideoCapturer? = null
    private var screenVideoSender:  RtpSender?  = null

    // Front camera track
    private var frontVideoTrack:    VideoTrack?  = null
    private var frontVideoSource:   VideoSource? = null
    private var frontCameraCapturer: VideoCapturer? = null
    private var frontVideoSender:   RtpSender?  = null

    // Back camera track
    private var backVideoTrack:     VideoTrack?  = null
    private var backVideoSource:    VideoSource? = null
    private var backCameraCapturer: VideoCapturer? = null
    private var backVideoSender:    RtpSender?  = null

    /** Remote audio track received from browser intercom — null when inactive. */
    private var remoteAudioTrack: AudioTrack? = null

    // ── Intercom callback ─────────────────────────────────────────────────────
    /**
     * Invoked on the main thread when the browser's intercom audio track
     * arrives (true) or is removed via peer-left (false).
     * StreamingService uses this to update the notification.
     */
    var onIntercomActive: ((Boolean) -> Unit)? = null

    // ── Phase 3: Transport state callbacks ────────────────────────────────────
    /**
     * Invoked on the main thread when ICE reaches CONNECTED state.
     * ConnectionOrchestrator uses this to switch to WEBRTC_P2P and
     * disconnect the standby WebSocket audio client.
     */
    var onIceConnected: (() -> Unit)? = null

    /**
     * Invoked on the main thread when ICE reaches FAILED state.
     * ConnectionOrchestrator uses this to activate the standby WebSocket
     * fallback with zero audio gap.
     */
    var onIceFailed: (() -> Unit)? = null

    // ── State ─────────────────────────────────────────────────────────────────
    @Volatile private var running      = false
    @Volatile private var renegotiationPending = false
    private var serverUrl = ""
    private var iceServers: List<PeerConnection.IceServer> = defaultIceServers()

    /**
     * FIX — Race-condition guard for async audio track initialisation.
     *
     * Problem: start() kicks off a background Thread to fetch /ice-config; then
     * buildPeerConnectionAndSignal() posts back to mainHandler when done.
     * setAudioEnabled() is called by ConnectionOrchestrator immediately after
     * start() returns — BEFORE the background work finishes and audioTrack is
     * created.  The `?.` safe-call makes it a silent no-op, leaving the track
     * hardcoded to disabled=false (muted) forever.
     *
     * Fix: persist the desired state here; buildPeerConnectionAndSignal()
     * reads it when creating the track instead of hardcoding `false`.
     */
    @Volatile private var desiredAudioEnabled: Boolean = false

    // ── ICE trickle candidate queue ───────────────────────────────────────────
    // Candidates from the remote peer that arrive BEFORE setRemoteDescription
    // completes are queued here and drained immediately after onSetSuccess fires.
    // This is the correct ICE trickle implementation per RFC 8838 §4.
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    @Volatile private var remoteDescriptionSet = false

    companion object {
        private const val AUDIO_TRACK_ID        = "streamnode_audio"
        private const val FRONT_VIDEO_TRACK_ID  = "streamnode_video_front"
        private const val BACK_VIDEO_TRACK_ID   = "streamnode_video_back"
        private const val SCREEN_VIDEO_TRACK_ID = "streamnode_video_screen"
        private const val STREAM_ID             = "streamnode_stream"
        private const val ICE_FETCH_TIMEOUT_S = 5L

        /**
         * Fallback ICE servers used when /ice-config fetch fails.
         * STUN-only — TURN is permanently disabled in StreamNode.
         * Symmetric NAT cases fall back to the WS relay (<50 ms switchover).
         */
        private fun defaultIceServers() = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )

        /** Init PeerConnectionFactory once per process — safe to call multiple times. */
        fun initializeOnce(context: Context) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
        }
    }

    // ── Public lifecycle ──────────────────────────────────────────────────────

    fun start(streamUrl: String, ctx: Context) {
        if (running) { log.w("start: already running"); return }
        serverUrl = streamUrl
        running   = true
        log.i("start", "url" to serverUrl)
        StreamLogger.log("🔧 WebRTC engine starting…")
        initializeOnce(ctx)
        fetchIceConfigThenStart(ctx)
    }

    /**
     * Add a screen-share video track.
     *
     * Takes [resultData] (the raw MediaProjection permission result Intent) rather than an
     * already-created [MediaProjection] object. This is intentional: [ScreenCapturerAndroid]
     * calls [MediaProjectionManager.getMediaProjection] internally, so the caller (StreamingService)
     * must NOT call it beforehand — consuming the one-time token twice causes a SecurityException
     * on Android 10+ (API 29+).
     */
    fun addScreenTrack(resultData: android.content.Intent, width: Int, height: Int, dpi: Int) {
        if (!running) return
        log.i("addScreenTrack", "width" to width, "height" to height, "dpi" to dpi)
        removeAllVideoTracks()
        val capturer = ScreenCapturerAndroid(
            resultData,
            object : MediaProjection.Callback() {}
        )
        val src   = factory!!.createVideoSource(capturer.isScreencast)
        val track = factory!!.createVideoTrack(SCREEN_VIDEO_TRACK_ID, src)
        val surfHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase!!.eglBaseContext)
        capturer.initialize(surfHelper, context, src.capturerObserver)
        capturer.startCapture(width, height, 30)
        screenVideoSource  = src
        screenVideoTrack   = track
        screenCapturer     = capturer
        screenVideoSender  = pc!!.addTrack(track, listOf(STREAM_ID))
        StreamLogger.log("🖥️ Screen video track added — renegotiating…")
        scheduleRenegotiation()
    }

    /**
     * Add a camera video track (FRONT or BACK). Both can run simultaneously.
     * Calling this for a facing that is already active replaces that track.
     * Triggers renegotiation.
     */
    fun addCameraTrack(facing: CameraFacing) {
        if (!running) return
        if (factory == null || eglBase == null || pc == null) {
            log.w("addCameraTrack skipped — WebRTC not yet initialized",
                "facing" to facing, "factory" to factory, "eglBase" to eglBase, "pc" to pc)
            return
        }
        log.i("addCameraTrack", "facing" to facing)
        // Remove only the same-facing track so the other camera stays active
        removeCameraTrackSilent(facing)
        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.firstOrNull { name ->
            if (facing == CameraFacing.FRONT) enumerator.isFrontFacing(name)
            else                              enumerator.isBackFacing(name)
        } ?: run { log.e("No camera found", "facing" to facing); return }
        val capturer = enumerator.createCapturer(deviceName, null)
            ?: run { log.e("createCapturer failed", "facing" to facing); return }

        val trackId = if (facing == CameraFacing.FRONT) FRONT_VIDEO_TRACK_ID else BACK_VIDEO_TRACK_ID
        val src     = factory!!.createVideoSource(capturer.isScreencast)
        val track   = factory!!.createVideoTrack(trackId, src)
        val surfHelper = SurfaceTextureHelper.create(
            if (facing == CameraFacing.FRONT) "FrontCamThread" else "BackCamThread",
            eglBase!!.eglBaseContext
        )
        capturer.initialize(surfHelper, context, src.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        if (facing == CameraFacing.FRONT) {
            frontVideoSource    = src
            frontVideoTrack     = track
            frontCameraCapturer = capturer
            frontVideoSender    = pc!!.addTrack(track, listOf(STREAM_ID))
        } else {
            backVideoSource    = src
            backVideoTrack     = track
            backCameraCapturer = capturer
            backVideoSender    = pc!!.addTrack(track, listOf(STREAM_ID))
        }
        StreamLogger.log("📷 Camera track added [${facing.name}] — renegotiating…")
        scheduleRenegotiation()
    }

    /** Remove a specific camera track by facing, leaving the other camera intact. */
    fun removeCameraTrack(facing: CameraFacing) {
        removeCameraTrackSilent(facing)
        if (running) scheduleRenegotiation()
    }

    /** Remove all video tracks (screen + front cam + back cam) and trigger renegotiation. */
    fun removeAllVideoTracks() {
        removeAllVideoTracksSilent()
        if (running) scheduleRenegotiation()
    }

    /** @deprecated Use removeAllVideoTracks() */
    fun removeVideoTrack() = removeAllVideoTracks()

    fun stop() {
        if (!running) return
        log.i("stop")
        running = false
        desiredAudioEnabled = false        // reset so a fresh start() begins muted
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()
        mainHandler.removeCallbacksAndMessages(null)
        signalingClient?.disconnect(); signalingClient = null
        deactivateIntercom()
        removeAllVideoTracksSilent()
        audioTrack?.dispose();  audioTrack  = null
        pc?.dispose();          pc           = null
        factory?.dispose();     factory      = null
        eglBase?.release();     eglBase      = null
    }

    /**
     * Gap 5 fix: immediately reset + reconnect the SignalingClient after a
     * network-change event, without tearing down the PeerConnection.
     * This collapses potentially long backoff timers so the browser sees the
     * device come back online as quickly as possible after network restore.
     */
    fun reconnectSignaling() {
        log.i("reconnectSignaling — resetting SignalingClient backoff")
        signalingClient?.reconnectNow()
    }

    /**
     * BUG FIX #2/#3: Mute or unmute the outgoing WebRTC audio track.
     *
     * Called by [ConnectionOrchestrator.onMicStateChanged] so the WebRTC audio
     * respects the same start/stop contract as the legacy WebSocket path:
     *   - enabled=false  → audio track muted  → browser hears silence (CONNECTED_IDLE)
     *   - enabled=true   → audio track active → browser hears microphone (STREAMING)
     *
     * Safe to call from any thread — simply toggles a flag on the native track.
     * No-op if the audio track has not been created yet (called before [start]).
     */
    fun setAudioEnabled(enabled: Boolean) {
        desiredAudioEnabled = enabled          // persist — survives async track creation
        audioTrack?.setEnabled(enabled)        // apply immediately if track already exists
        log.i("setAudioEnabled", "enabled" to enabled, "trackReady" to (audioTrack != null))
        StreamLogger.log(if (enabled) "🎤 WebRTC audio track UN-MUTED — streaming" else "🔇 WebRTC audio track MUTED — stopped")
    }

    // ── ICE config fetch ──────────────────────────────────────────────────────

    private fun fetchIceConfigThenStart(ctx: Context) {
        // Convert WS scheme to HTTP for the REST call.
        // wss:// → https://  (production ngrok / TLS)
        // ws://  → http://   (local non-TLS dev server — do NOT upgrade to https)
        // Strip all known path suffixes (/stream, /control, /signal, /listen)
        // with or without query string, then append /ice-config.
        // Including /signal here prevents a double-path bug when serverUrl has already
        // been changed to a /signal URL via a change_url command.
        val httpUrl = serverUrl
            .replaceFirst(Regex("^wss://"), "https://")
            .replaceFirst(Regex("^ws://"),  "http://")
            .replaceFirst(Regex("/(stream|control|signal|listen)(\\?.*)?$"), "")
            .trimEnd('/') + AppConstants.WEBRTC_ICE_CONFIG_PATH

        Thread {
            try {
                val resp = OkHttpClient.Builder()
                    .callTimeout(ICE_FETCH_TIMEOUT_S, TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(httpUrl)
                        .header("ngrok-skip-browser-warning", "true").build())
                    .execute()
                val body = resp.body?.string() ?: ""
                val arr  = JSONObject(body).getJSONArray("iceServers")
                val servers = mutableListOf<PeerConnection.IceServer>()
                for (i in 0 until arr.length()) {
                    val obj  = arr.getJSONObject(i)
                    val urls = obj.optString("urls", "")
                    // ── STUN-only enforcement (defense-in-depth) ──────────────────
                    // StreamNode never uses TURN.  The server's config.ts already rejects
                    // TURN env vars at startup, but we filter here too so that even a
                    // misconfigured or third-party /ice-config response can never inject
                    // a TURN entry into the PeerConnection.
                    // Symmetric NAT → ICE FAILED → WS relay fallback (<50 ms switchover).
                    if (!urls.startsWith("stun:", ignoreCase = true) &&
                        !urls.startsWith("stuns:", ignoreCase = true)) {
                        log.w("ICE entry rejected (non-STUN, TURN disabled)", "urls" to urls)
                        StreamLogger.log("⚠️ ICE entry rejected: $urls (TURN disabled — using WS relay for hard NAT)")
                        continue
                    }
                    val b = PeerConnection.IceServer.builder(urls)
                    if (obj.has("username"))   b.setUsername(obj.getString("username"))
                    if (obj.has("credential")) b.setPassword(obj.getString("credential"))
                    servers.add(b.createIceServer())
                }
                iceServers = servers
                log.i("ICE config fetched", "serverCount" to servers.size)
                StreamLogger.log("🌐 ICE config fetched — ${servers.size} server(s)")
            } catch (e: Exception) {
                log.w("ICE config fetch failed — using defaults", "error" to e.message)
                StreamLogger.log("⚠️ ICE fetch failed (${e.message}) — using STUN defaults")
            }
            mainHandler.post { buildPeerConnectionAndSignal(ctx) }
        }.start()
    }

    // ── PeerConnection + signaling setup ──────────────────────────────────────

    private fun buildPeerConnectionAndSignal(ctx: Context) {
        if (!running) return
        eglBase = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics             = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy             = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy            = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Allow all ICE transport types so STUN probing works across UDP and TCP.
            // Explicit assignment avoids reliance on implementation-specific defaults.
            iceTransportsType        = PeerConnection.IceTransportsType.ALL
        }

        pc = factory!!.createPeerConnection(rtcConfig, PcObserver())
            ?: run { log.e("createPeerConnection returned null"); return }

        // Always add audio track — apply the desired enabled state.
        // FIX: Previously hardcoded to setEnabled(false) here, which permanently muted
        // audio because setAudioEnabled() is called before this async init completes
        // and the `?.` safe-call is a silent no-op on a null track.
        // Now we read desiredAudioEnabled (set by setAudioEnabled()) so the correct
        // mute/unmute state is applied the moment the track is created.
        val audioSource = factory!!.createAudioSource(MediaConstraints())
        audioTrack = factory!!.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        audioTrack!!.setEnabled(desiredAudioEnabled)   // honour state set before async init
        pc!!.addTrack(audioTrack!!, listOf(STREAM_ID))
        StreamLogger.log("🎤 Audio track added to PeerConnection (enabled=$desiredAudioEnabled)")

        signalingClient = SignalingClient(
            onMessage    = ::handleSignalingMessage,
            onConnected  = {
                log.i("Signaling connected")
                StreamLogger.log("📡 Signaling WebSocket connected")
            },
            onDisconnected = {
                if (running) {
                    log.w("Signaling disconnected — will reconnect")
                    StreamLogger.log("⚠️ Signaling disconnected — will reconnect")
                }
            }
        ).also { it.connect(serverUrl, ctx) }
    }

    // ── Offer / answer ────────────────────────────────────────────────────────

    /**
     * Feature 1 — frame size hint for the remote browser's jitter buffer.
     * Set by StreamingService whenever MicOrchestrator applies a new config.
     * The value is injected as an SDP `a=ptime:<ms>` attribute in every offer.
     */
    var currentFrameMs: Int = 20  // FIX: 60 → 20 to match HIGH_QUALITY.frameMs; injected as a=ptime in SDP

    private fun createAndSendOffer() {
        val pcLocal = pc ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pcLocal.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                // Feature 1: inject a=ptime so the browser knows our Opus frame size.
                // Insert "a=ptime:<ms>" after every "m=audio" line in the SDP.
                val ptime      = currentFrameMs
                val patchedSdp = sdp.description
                    .replace(Regex("(m=audio[^\r\n]*[\r\n]+)"), "$1a=ptime:$ptime\r\n")
                val patchedDesc = SessionDescription(sdp.type, patchedSdp)

                pcLocal.setLocalDescription(object : SimpleSdpObserver("setLocal") {
                    override fun onSetSuccess() {
                        log.i("Sending offer SDP", "ptime" to ptime)
                        StreamLogger.log("📤 Sending WebRTC offer to browser (ptime=${ptime}ms)")
                        signalingClient?.send(JSONObject().apply {
                            put("type", "offer"); put("sdp", patchedDesc.description)
                        })
                    }
                }, patchedDesc)
            }
        }, constraints)
    }

    private fun handleSignalingMessage(msg: JSONObject) {
        val pcLocal = pc ?: return
        when (val type = msg.optString("type")) {
            "phone-ready"  -> {
                log.i("Browser connected — creating offer")
                StreamLogger.log("🖥️ Browser connected — creating WebRTC offer")
                mainHandler.post { createAndSendOffer() }
            }
            "answer" -> {
                StreamLogger.log("📥 Received WebRTC answer from browser")
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, msg.getString("sdp"))
                // ICE trickle fix: drain queued candidates AFTER setRemoteDescription succeeds.
                // Candidates added before remote desc is set are discarded by libwebrtc.
                pcLocal.setRemoteDescription(object : SimpleSdpObserver("setRemote") {
                    override fun onSetSuccess() {
                        mainHandler.post {
                            remoteDescriptionSet = true
                            if (pendingRemoteCandidates.isNotEmpty()) {
                                log.i("Draining ${pendingRemoteCandidates.size} queued ICE candidates after answer")
                                StreamLogger.log("🧊 Draining ${pendingRemoteCandidates.size} queued ICE candidate(s)")
                                pendingRemoteCandidates.forEach { pcLocal.addIceCandidate(it) }
                                pendingRemoteCandidates.clear()
                            }
                        }
                    }
                }, sdp)
            }
            "offer" -> {
                log.i("Re-offer received from browser — answering for intercom")
                StreamLogger.log("🔄 Re-offer from browser — renegotiating for intercom")
                val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, msg.getString("sdp"))
                pcLocal.setRemoteDescription(object : SimpleSdpObserver("setRemote-reOffer") {
                    override fun onSetSuccess() {
                        pcLocal.createAnswer(object : SimpleSdpObserver("createAnswer-reOffer") {
                            override fun onCreateSuccess(sdp: SessionDescription) {
                                pcLocal.setLocalDescription(object : SimpleSdpObserver("setLocal-reOffer") {
                                    override fun onSetSuccess() {
                                        log.i("Sending answer for browser re-offer")
                                        StreamLogger.log("📤 Sending WebRTC answer to browser")
                                        signalingClient?.send(JSONObject().apply {
                                            put("type", "answer")
                                            put("sdp",  sdp.description)
                                        })
                                    }
                                }, sdp)
                            }
                        }, MediaConstraints())
                    }
                }, remoteSdp)
            }
            "ice-candidate" -> {
                val c = msg.optJSONObject("candidate") ?: return
                val sdp = c.optString("candidate", "")
                if (sdp.isBlank()) {
                    log.d("Skipping empty ICE candidate (end-of-candidates marker from browser)")
                    return
                }
                val candidate = IceCandidate(
                    c.optString("sdpMid", ""),
                    c.optInt("sdpMLineIndex", 0),
                    sdp
                )
                mainHandler.post {
                    if (remoteDescriptionSet) {
                        pcLocal.addIceCandidate(candidate)
                    } else {
                        // ICE trickle: queue candidates that arrive before the answer is processed.
                        log.d("Queuing ICE candidate (remote desc not yet set)", "sdpMid" to candidate.sdpMid)
                        pendingRemoteCandidates.add(candidate)
                    }
                }
            }
            "waiting"    -> { log.i("No browser yet — waiting"); StreamLogger.log("⏳ No browser connected yet — waiting…") }
            "peer-left"  -> {
                log.i("Browser disconnected — stopping intercom")
                StreamLogger.log("👋 Browser disconnected")
                mainHandler.post { deactivateIntercom() }
            }
            "welcome"    -> { log.i("Signaling welcome received"); StreamLogger.log("✅ Signaling server welcomed us") }
            else         -> log.d("Unhandled signaling type", "type" to type)
        }
    }

    private fun deactivateIntercom() {
        remoteAudioTrack?.setEnabled(false)
        remoteAudioTrack = null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode             = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = false
        onIntercomActive?.invoke(false)
        log.i("Intercom deactivated — AudioManager restored")
    }

    // ── Video track helpers ───────────────────────────────────────────────────

    /** Silently dispose and remove one camera facing (no renegotiation). */
    private fun removeCameraTrackSilent(facing: CameraFacing) {
        if (facing == CameraFacing.FRONT) {
            frontCameraCapturer?.stopCapture(); frontCameraCapturer?.dispose(); frontCameraCapturer = null
            frontVideoTrack?.dispose();  frontVideoTrack  = null
            frontVideoSource?.dispose(); frontVideoSource = null
            frontVideoSender?.let { runCatching { pc?.removeTrack(it) } }; frontVideoSender = null
        } else {
            backCameraCapturer?.stopCapture(); backCameraCapturer?.dispose(); backCameraCapturer = null
            backVideoTrack?.dispose();  backVideoTrack  = null
            backVideoSource?.dispose(); backVideoSource = null
            backVideoSender?.let { runCatching { pc?.removeTrack(it) } }; backVideoSender = null
        }
    }

    /** Silently dispose all video tracks (no renegotiation). Used by stop() and addScreenTrack(). */
    private fun removeAllVideoTracksSilent() {
        screenCapturer?.stopCapture(); screenCapturer?.dispose(); screenCapturer = null
        screenVideoTrack?.dispose();   screenVideoTrack  = null
        screenVideoSource?.dispose();  screenVideoSource = null
        screenVideoSender?.let { runCatching { pc?.removeTrack(it) } }; screenVideoSender = null
        removeCameraTrackSilent(CameraFacing.FRONT)
        removeCameraTrackSilent(CameraFacing.BACK)
    }

    private fun scheduleRenegotiation() {
        if (renegotiationPending) return
        renegotiationPending = true
        // OPT §4.5: increased from 200 ms → 500 ms so that rapid multi-track adds
        // (e.g. front camera + back camera toggled in quick succession) are batched
        // into a single offer/answer cycle instead of firing 2–3 separate ones.
        mainHandler.postDelayed({
            renegotiationPending = false
            if (running) createAndSendOffer()
        }, 500)
    }

    // ── PeerConnection observer ───────────────────────────────────────────────

    private inner class PcObserver : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            // Null/empty sdp = end-of-candidates marker emitted by libwebrtc when gathering
            // pauses. Do NOT send these — the browser's addIceCandidate(null) throws even
            // with .catch(). We use GATHER_CONTINUALLY so gathering resumes automatically.
            if (candidate.sdp.isNullOrBlank()) {
                log.d("Skipping null/empty ICE candidate (end-of-candidates marker)")
                return
            }
            log.d("ICE candidate", "sdpMid" to candidate.sdpMid)
            signalingClient?.send(JSONObject().apply {
                put("type", "ice-candidate")
                put("candidate", JSONObject().apply {
                    put("sdpMid",        candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate",     candidate.sdp)
                })
            })
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            log.i("PeerConnection state changed", "state" to state)
            StreamLogger.log("🔗 PeerConnection → $state")
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            log.i("ICE connection changed", "state" to state)
            StreamLogger.log("🧊 ICE → $state")
            if (state == PeerConnection.IceConnectionState.CONNECTED ||
                state == PeerConnection.IceConnectionState.COMPLETED) {
                StreamLogger.log("✅ WebRTC P2P established — audio streaming direct to browser!")
                mainHandler.post { onIceConnected?.invoke() }
            }
            if (state == PeerConnection.IceConnectionState.FAILED) {
                log.w("ICE FAILED — restarting ICE + notifying fallback handler")
                StreamLogger.log("❌ ICE FAILED — activating WebSocket fallback")
                mainHandler.post {
                    onIceFailed?.invoke()
                    pc?.restartIce()
                    scheduleRenegotiation()
                }
            }
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState?)         {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
            log.i("ICE gathering changed", "state" to s)
            StreamLogger.log("🔍 ICE gathering → $s")
            if (s == PeerConnection.IceGatheringState.COMPLETE)
                StreamLogger.log("✅ ICE gathering complete — all candidates sent to browser")
        }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?)          {}
        override fun onIceConnectionReceivingChange(b: Boolean)                   {}
        override fun onAddStream(s: MediaStream?)                                  {}
        override fun onRemoveStream(s: MediaStream?)                               {}
        override fun onDataChannel(d: DataChannel?)                                {}
        override fun onRenegotiationNeeded() {
            // Guard: only send offer once signaling is open.
            // addTrack() fires this callback during buildPeerConnectionAndSignal()
            // BEFORE signalingClient is even created — dropping the offer here is safe
            // because the first real offer is triggered by the "phone-ready" message.
            if (running && signalingClient?.isOpen == true) {
                mainHandler.post { createAndSendOffer() }
            }
        }
        override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?)      {}
        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() ?: return
            if (track.kind() != MediaStreamTrack.AUDIO_TRACK_KIND) return
            log.i("Remote audio track received — activating intercom")
            StreamLogger.log("🎙️ Intercom: remote audio track received from browser")
            val audioTrackRemote = track as? AudioTrack ?: return
            audioTrackRemote.setEnabled(true)
            remoteAudioTrack = audioTrackRemote
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode             = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
            mainHandler.post { onIntercomActive?.invoke(true) }
        }
    }

    // ── SDP observer base ─────────────────────────────────────────────────────

    private open class SimpleSdpObserver(private val opTag: String) : SdpObserver {
        private val sdpLog = com.akdevelopers.streamnode.analytics.StreamNodeLogger.forModule("SDP")
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) { sdpLog.e("createFailure", "op" to opTag, "err" to err) }
        override fun onSetFailure(err: String?)    { sdpLog.e("setFailure",    "op" to opTag, "err" to err) }
    }
}
