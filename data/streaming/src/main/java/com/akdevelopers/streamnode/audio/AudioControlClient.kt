package com.akdevelopers.streamnode.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.akdevelopers.streamnode.analytics.Analytics
import com.akdevelopers.streamnode.analytics.StreamNodeLogger
import com.akdevelopers.streamnode.core.AppConstants
import com.akdevelopers.streamnode.service.StreamIdentity
import com.akdevelopers.streamnode.service.StreamStatus
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioControlClient — always-on command channel (/control WebSocket).
 *
 * JSON only — never sends binary audio frames.
 * Exponential backoff reconnect: 1 s → 2 s → 4 s → 8 s → 16 s → 30 s cap.
 *
 * Callbacks:
 *  onCmd         — (commandId, action, url) for every incoming cmd message.
 *  onStatusChange — StreamStatus transitions driven by connection events.
 *  onInput        — Remote input injection events (bypass dedup; each gesture unique).
 */
class AudioControlClient {

    companion object {
        private const val PING_INTERVAL_MS = 20_000L
        // Bug #14 fix: old value was 10 s but the server's wsHeartbeat pings every
        // 30 s. A single missed app-level pong (e.g. during Android Doze) fired the
        // reconnect while the server's own heartbeat would have caught a dead socket
        // within 60 s. Set the timeout to 35 s — comfortably above the server's 30 s
        // ping cycle — so transient Doze delays don't cause unnecessary reconnects.
        private const val PONG_TIMEOUT_MS  = 35_000L

        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0,  TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val log = StreamNodeLogger.forModule("Control")

    var onCmd:         ((commandId: String, action: String, url: String) -> Unit)? = null
    var onStatusChange: ((StreamStatus) -> Unit)? = null
    var onInput: ((kind: String, x: Float, y: Float,
                   x1: Float, y1: Float, x2: Float, y2: Float,
                   durationMs: Long, keycode: Int) -> Unit)? = null
    /**
     * Phase 6 Step 29: Invoked on the main thread with the measured RTT (ms) each
     * time a pong is received. ConnectionOrchestrator forwards this to MicOrchestrator
     * to drive the adaptive bitrate ladder.
     */
    var onRttMeasured: ((Long) -> Unit)? = null

    @Volatile var isOpen: Boolean = false
        private set

    private val mainHandler     = Handler(Looper.getMainLooper())
    private var webSocket:      WebSocket? = null
    private val shouldReconnect = AtomicBoolean(false)
    private var resolvedUrl     = ""
    private var pongDeadline:   Runnable? = null
    @Volatile private var generation      = 0
    private var reconnectAttempt          = 0
    /** Phase 6 Step 29: epoch ms when the last app-level ping was sent. */
    @Volatile private var pingTs          = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    fun connect(serverUrl: String, context: Context) {
        // Derive the /control URL from any known path suffix.
        // Must strip /signal (and /listen) as well as /stream so that a change_url
        // delivering a /signal URL doesn't produce wss://host/signal/control.
        val controlUrl = when {
            serverUrl.contains(Regex("/(stream|signal|listen)(\\?|$)")) ->
                serverUrl.replace(Regex("/(stream|signal|listen)(\\?.*)?$"), "/control")
            serverUrl.contains(Regex("/control(\\?|$)")) -> serverUrl
            else -> serverUrl.trimEnd('/') + "/control"
        }
        resolvedUrl = StreamIdentity.appendToUrl(context, controlUrl)
        shouldReconnect.set(true)
        reconnectAttempt = 0
        log.i("connect", "url" to resolvedUrl)
        openSocket()
    }

    fun disconnect() {
        log.i("disconnect")
        generation++
        shouldReconnect.set(false)
        isOpen = false
        stopPingLoop()
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        onStatusChange?.invoke(StreamStatus.IDLE)
    }

    fun reconnectNow() {
        log.i("reconnectNow")
        generation++
        stopPingLoop()
        mainHandler.removeCallbacksAndMessages(null)
        reconnectAttempt = 0
        isOpen = false
        webSocket?.close(1000, "Reconnecting")
        webSocket = null
        openSocket()
    }

    fun reconnectTo(serverUrl: String, context: Context) {
        val controlUrl = when {
            serverUrl.contains(Regex("/(stream|signal|listen)(\\?|$)")) ->
                serverUrl.replace(Regex("/(stream|signal|listen)(\\?.*)?$"), "/control")
            serverUrl.contains(Regex("/control(\\?|$)")) -> serverUrl
            else -> serverUrl.trimEnd('/') + "/control"
        }
        resolvedUrl = StreamIdentity.appendToUrl(context, controlUrl)
        log.i("reconnectTo", "url" to resolvedUrl)
        reconnectNow()
    }

    fun sendStatusUpdate(status: StreamStatus) {
        if (!isOpen) return
        runCatching { webSocket?.send("""{"type":"statusUpdate","status":"${status.name}"}""") }
    }

    fun send(json: String) {
        if (!isOpen) return
        runCatching { webSocket?.send(json) }
    }

    fun sendBinary(data: ByteArray) {
        if (!isOpen) return
        runCatching { webSocket?.send(data.toByteString()) }
            .onFailure { log.w("sendBinary failed", "error" to it.message, "bytes" to data.size) }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun openSocket() {
        if (!shouldReconnect.get()) return
        onStatusChange?.invoke(StreamStatus.CONNECTING)
        val request = Request.Builder()
            .url(resolvedUrl)
            .header("ngrok-skip-browser-warning", "true")
            .header("User-Agent", "StreamNode/1.0-control")
            .build()
        log.d("openSocket", "url" to resolvedUrl, "gen" to generation)
        webSocket = sharedClient.newWebSocket(request, Listener(generation))
    }

    /**
     * Two-phase exponential back-off:
     *
     * Phase 1 (attempt ≤ RECONNECT_BACKOFF_EXTEND_AFTER):
     *   1 s → 2 → 4 → 8 → 16 → 30 s  (fast recovery for brief outages)
     *
     * Phase 2 (attempt > RECONNECT_BACKOFF_EXTEND_AFTER):
     *   cap raised to 5 min (conserves battery when server is offline long-term)
     *
     * NetworkChangeReceiver resets reconnectAttempt = 0 via reconnectNow(), so
     * Phase 1 fires immediately when real connectivity is restored — guaranteeing
     * the fastest possible reconnect without any manual app interaction.
     */
    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) return
        reconnectAttempt++
        val cap = if (reconnectAttempt <= AppConstants.RECONNECT_BACKOFF_EXTEND_AFTER)
            AppConstants.RECONNECT_BACKOFF_MAX_NORMAL_MS
        else
            AppConstants.RECONNECT_BACKOFF_MAX_EXTENDED_MS
        val delayMs = minOf(1_000L shl (reconnectAttempt - 1), cap)
        log.d("scheduleReconnect", "attempt" to reconnectAttempt, "delayMs" to delayMs,
              "phase" to if (reconnectAttempt <= AppConstants.RECONNECT_BACKOFF_EXTEND_AFTER) 1 else 2)
        Analytics.logWsReconnectScheduled(attempt = reconnectAttempt, delayMs = delayMs)
        onStatusChange?.invoke(StreamStatus.RECONNECTING)
        mainHandler.postDelayed({ openSocket() }, delayMs)
    }

