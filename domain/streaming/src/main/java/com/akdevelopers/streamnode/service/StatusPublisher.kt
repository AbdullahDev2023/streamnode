package com.akdevelopers.streamnode.service

/**
 * StatusPublisher — typed seam for writing the live stream status to Firebase RTDB.
 *
 * Implemented by [com.akdevelopers.streamnode.remote.FirebaseRemoteController].
 * Keeping this separate from [RemoteCommandSource] and [MetricsPublisher] respects
 * interface-segregation: consumers that only need to push status do not depend on
 * the command-listening or metrics infrastructure.
 */
interface StatusPublisher {

    /**
     * Write the current stream status and associated server URL to
     * /users/{streamId}/status in Firebase RTDB.
     *
     * @param status    The current [StreamStatus] enum value.
     * @param serverUrl The WebSocket URL currently in use (stored for dashboard display).
     */
    fun pushStatus(status: StreamStatus, serverUrl: String)
}
