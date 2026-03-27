package com.akdevelopers.streamnode.system
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.akdevelopers.streamnode.analytics.Analytics
import com.akdevelopers.streamnode.core.AppConstants

/**
 * Monitors network connectivity. When a usable network appears (after a drop
 * or when switching WiFi <-> Mobile Data), notifies the StreamingService so
 * the WebSocket can reconnect immediately instead of waiting for backoff.
 *
 * FIX (2026-03-11): Added a 3 s startup grace period.
 * ConnectivityManager fires onAvailable() + onCapabilitiesChanged() immediately
 * on registration when the network is already up. This caused 2–4 spurious
 * reconnectNow() calls before the initial connection could complete its TLS
 * handshake. Callbacks within GRACE_MS of register() are now silently ignored.
 *
 * FIX (2026-03-13): Added per-event cooldown.
 * onCapabilitiesChanged fires multiple times per second on network state changes.
 * Without a cooldown, each callback triggers a reconnectNow(), which floods the
 * server with simultaneous TLS handshakes. A [AppConstants.NETWORK_RECONNECT_COOLDOWN_MS]
 * cooldown between reconnect triggers collapses rapid bursts into a single action.
 */
class NetworkChangeReceiver(
    private val context: Context,
    private val onNetworkAvailable: () -> Unit,
    private val onNetworkLost: () -> Unit
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /** Timestamp set in register(). Callbacks within GRACE_MS are suppressed. */
    @Volatile private var registeredAt = 0L
    private val GRACE_MS = 3_000L

    /** Last time onNetworkAvailable was dispatched — prevents reconnect storms. */
    @Volatile private var lastReconnectAt = 0L

    private fun shouldFireReconnect(): Boolean {
        val now = System.currentTimeMillis()
        if (now - registeredAt < GRACE_MS) return false
        if (now - lastReconnectAt < AppConstants.NETWORK_RECONNECT_COOLDOWN_MS) return false
        lastReconnectAt = now
        return true
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!shouldFireReconnect()) return
            Analytics.logNetworkAvailable()
            onNetworkAvailable()
        }
        override fun onLost(network: Network) {
            Analytics.logNetworkLost()
            onNetworkLost()
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!shouldFireReconnect()) return
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                Analytics.logNetworkAvailable()
                onNetworkAvailable()
            }
        }
    }

    fun register() {
        registeredAt = System.currentTimeMillis()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try { cm.registerNetworkCallback(request, callback) } catch (_: Exception) {}
    }

    fun unregister() {
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
    }
}
