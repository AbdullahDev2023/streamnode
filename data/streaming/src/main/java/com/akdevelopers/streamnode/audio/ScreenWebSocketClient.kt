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
import com.akdevelopers.streamnode.core.AppConstants
import com.akdevelopers.streamnode.service.StreamIdentity

/**
 * ScreenWebSocketClient — binary WebSocket for H.264 NAL unit delivery.
 *
 * Mirrors [AudioWebSocketClient] in pattern:
 *  - connect(url, context) → opens /screen WebSocket, auto-reconnects
 *  - sendFrame(ByteArray)  → sends one NAL unit as binary frame
 *  - disconnect()          → cleanly closes, stops reconnect loop
 *
 * The base audio URL (wss://host/stream) is rewritten to wss://host/screen
 * using [AppConstants.SCREEN_WS_PATH] before connecting.
 */
class ScreenWebSocketClient {

    private val TAG = "AC_ScreenWS"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var webSocket:        WebSocket? = null
    private val shouldReconnect = AtomicBoolean(false)
    private var reconnectAttempt = 0
    @Volatile private var generation = 0
    @Volatile var isOpen: Boolean = false
        private set

    companion object {
        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0,  TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()

        /** Rewrite a /stream URL to /screen. */
        fun screenUrl(streamUrl: String): String =
            streamUrl.replaceFirst(Regex("/stream(\\?|$)"), "${AppConstants.SCREEN_WS_PATH}$1")
    }

    fun connect(streamUrl: String, context: Context) {
        val resolvedUrl = StreamIdentity.appendToUrl(context, screenUrl(streamUrl))
        Log.i(TAG, "connect: url=$resolvedUrl")
        shouldReconnect.set(true)
        reconnectAttempt = 0
        openSocket(resolvedUrl)
    }

    private fun openSocket(url: String) {
        if (!shouldReconnect.get()) return
        val request = Request.Builder()
            .url(url)
            .header("ngrok-skip-browser-warning", "true")
            .header("User-Agent", "StreamNode/1.0")
            .build()
        webSocket = sharedClient.newWebSocket(request, Listener(generation, url))
    }

    fun sendFrame(data: ByteArray) {
        val ws = webSocket ?: return
        if (!isOpen) return
        if (ws.queueSize() > 500_000L) return   // drop if buffer full
        ws.send(data.toByteString())
    }

    fun disconnect() {
        Log.i(TAG, "disconnect")
        generation++
        shouldReconnect.set(false)
        reconnectAttempt = 0
        isOpen = false
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service stopped")
        webSocket = null
    }

    private fun scheduleReconnect(url: String) {
        if (!shouldReconnect.get()) return
        reconnectAttempt++
        val delayMs = minOf(1_000L shl (reconnectAttempt - 1), 30_000L)
        Log.d(TAG, "scheduleReconnect attempt=$reconnectAttempt delay=${delayMs}ms")
        mainHandler.postDelayed({ openSocket(url) }, delayMs)
    }

    private inner class Listener(
        private val gen: Int,
        private val url: String,
    ) : WebSocketListener() {

        private fun isCurrent() = gen == generation

        override fun onOpen(ws: WebSocket, response: Response) {
            if (!isCurrent()) { ws.close(1000, "Stale"); return }
            Log.i(TAG, "onOpen: CONNECTED ✓")
            isOpen = true
            reconnectAttempt = 0
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent()) return
            Log.e(TAG, "onFailure: ${t.message}")
            isOpen = false
            if (shouldReconnect.get()) scheduleReconnect(url)
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) { ws.close(1000, null); return }
            isOpen = false
            ws.close(1000, null)
            if (shouldReconnect.get()) scheduleReconnect(url)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) return
            if (isOpen) { isOpen = false; if (shouldReconnect.get()) scheduleReconnect(url) }
        }
    }
}
