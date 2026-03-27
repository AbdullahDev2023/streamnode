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
import com.akdevelopers.streamnode.service.CameraFacing
import com.akdevelopers.streamnode.service.StreamIdentity

/**
 * CameraWebSocketClient — binary WebSocket for camera H.264 NAL unit delivery.
 *
 * Mirrors [ScreenWebSocketClient] exactly.
 * URL rewrite: /stream → /camera-front  or  /camera-back  based on [CameraFacing].
 */
class CameraWebSocketClient(private val facing: CameraFacing) {

    private val TAG = "AC_CameraWS[${facing.name}]"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var webSocket:       WebSocket? = null
    private val shouldReconnect = AtomicBoolean(false)
    private var reconnectAttempt = 0
    @Volatile private var generation = 0
    @Volatile var isOpen: Boolean = false
        private set

    companion object {
        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()

        fun cameraUrl(streamUrl: String, facing: CameraFacing): String {
            val path = when (facing) {
                CameraFacing.FRONT -> AppConstants.CAMERA_FRONT_WS_PATH
                CameraFacing.BACK  -> AppConstants.CAMERA_BACK_WS_PATH
            }
            return streamUrl.replaceFirst(Regex("/stream(\\?|$)"), "$path$1")
        }
    }

    fun connect(streamUrl: String, context: Context) {
        val resolved = StreamIdentity.appendToUrl(context, cameraUrl(streamUrl, facing))
        Log.i(TAG, "connect: url=$resolved")
        shouldReconnect.set(true)
        reconnectAttempt = 0
        openSocket(resolved)
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
        if (ws.queueSize() > 500_000L) return
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
        mainHandler.postDelayed({ openSocket(url) }, delayMs)
    }

    private inner class Listener(private val gen: Int, private val url: String) : WebSocketListener() {
        private fun isCurrent() = gen == generation

        override fun onOpen(ws: WebSocket, response: Response) {
            if (!isCurrent()) { ws.close(1000, "Stale"); return }
            Log.i(TAG, "onOpen: CONNECTED ✓")
            isOpen = true; reconnectAttempt = 0
        }
        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent()) return
            Log.e(TAG, "onFailure: ${t.message}")
            isOpen = false
            if (shouldReconnect.get()) scheduleReconnect(url)
        }
        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) { ws.close(1000, null); return }
            isOpen = false; ws.close(1000, null)
            if (shouldReconnect.get()) scheduleReconnect(url)
        }
        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) return
            if (isOpen) { isOpen = false; if (shouldReconnect.get()) scheduleReconnect(url) }
        }
    }
}
