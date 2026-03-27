package com.akdevelopers.streamnode.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.akdevelopers.streamnode.analytics.Analytics
import com.akdevelopers.streamnode.analytics.CrashManager
import com.akdevelopers.streamnode.core.AppConstants
import com.akdevelopers.streamnode.service.AudioTransport
import com.akdevelopers.streamnode.service.StreamIdentity
import com.akdevelopers.streamnode.service.StreamStatus

/**
 * AudioWebSocketClient — always-connected binary audio WebSocket.
 *
 * Implements [AudioTransport] so [ConnectionOrchestrator] (and tests) can
 * program against the interface rather than this concrete class.
 *
 * Connection lifecycle is independent of streaming state:
 *  - connect()             → opens WebSocket, auto-reconnects forever
 *  - disconnect()          → cleanly closes, stops reconnect loop
 *  - sendFrame()           → sends an encoded audio frame (no-op if not open)
 *  - sendStreamingState()  → notifies server whether the mic is active
 *
 * Commands (start/stop/change_url, etc.) are handled by [AudioControlClient]
 * over the dedicated /control WebSocket — not here.
 */
class AudioWebSocketClient : AudioTransport {

    private val TAG = "AC_WebSocket"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private val shouldReconnect = AtomicBoolean(false)
    private var reconnectAttempt = 0
    private var resolvedUrl: String = ""
    private var serverUrl: String   = ""

    // ── AudioTransport ─────────────────────────────────────────────────────────
    override var onStatusChange: ((StreamStatus) -> Unit)? = null
    override var sampleRate: Int = 48000
    override var frameMs: Int    = 60

    /**
     * True only while the OkHttp WebSocket is in the OPEN state (after onOpen,
     * before onFailure / onClosing / disconnect).
     */
    @Volatile override var isOpen: Boolean = false
        private set

    // ── Phase 3: Standby mode ─────────────────────────────────────────────────
    /**
     * When true the socket is connected but sendFrame() is a no-op.
     * Used by ConnectionOrchestrator's fallback state machine: the WebSocket
     * client pre-connects in standby while WebRTC is the active transport,
     * so it can begin sending audio within <50 ms when ICE fails — with zero
     * audible gap.
     *
     * Toggle via activateAudio() / suspendAudio().
     */
    @Volatile var audioSuspended: Boolean = true
        private set

    /** Begin sending audio frames. Called when this client becomes the active transport. */
    fun activateAudio() {
        audioSuspended = false
        Log.i(TAG, "activateAudio: standby → active")
        sendStreamingState(true)
    }

    /** Suspend audio (standby mode). Called when WebRTC takes over as primary transport. */
    fun suspendAudio() {
        audioSuspended = true
        Log.i(TAG, "suspendAudio: active → standby")
        sendStreamingState(false)
    }

    // ── Generation counter — prevents stale-socket reconnect avalanche ─────────
    @Volatile private var generation = 0

    // ── App-level ping-pong ────────────────────────────────────────────────────
    private val PING_INTERVAL_MS = 20_000L
    // BUG FIX #5: old value was 10_000 ms. The server's wsHeartbeat sends a WS-level
    // ping every WS_PING_INTERVAL_MS = 30 s. An app-level pong timeout of 10 s fires
    // a forced reconnect on every single server heartbeat cycle, because the server
    // ping resets the socket's idle timer — but NOT the app-level pong deadline.
    // In standby mode this manifested as the standby WS reconnecting continuously,
    // spamming the server and burning battery.
    // Fix: set the timeout to 35 s (matching AudioControlClient's proven value) —
    // comfortably above the 30 s server heartbeat so transient Doze/sleep delays
    // don't trigger a false-positive reconnect.
    private val PONG_TIMEOUT_MS  = 35_000L
    private var pongDeadline: Runnable? = null

    private fun startPingLoop() {
        stopPingLoop()
        mainHandler.postDelayed(pingRunnable, PING_INTERVAL_MS)
    }

    private fun stopPingLoop() {
        mainHandler.removeCallbacks(pingRunnable)
        disarmPongDeadline()
    }

