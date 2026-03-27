package com.akdevelopers.streamnode.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.akdevelopers.streamnode.analytics.StreamNodeLogger
import com.akdevelopers.streamnode.core.AppConstants
import com.akdevelopers.streamnode.service.StreamIdentity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SignalingClient — WebSocket connection to /signal?id=<uuid>&role=phone.
 *
 * Responsibilities:
 *  - Connect to the signaling endpoint derived from the stream server URL.
 *  - Deliver inbound JSON messages (answer SDP, ICE candidates, peer events) to
 *    [onMessage] so [WebRTCEngine] can act on them.
 *  - Send outbound signaling messages (offer SDP, ICE candidates) from [WebRTCEngine].
 *  - Exponential-backoff reconnect: 1 s → 2 s → 4 s → … → 30 s cap.
 *
 * URL derivation:
 *   serverUrl = "wss://host/stream" → "wss://host/signal?id=<uuid>&role=phone"
 */
class SignalingClient(
    private val onMessage:      (JSONObject) -> Unit,
    private val onConnected:    () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {
    private val log         = StreamNodeLogger.forModule("Signaling")
    private val mainHandler = Handler(Looper.getMainLooper())

    private var webSocket:       WebSocket? = null
    private val shouldConnect  = AtomicBoolean(false)
    private var reconnectAttempt = 0
    /** Remembered so reconnectNow() can reopen the socket without re-deriving the URL. */
    private var connectedUrl: String = ""

    @Volatile private var generation = 0
    @Volatile var isOpen: Boolean = false
        private set

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0,  TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()

        fun signalingUrl(streamUrl: String, context: Context): String {
            // Strip any known path suffix (/stream, /control, /signal, /listen)
            // with or without query string, so we always derive from the bare host+port.
            // Without stripping /signal here a change_url delivering a /signal URL
            // would produce wss://host/signal/signal?... and fail with 101→404.
            val base = streamUrl
                .replaceFirst(Regex("/(stream|control|signal|listen)(\\?.*)?$"), "")
                .trimEnd('/')
            val id = StreamIdentity.getStreamId(context)
            return "$base${AppConstants.WEBRTC_SIGNALING_PATH}?id=$id&role=phone"
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun connect(streamUrl: String, context: Context) {
        val url = signalingUrl(streamUrl, context)
        connectedUrl = url
        log.i("connect → signaling URL", "url" to url, "streamUrl" to streamUrl)
        shouldConnect.set(true)
        reconnectAttempt = 0
        openSocket(url)
    }

    fun send(msg: JSONObject) {
        val ws = webSocket ?: return
        if (!isOpen) return
        try { ws.send(msg.toString()) }
        catch (e: Exception) { log.w("send failed", "error" to e.message) }
    }

    fun send(raw: String) {
        val ws = webSocket ?: return
        if (!isOpen) return
        try { ws.send(raw) }
        catch (e: Exception) { log.w("send(raw) failed", "error" to e.message) }
    }

    fun disconnect() {
        log.i("disconnect")
        generation++
        shouldConnect.set(false)
        isOpen = false
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        onDisconnected()
    }

    /**
     * Gap 5 fix: immediately reconnect after a network change.
     * Resets the reconnect counter to 0 so Phase-1 backoff fires on the next
     * attempt — no waiting for an existing 5-min backoff timer to expire.
     * No-op if connect() was never called (connectedUrl is blank).
     */
    fun reconnectNow() {
        if (!shouldConnect.get() || connectedUrl.isBlank()) return
        log.i("reconnectNow — resetting backoff + reopening socket")
        generation++
        mainHandler.removeCallbacksAndMessages(null)
        reconnectAttempt = 0
        isOpen = false
        webSocket?.close(1000, "Reconnecting")
        webSocket = null
        openSocket(connectedUrl)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun openSocket(url: String) {
        if (!shouldConnect.get()) return
        val request = Request.Builder()
            .url(url)
            .header("ngrok-skip-browser-warning", "true")
            .header("User-Agent", "StreamNode/1.0-signal")
            .build()
        log.d("openSocket", "url" to url, "gen" to generation)
        webSocket = client.newWebSocket(request, Listener(generation, url))
    }

    /**
     * Two-phase backoff (mirrors AudioControlClient):
     * Phase 1 (≤ EXTEND_AFTER attempts): cap = 30 s  (fast recovery)
     * Phase 2 (> EXTEND_AFTER):          cap = 5 min (long-term outage)
     * NetworkChangeReceiver resets reconnectAttempt via the outer reconnectNow() call.
     */
    private fun scheduleReconnect(url: String) {
        if (!shouldConnect.get()) return
        reconnectAttempt++
        val cap = if (reconnectAttempt <= AppConstants.RECONNECT_BACKOFF_EXTEND_AFTER)
            AppConstants.RECONNECT_BACKOFF_MAX_NORMAL_MS
        else
            AppConstants.RECONNECT_BACKOFF_MAX_EXTENDED_MS
        val delayMs = minOf(1_000L shl (reconnectAttempt - 1), cap)
        log.d("scheduleReconnect", "attempt" to reconnectAttempt, "delayMs" to delayMs,
              "phase" to if (reconnectAttempt <= AppConstants.RECONNECT_BACKOFF_EXTEND_AFTER) 1 else 2)
        mainHandler.postDelayed({ openSocket(url) }, delayMs)
    }

    // ── WebSocketListener ─────────────────────────────────────────────────────

    private inner class Listener(
        private val gen: Int,
        private val url: String
    ) : WebSocketListener() {

        private fun isCurrent() = gen == generation

        override fun onOpen(ws: WebSocket, response: Response) {
            if (!isCurrent()) { ws.close(1000, "Stale"); return }
            log.i("onOpen ✓", "gen" to gen)
            isOpen = true
            reconnectAttempt = 0
            onConnected()
        }

        override fun onMessage(ws: WebSocket, text: String) {
            if (!isCurrent()) return
            try {
                val json = JSONObject(text)
                log.d("← message", "type" to json.optString("type"))
                onMessage(json)
            } catch (e: Exception) {
                log.w("bad JSON from server", "raw" to text.take(120), "error" to e.message)
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent()) return
            log.e("onFailure",
                "error"      to t.message,
                "cause"      to t.cause?.message,
                "httpStatus" to response?.code,
                "attempt"    to reconnectAttempt
            )
            isOpen = false
            onDisconnected()
            scheduleReconnect(url)
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) { ws.close(1000, null); return }
            log.i("onClosing", "code" to code, "reason" to reason)
            isOpen = false
            ws.close(1000, null)
            if (shouldConnect.get()) scheduleReconnect(url)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) return
            if (isOpen) {
                log.i("onClosed (was open)", "code" to code)
                isOpen = false
                onDisconnected()
                if (shouldConnect.get()) scheduleReconnect(url)
            }
        }
    }
}