    // ── Ping / Pong ───────────────────────────────────────────────────────────

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
            if (!isOpen) return
            pingTs = System.currentTimeMillis()   // Phase 6: timestamp for RTT measurement
            runCatching { webSocket?.send("""{"type":"ping"}""") }
            log.v("ping →")
            disarmPongDeadline()
            val deadline = Runnable {
                if (isOpen) {
                    log.w("Pong timeout — forcing reconnect")
                    reconnectNow()
                }
            }
            pongDeadline = deadline
            mainHandler.postDelayed(deadline, PONG_TIMEOUT_MS)
            mainHandler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    // ── WebSocketListener ─────────────────────────────────────────────────────

    private inner class Listener(private val gen: Int) : WebSocketListener() {

        private fun isCurrent(): Boolean = gen == generation

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent()) { webSocket.close(1000, "Stale"); return }
            log.i("onOpen ✓", "gen" to gen, "url" to resolvedUrl)
            isOpen = true
            reconnectAttempt = 0
            startPingLoop()
            onStatusChange?.invoke(StreamStatus.CONNECTED_IDLE)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent()) return
            when {
                text.contains(""""type":"pong"""") -> {
                    log.v("← pong")
                    disarmPongDeadline()
                    // Phase 6 Step 29: measure RTT from ping send-time to pong arrival
                    val ts = pingTs
                    if (ts > 0L) {
                        val rtt = System.currentTimeMillis() - ts
                        if (rtt in 1L..10_000L) {   // sanity-clamp: 1 ms – 10 s
                            onRttMeasured?.invoke(rtt)
                        }
                    }
                }
                text.contains(""""type":"ping"""") ->
                    runCatching { webSocket.send("""{"type":"pong"}""") }

                text.contains(""""type":"input"""") -> {
                    runCatching {
                        val p    = JSONObject(text)
                        val kind = p.optString("kind", "")
                        val x    = p.optDouble("x",  0.0).toFloat()
                        val y    = p.optDouble("y",  0.0).toFloat()
                        val x1   = p.optDouble("x1", 0.0).toFloat()
                        val y1   = p.optDouble("y1", 0.0).toFloat()
                        val x2   = p.optDouble("x2", 0.0).toFloat()
                        val y2   = p.optDouble("y2", 0.0).toFloat()
                        val dur  = p.optLong("durationMs", 150L)
                        val kc   = p.optInt("keycode", 0)
                        log.d("← input", "kind" to kind, "x" to x, "y" to y)
                        onInput?.invoke(kind, x, y, x1, y1, x2, y2, dur, kc)
                    }.onFailure { log.w("Failed to parse input", "error" to it.message) }
                }

                text.contains(""""type":"cmd"""") -> {
                    runCatching {
                        val p         = JSONObject(text)
                        val commandId = p.optString("commandId", UUID.randomUUID().toString())
                        val action    = p.optString("action", "")
                        val url       = p.optString("url", "")
                        if (action.isNotBlank()) {
                            log.i("← CMD", "action" to action, "id" to commandId.take(8))
                            onCmd?.invoke(commandId, action, url)
                        }
                    }.onFailure { log.w("Failed to parse cmd", "error" to it.message) }
                }

                text.contains(""""type":"welcome"""") ->
                    log.i("Control channel ready ✓")

                else ->
                    log.v("← msg", "preview" to text.take(80))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent()) {
                log.d("onFailure — stale gen ignored", "gen" to gen)
                return
            }
            log.e("onFailure",
                "error"      to t.message,
                "httpStatus" to response?.code,
                "attempt"    to reconnectAttempt
            )
            isOpen = false
            stopPingLoop()
            Analytics.logWsFailure(attempt = reconnectAttempt, errorType = t.javaClass.simpleName)
            scheduleReconnect()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) { webSocket.close(1000, null); return }
            log.w("onClosing", "code" to code, "reason" to reason)
            isOpen = false
            stopPingLoop()
            webSocket.close(1000, null)
            if (shouldReconnect.get()) scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) return
            if (isOpen) {
                log.i("onClosed (was open)", "code" to code)
                isOpen = false
                stopPingLoop()
                if (shouldReconnect.get()) scheduleReconnect()
            }
        }
    }
}
