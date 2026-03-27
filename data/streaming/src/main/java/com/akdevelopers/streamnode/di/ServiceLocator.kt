package com.akdevelopers.streamnode.di

import android.content.Context
import android.util.Log
import com.akdevelopers.streamnode.core.AppFeature

/**
 * ServiceLocator — lightweight process-wide access point.
 *
 * Moved from :app to :data:streaming so that :feature:stream can access it
 * without a circular dependency (:feature:stream already depends on :data:streaming).
 *
 * Holds the single [AppGraph] instance. Use [ServiceLocator] only where
 * constructor injection is impractical (e.g. BroadcastReceivers, Services).
 */
object ServiceLocator {

    private const val TAG = "ServiceLocator"

    @Volatile private var _graph: AppGraph? = null

    val graph: AppGraph
        get() = _graph ?: error("ServiceLocator.init() has not been called yet.")

    fun init(context: Context) {
        if (_graph != null) { Log.w(TAG, "init() called more than once — ignoring"); return }
        _graph = AppGraph(context.applicationContext)
        Log.d(TAG, "Initialised ✓")
    }

    fun tearDownAll() {
        _graph?.tearDownAll()
        _graph = null
        Log.d(TAG, "Torn down")
    }

    fun registerFeature(feature: AppFeature) = graph.registerFeature(feature)

    inline fun <reified T : AppFeature> feature(): T? = graph.feature<T>()

    @Deprecated(
        "Use the reified feature<T>() overload instead of string-based lookup",
        ReplaceWith("graph.feature<T>()")
    )
    fun <T : AppFeature> featureById(id: String): T? = graph.featureById(id)

    val httpClient get() = graph.httpClient

    fun requireContext(): Context = graph.appContext
}
