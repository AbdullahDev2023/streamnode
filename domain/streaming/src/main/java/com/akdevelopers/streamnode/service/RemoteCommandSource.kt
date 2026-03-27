package com.akdevelopers.streamnode.service

/**
 * RemoteCommandSource — typed seam over the Firebase RTDB command channel.
 *
 * Implemented by [com.akdevelopers.streamnode.remote.FirebaseRemoteController] in data:streaming.
 * Decouples StreamOrchestrator and StreamNodeApp from the concrete Firebase SDK so
 * each can be tested against a fake implementation.
 *
 * Lifecycle:
 *   start() → attach listener on /users/{streamId}/control
 *   stop()  → detach listener; remove device from /streams directory
 *
 * Commands arrive via [onCommandReceived] and are routed to CommandProcessor for dedup.
 */
interface RemoteCommandSource {

    /**
     * Invoked on every new (non-duplicate) command snapshot.
     * @param commandId  UUID used as the primary dedup key in CommandProcessor.
     * @param action     "start" | "stop" | "change_url" | "reconnect" | "crash_*"
     * @param url        Non-empty only for "change_url" commands.
     */
    var onCommandReceived: ((commandId: String, action: String, url: String) -> Unit)?

    /** Attach the Firebase value listener and begin forwarding commands. */
    fun start()

    /** Detach the listener and clean up the /streams directory entry. */
    fun stop()

    /**
     * One-shot fetch of the shared server URL from /streamnode_config/serverUrl_v2.
     * Times out after [com.akdevelopers.streamnode.core.AppConstants.FIREBASE_FETCH_TIMEOUT_MS].
     */
    fun fetchServerUrl(onSuccess: (String) -> Unit, onFailure: (() -> Unit)? = null)
}
