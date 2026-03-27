package com.akdevelopers.streamnode.di

import android.content.Context
import com.akdevelopers.streamnode.core.AppFeature
import com.akdevelopers.streamnode.remote.FirebaseRemoteController
import com.akdevelopers.streamnode.service.ConnectionOrchestrator
import com.akdevelopers.streamnode.service.RemoteCommandSource
import com.akdevelopers.streamnode.service.StatusPublisher
import com.akdevelopers.streamnode.service.MetricsPublisher
import com.akdevelopers.streamnode.service.StreamOrchestrator
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * AppGraph — the application's composition root.
 *
 * Moved from :app to :data:streaming so that :feature:stream can access it
 * without a circular dependency (:feature:stream already depends on :data:streaming).
 *
 * ── Design rules ─────────────────────────────────────────────────────────────
 *  • Every dependency returned by this class is typed as an *interface*, not
 *    the concrete class.  Callers in feature:stream / core never import a
 *    data:streaming class directly.
 *  • Factories (e.g. [newStreamOrchestrator]) are called per-service-instance,
 *    not stored as lazy vals, because StreamingService is created/destroyed
 *    multiple times during the app lifetime.
 *  • [appRemoteCommandSource] is the *app-level* Firebase listener covering
 *    the gap when StreamingService is not running.
 */
class AppGraph(val appContext: Context) {

    // ── Shared HTTP client ─────────────────────────────────────────────────────
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // ── App-level Firebase remote-command source ───────────────────────────────
    val appRemoteCommandSource: RemoteCommandSource by lazy {
        FirebaseRemoteController(appContext)
    }

    val appStatusPublisher:  StatusPublisher  get() = appRemoteCommandSource as StatusPublisher
    val appMetricsPublisher: MetricsPublisher get() = appRemoteCommandSource as MetricsPublisher

    // ── Per-service factories ──────────────────────────────────────────────────
    fun newStreamOrchestrator(context: Context): StreamOrchestrator =
        ConnectionOrchestrator(context)

    // ── Feature registry ───────────────────────────────────────────────────────
    @PublishedApi
    internal val _features = CopyOnWriteArrayList<AppFeature>()

    fun registerFeature(feature: AppFeature) {
        _features += feature
        feature.initialize(appContext)
    }

    inline fun <reified T : AppFeature> feature(): T? =
        _features.filterIsInstance<T>().firstOrNull()

    @Suppress("UNCHECKED_CAST")
    fun <T : AppFeature> featureById(id: String): T? =
        _features.firstOrNull { it.featureId == id } as? T

    fun tearDownAll() {
        _features.forEach { runCatching { it.tearDown() } }
        _features.clear()
    }
}
