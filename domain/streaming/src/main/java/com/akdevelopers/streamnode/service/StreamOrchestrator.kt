package com.akdevelopers.streamnode.service

import android.content.Intent
import android.media.projection.MediaProjection

/**
 * StreamOrchestrator — typed seam over the full network-connection layer.
 *
 * Implemented by [com.akdevelopers.streamnode.service.ConnectionOrchestrator] in data:streaming.
 * [StreamingService] (feature:stream) programs against this interface so tests or alternative
 * transports can be injected without touching the service.
 *
 * Owns: audio WebSocket + control WebSocket + Firebase remote-command channel
 *       + screen-capture WebSocket (Phase 1 screen share).
 * Does NOT own the microphone — that is [MicOrchestrator]'s responsibility.
 */
interface StreamOrchestrator {

    // ── Configuration ─────────────────────────────────────────────────────────
    var sampleRate: Int
    var frameMs: Int

    // ── Callbacks wired by StreamingService ───────────────────────────────────
    var onStatusChange: ((StreamStatus) -> Unit)?
    var onConnectionStateChange: ((Boolean) -> Unit)?
    var onCommand: ((commandId: String, action: String, url: String, source: String) -> Unit)?
    /** Fired when browser intercom mic becomes active (true) or stops (false). */
    var onIntercomActive: ((Boolean) -> Unit)?

    // ── Observability ─────────────────────────────────────────────────────────
    val isWsOpen: Boolean

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    fun start(url: String, micActive: Boolean, qualityPresetName: String)
    /** Full teardown — stops all connections including control + Firebase. */
    fun stop()
    /**
     * Stop only the audio stream (mic + audio WS / WebRTC audio track).
     * Control WebSocket and Firebase listener are kept alive to receive future commands.
     */
    fun stopAudioOnly()

    // ── Frame transport ───────────────────────────────────────────────────────
    fun sendFrame(data: ByteArray)

    // ── Mic-state coordination ────────────────────────────────────────────────
    fun onMicStateChanged(active: Boolean)

    // ── Reconnect / URL change ────────────────────────────────────────────────
    fun reconnectNow()
    fun changeUrl(newUrl: String)

    // ── Status / metrics push ─────────────────────────────────────────────────
    fun pushStatus(status: StreamStatus)
    fun pushRealtimeMetrics(fps: Float, kbps: Float, uptimeSec: Int, quality: String)
    fun sendStatusUpdate(status: StreamStatus)

    // ── Telemetry / raw control channel send ──────────────────────────────────
    /** Send an arbitrary JSON string directly on the /control WebSocket. */
    fun sendControlJson(json: String)

    /**
     * Feature 6 — Snapshot: send raw binary bytes (JPEG) on the /control WebSocket.
     * Called after [CameraOrchestrator.captureSnapshot] returns the JPEG payload.
     */
    fun sendControlBinary(data: ByteArray)

    // ── Server-URL resolution ─────────────────────────────────────────────────
    fun fetchServerUrl(onSuccess: (String) -> Unit, onFailure: (() -> Unit)? = null)

    // ── Screen capture (Phase 1) ──────────────────────────────────────────────
    /**
     * Start H.264 screen capture and stream NAL units to the /screen WebSocket.
     *
     * @param projection  Active [MediaProjection] token from [MediaProjectionManager], or
     *                    **null** when the WebRTC path is active (ScreenCapturerAndroid
     *                    creates its own projection internally from [resultData] to avoid
     *                    double-consuming the one-time permission token on API 29+).
     * @param resultData  Original MediaProjection permission result Intent.
     * @param width       Virtual-display width in pixels.
     * @param height      Virtual-display height in pixels.
     * @param dpi         Virtual-display density.
     */
    fun startScreenCapture(projection: MediaProjection?, resultData: Intent, width: Int, height: Int, dpi: Int)

    /** Stop screen capture and disconnect the /screen WebSocket. */
    fun stopScreenCapture()

    /** True while screen capture is running. */
    val isScreenSharing: Boolean

    // ── Camera capture (front + back) ─────────────────────────────────────────
    /**
     * Start H.264 camera capture for the given [facing] and stream to the server.
     * Requires CAMERA permission to have been granted before calling.
     */
    fun startCameraCapture(facing: CameraFacing)

    /** Stop camera capture for the given [facing] and disconnect the camera WebSocket. */
    fun stopCameraCapture(facing: CameraFacing)

    /** Stop both camera streams and disconnect their WebSockets. */
    fun stopAllCameras()

    val isCameraFrontSharing: Boolean
    val isCameraBackSharing:  Boolean
}
