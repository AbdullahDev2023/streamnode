package com.akdevelopers.streamnode.service

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Decoupling bridge so core:platform can observe streaming state and
 * build start intents WITHOUT creating a compile-time dependency on
 * feature:stream (which would be circular — feature:stream already
 * depends on core:platform).
 *
 * StreamingService writes to this bridge; WatchdogWorker and
 * BootReceiver read from it.
 *
 * reconnectCallback: wired by StreamingService so WatchdogWorker can
 * trigger a WS reconnect on the live service instead of killing and
 * restarting the whole process (which resets all backoff state and
 * floods the server with simultaneous TLS handshakes).
 */
object StreamingServiceBridge {

    private val _isRunning   = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    fun setRunning(v: Boolean)   { _isRunning.value   = v }
    fun setConnected(v: Boolean) { _isConnected.value = v }

    /**
     * Called by StreamingService.onCreate / onDestroy to register / clear the
     * in-process reconnect hook.  WatchdogWorker calls [triggerReconnect] when
     * the service is alive but the WebSocket is down (e.g. server just came back
     * after a long outage).  This avoids a full service restart that would
     * interrupt telemetry, clear the command dedup set, and reset backoff.
     */
    @Volatile var reconnectCallback: (() -> Unit)? = null

    /**
     * Trigger an immediate reconnect on the live service instance.
     * No-op when the service is not running (reconnectCallback is null).
     * Returns true if the callback was invoked, false if the service was not alive.
     */
    fun triggerReconnect(): Boolean {
        val cb = reconnectCallback ?: return false
        cb.invoke()
        return true
    }

    private const val SERVICE_CLASS =
        "com.akdevelopers.streamnode.service.StreamingService"

    const val EXTRA_URL = "server_url"

    fun buildStartIntent(ctx: Context, url: String): Intent =
        Intent().setClassName(ctx, SERVICE_CLASS).putExtra(EXTRA_URL, url)
}
