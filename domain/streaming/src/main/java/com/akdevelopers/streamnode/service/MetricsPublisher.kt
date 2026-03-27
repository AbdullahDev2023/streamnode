package com.akdevelopers.streamnode.service

/**
 * MetricsPublisher — typed seam for writing periodic audio metrics to Firebase RTDB.
 *
 * Implemented by [com.akdevelopers.streamnode.remote.FirebaseRemoteController].
 * Segregated from [StatusPublisher] and [RemoteCommandSource] so consumers (e.g.
 * MicOrchestrator via StreamOrchestrator) depend only on the metric-write contract.
 */
interface MetricsPublisher {

    /**
     * Write a periodic audio health snapshot to /users/{streamId}/realtime.
     *
     * Called every [com.akdevelopers.streamnode.core.AppConstants.METRICS_INTERVAL_MS] (60 s)
     * while the microphone is active.
     *
     * @param framesPerSec  Audio frames delivered in the last measurement window.
     * @param kbps          Kilobits per second transmitted in the last window.
     * @param uptimeSec     Total seconds since the streaming session started.
     * @param quality       Quality preset name, e.g. "HIGH_QUALITY".
     */
    fun pushRealtimeMetrics(
        framesPerSec: Float,
        kbps: Float,
        uptimeSec: Int,
        quality: String
    )
}
