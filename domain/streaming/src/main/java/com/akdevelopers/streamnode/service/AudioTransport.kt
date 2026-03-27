package com.akdevelopers.streamnode.service

import android.content.Context

/**
 * AudioTransport — typed seam over the binary audio WebSocket channel.
 *
 * Implemented by [com.akdevelopers.streamnode.audio.AudioWebSocketClient] in data:streaming.
 * Consumed by [StreamOrchestrator] so tests can inject a fake transport without
 * spinning up a real WebSocket.
 *
 * Connection lifecycle:
 *   connect()  → opens the socket and starts auto-reconnect on failure
 *   disconnect() → cleanly closes; stops the reconnect loop
 *
 * Frame delivery:
 *   sendFrame() → fire-and-forget binary write; no-op when socket is not open
 *   sendStreamingState() → notifies the server whether the mic is active
 */
interface AudioTransport {

    /** True while the underlying WebSocket is in the OPEN state. */
    val isOpen: Boolean

    /** Sample rate forwarded in the codec-negotiation handshake header. */
    var sampleRate: Int

    /** Frame duration (ms) forwarded in the codec-negotiation handshake header. */
    var frameMs: Int

    /**
     * Callback fired on every connection-state change.
     * Values: [StreamStatus.CONNECTING], [StreamStatus.CONNECTED_IDLE], [StreamStatus.RECONNECTING].
     */
    var onStatusChange: ((StreamStatus) -> Unit)?

    /** Open the WebSocket for [url]. Auto-reconnects on failure until [disconnect] is called. */
    fun connect(url: String, context: Context)

    /** Close the socket and stop the reconnect loop. Idempotent. */
    fun disconnect()

    /** Trigger an immediate reconnect attempt (e.g. after a network change). */
    fun reconnectNow()

    /** Reconnect to a new URL, persisting the change. */
    fun reconnectTo(newUrl: String, context: Context) {
        connect(newUrl, context)
    }

    /**
     * Enqueue a binary audio [frame] for transmission.
     * No-op if the socket is not currently open.
     */
    fun sendFrame(data: ByteArray)

    /**
     * Notify the server whether the microphone is currently active.
     * Sent on every connect and on every mic start/stop transition.
     */
    fun sendStreamingState(active: Boolean)
}
