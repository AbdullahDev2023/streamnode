package com.akdevelopers.streamnode.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import com.akdevelopers.streamnode.analytics.Analytics
import com.akdevelopers.streamnode.core.AppConstants
import com.akdevelopers.streamnode.core.BaseViewModel
import com.akdevelopers.streamnode.di.ServiceLocator
import com.akdevelopers.streamnode.service.CommandProcessor
import com.akdevelopers.streamnode.service.StreamingService
import com.akdevelopers.streamnode.service.TransportMode
import com.akdevelopers.streamnode.util.streamnodePrefs

/**
 * MainViewModel — drives auto-connect and exposes status flows for MainActivity.
 *
 * MainActivity is a pure log console — no buttons. This ViewModel therefore
 * only needs to:
 *  - Expose StreamingService status as LiveData for the badge
 *  - Fetch the server URL from Firebase and auto-start the service
 */
class MainViewModel(app: Application) : BaseViewModel(app) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    val status  = StreamingService.status.asLiveData()
    val isRunning = StreamingService.isRunning.asLiveData()

    // ── Phase 5: Transport mode StateFlow (mirrors StreamingService) ──────────
    /**
     * Current transport mode observed from [StreamingService.transportMode].
     * Collected by MainActivity to update the transport selector row and its
     * status TextView without querying the service directly.
     */
    val transportMode: StateFlow<TransportMode> = StreamingService.transportMode

    private val _fetchStatusMessage = MutableStateFlow<String?>(null)
    val fetchStatusMessage: StateFlow<String?> = _fetchStatusMessage

    private val isFetching = AtomicBoolean(false)

    var serverUrl: String
        get() = prefs.getString(AppConstants.PREF_SERVER_URL, AppConstants.DEFAULT_SERVER_URL)
            ?: AppConstants.DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(AppConstants.PREF_SERVER_URL, value).apply()

    var autoRestart: Boolean
        get() = prefs.getBoolean(AppConstants.PREF_AUTO_RESTART, true)
        set(value) = prefs.edit().putBoolean(AppConstants.PREF_AUTO_RESTART, value).apply()

    /** Start the streaming service (if not running) with the given URL. */
    fun ensureServiceRunning(context: Context) {
        if (isRunning.value == true) return
        val url = serverUrl
        if (url.isBlank() || !isUrlValid()) return
        autoRestart = true
        StreamingService.log("▶️  ensureServiceRunning — starting foreground service")
        context.applicationContext.startForegroundService(
            StreamingService.buildStartIntent(context.applicationContext, url)
        )
    }


    /**
     * Fetch the server URL from Firebase RTDB (v2 key).
     * On success → persist + call ensureServiceRunning.
     * On failure → fall back to saved URL if valid.
     */
    fun fetchUrlAndAutoConnect(context: Context) {
        if (isRunning.value == true) return
        if (!isFetching.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        _fetchStatusMessage.value = "🔄 Fetching server URL from Firebase…"
        StreamingService.log("🔄 Fetching server URL from Firebase…")

        ServiceLocator.graph.appRemoteCommandSource.fetchServerUrl(
            onSuccess = { url ->
                isFetching.set(false)
                serverUrl = url
                _fetchStatusMessage.value = "✅ Firebase URL received"
                StreamingService.log("✅ Firebase URL: $url")
                Analytics.logFirebaseUrlFetched("firebase")
                ensureServiceRunning(appCtx)
            },
            onFailure = {
                isFetching.set(false)
                if (isUrlValid()) {
                    _fetchStatusMessage.value = "⚠️ Firebase unavailable — using saved URL"
                    StreamingService.log("⚠️ Firebase unavailable — falling back to saved URL")
                    Analytics.logFirebaseUrlFetched("prefs")
                    ensureServiceRunning(appCtx)
                } else {
                    _fetchStatusMessage.value = "❌ No server URL configured"
                    StreamingService.log("❌ No server URL configured — cannot start")
                    Analytics.logFirebaseUrlFetched("none")
                    Log.w(TAG, "fetchUrlAndAutoConnect: no valid URL available")
                }
            })
    }

    fun clearFetchStatus() { _fetchStatusMessage.value = null }

    fun isUrlValid(): Boolean =
        serverUrl.startsWith("ws://") || serverUrl.startsWith("wss://")

    // ── Phase 5: Transport selector ───────────────────────────────────────────
    /**
     * Pin or release the transport from the in-app RadioGroup.
     *
     * [mode] must be one of: "auto" | "webrtc" | "websocket"
     *
     * Persists PREF_WEBRTC_ENABLED + PREF_TRANSPORT_AUTO immediately so the
     * next service restart picks up the user's choice, then — if the service
     * is already running — calls CommandProcessor.onSetTransport which is
     * wired to the live ConnectionOrchestrator for a zero-gap hot-swap.
     */
    fun setTransport(context: Context, mode: String) {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
        when (mode) {
            "websocket" -> prefs.edit()
                .putBoolean(AppConstants.PREF_WEBRTC_ENABLED, false)
                .putBoolean(AppConstants.PREF_TRANSPORT_AUTO, false)
                .apply()
            "webrtc" -> prefs.edit()
                .putBoolean(AppConstants.PREF_WEBRTC_ENABLED, true)
                .putBoolean(AppConstants.PREF_TRANSPORT_AUTO, false)
                .apply()
            "auto" -> prefs.edit()
                .putBoolean(AppConstants.PREF_WEBRTC_ENABLED, true)
                .putBoolean(AppConstants.PREF_TRANSPORT_AUTO, true)
                .apply()
        }
        // If service is live, hot-swap via CommandProcessor → ConnectionOrchestrator.
        // CommandProcessor.onSetTransport is wired in StreamingService.wireCommandProcessor()
        // and always points to the currently-running ConnectionOrchestrator instance.
        if (isRunning.value == true) {
            CommandProcessor.onSetTransport?.invoke(mode)
        }
    }

    /** Returns the transport mode the user last pinned (reads from SharedPreferences). */
    fun savedTransportPreference(context: Context): String {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
        val isAuto   = prefs.getBoolean(AppConstants.PREF_TRANSPORT_AUTO, true)
        val isWebRtc = prefs.getBoolean(AppConstants.PREF_WEBRTC_ENABLED, AppConstants.WEBRTC_ENABLED_DEFAULT)
        return when {
            isAuto   -> "auto"
            isWebRtc -> "webrtc"
            else     -> "websocket"
        }
    }
}