    private fun disarmPongDeadline() {
        pongDeadline?.let { mainHandler.removeCallbacks(it) }
        pongDeadline = null
    }

    private val pingRunnable: Runnable = object : Runnable {
        override fun run() {
            val ws = webSocket
            if (!isOpen || ws == null) return
            try {
                ws.send("""{"type":"ping"}""")
                Log.v(TAG, "app-ping →")
            } catch (e: Exception) {
                Log.w(TAG, "ping send failed: ${e.message}")
                return
            }
            disarmPongDeadline()
            val deadline = Runnable {
                if (isOpen) {
                    Log.w(TAG, "pong timeout — connection silent, forcing reconnect")
                    reconnectNow()
                }
            }
            pongDeadline = deadline
            mainHandler.postDelayed(deadline, PONG_TIMEOUT_MS)
            mainHandler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    companion object {
        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    // ── AudioTransport: connect ────────────────────────────────────────────────

    override fun connect(url: String, context: Context) {
        Log.i(TAG, "connect: url=$url")
        serverUrl   = url
        resolvedUrl = StreamIdentity.appendToUrl(context, url)
        shouldReconnect.set(true)
        reconnectAttempt = 0
        openSocket()
    }

    private fun openSocket() {
        if (!shouldReconnect.get()) { Log.w(TAG, "openSocket: shouldReconnect=false — abort"); return }
        val status = if (reconnectAttempt == 0) StreamStatus.CONNECTING else StreamStatus.RECONNECTING
        onStatusChange?.invoke(status)
        val request = Request.Builder()
            .url(resolvedUrl)
            .header("ngrok-skip-browser-warning", "true")
            .header("User-Agent", "StreamNode/1.0")
            .build()
        webSocket = sharedClient.newWebSocket(request, Listener(generation))
    }

    // ── AudioTransport: sendFrame / sendStreamingState ─────────────────────────

    override fun sendFrame(data: ByteArray) {
        // Phase 3: drop frames when in standby — WebRTC is the active transport.
        if (audioSuspended) return
        val ws = webSocket ?: return
        if (ws.queueSize() > 200_000L) return
        ws.send(data.toByteString())
    }

    override fun sendStreamingState(active: Boolean) {
        try {
            webSocket?.send("""{"type":"streamingState","active":$active}""")
            Log.d(TAG, "sendStreamingState: active=$active")
        } catch (e: Exception) {
            Log.w(TAG, "sendStreamingState failed: ${e.message}")
        }
    }

    /**
     * Feature 2 — re-broadcast codec parameters to the server after a live sample-rate change.
     * The server stores the codec announce and replays it to late-joining browsers so they can
     * reconfigure their AudioDecoder with the updated sampleRate.
     * Safe to call from any thread (no-op if socket not open).
     */
    fun sendCodecAnnounce() {
        if (!isOpen) return
        try {
            webSocket?.send("""{"type":"codec","codec":"opus","sampleRate":$sampleRate,"channels":1,"frameMs":$frameMs}""")
            Log.i(TAG, "sendCodecAnnounce: sampleRate=$sampleRate frameMs=$frameMs")
        } catch (e: Exception) {
            Log.w(TAG, "sendCodecAnnounce failed: ${e.message}")
        }
    }

    // ── AudioTransport: disconnect / reconnectNow ──────────────────────────────

    override fun disconnect() {
        Log.i(TAG, "disconnect")
        generation++
        shouldReconnect.set(false)
        reconnectAttempt = 0
        isOpen = false
        audioSuspended = true  // Phase 3: reset to standby on full disconnect
        stopPingLoop()
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        onStatusChange?.invoke(StreamStatus.IDLE)
    }

    override fun reconnectNow() {
        Log.i(TAG, "reconnectNow")
        generation++
        stopPingLoop()
        mainHandler.removeCallbacksAndMessages(null)
        reconnectAttempt = 0
        isOpen = false
        webSocket?.close(1000, "Reconnecting")
        webSocket = null
        openSocket()
    }

    /**
     * Two-phase exponential back-off (mirrors AudioControlClient):
     *
     * Phase 1 (attempt ≤ RECONNECT_BACKOFF_EXTEND_AFTER):
     *   cap = RECONNECT_BACKOFF_MAX_NORMAL_MS (30 s) — fast recovery for brief outages.
     *
     * Phase 2 (attempt > RECONNECT_BACKOFF_EXTEND_AFTER):
     *   cap = RECONNECT_BACKOFF_MAX_EXTENDED_MS (5 min) — conserves battery when the
     *   server is offline for hours / days / years.
     *
     * NetworkChangeReceiver resets reconnectAttempt = 0 via reconnectNow(), so Phase-1
     * fires immediately on real connectivity restore — no manual intervention needed.
     */
    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) return
        reconnectAttempt++
        val cap = if (reconnectAttempt <= AppConstants.RECONNECT_BACKOFF_EXTEND_AFTER)
            AppConstants.RECONNECT_BACKOFF_MAX_NORMAL_MS
        else
            AppConstants.RECONNECT_BACKOFF_MAX_EXTENDED_MS
        val delayMs = minOf(1_000L shl (reconnectAttempt - 1), cap)
        Log.d(TAG, "scheduleReconnect: attempt=$reconnectAttempt delay=${delayMs}ms " +
              "phase=${if (reconnectAttempt <= AppConstants.RECONNECT_BACKOFF_EXTEND_AFTER) 1 else 2}")
        Analytics.logWsReconnectScheduled(attempt = reconnectAttempt, delayMs = delayMs)
        mainHandler.postDelayed({ openSocket() }, delayMs)
    }

    // ── WebSocketListener ──────────────────────────────────────────────────────

    private inner class Listener(private val gen: Int) : WebSocketListener() {

        private fun isCurrent(): Boolean = gen == generation

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent()) {
                Log.d(TAG, "onOpen — stale socket (gen=$gen, current=$generation), closing")
                webSocket.close(1000, "Stale")
                return
            }
            Log.i(TAG, "onOpen: CONNECTED ✓")
            isOpen = true
            reconnectAttempt = 0
            startPingLoop()
            webSocket.send("""{"type":"codec","codec":"opus","sampleRate":$sampleRate,"channels":1,"frameMs":$frameMs}""")
            Analytics.logWsConnected(attempt = 0, sampleRate = sampleRate)
            Analytics.logStreamConnected(latencyMs = 0L, quality = "HIGH_QUALITY")
            onStatusChange?.invoke(StreamStatus.CONNECTED_IDLE)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent()) return
            when {
                text == """{"type":"pong"}""" -> {
                    Log.v(TAG, "app-pong ← ✓")
                    disarmPongDeadline()
                }
                text == """{"type":"ping"}""" -> webSocket.send("""{"type":"pong"}""")
                else -> Log.v(TAG, "server msg ignored: ${text.take(80)}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent()) { Log.d(TAG, "onFailure — stale socket (gen=$gen), ignoring"); return }
            Log.e(TAG, "onFailure: ${t.javaClass.simpleName}: ${t.message}")
            isOpen = false
            stopPingLoop()
            CrashManager.recordNonFatal(t, "WebSocket failure — attempt=$reconnectAttempt")
            Analytics.logWsFailure(attempt = reconnectAttempt, errorType = t.javaClass.simpleName)
            onStatusChange?.invoke(StreamStatus.RECONNECTING)
            scheduleReconnect()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) {
                Log.d(TAG, "onClosing — stale socket (gen=$gen) code=$code reason=$reason, skipping reconnect")
                webSocket.close(1000, null)
                return
            }
            Log.w(TAG, "onClosing: code=$code reason='$reason'")
            isOpen = false
            stopPingLoop()
            webSocket.close(1000, null)
            if (shouldReconnect.get()) {
                onStatusChange?.invoke(StreamStatus.RECONNECTING)
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) return
            Log.w(TAG, "onClosed: code=$code reason='$reason'")
            if (isOpen) {
                isOpen = false
                stopPingLoop()
                if (shouldReconnect.get()) {
                    onStatusChange?.invoke(StreamStatus.RECONNECTING)
                    scheduleReconnect()
                }
            }
        }
    }
}
