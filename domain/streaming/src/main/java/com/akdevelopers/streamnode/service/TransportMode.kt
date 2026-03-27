package com.akdevelopers.streamnode.service

/**
 * TransportMode — represents the active audio transport path for a streaming session.
 *
 * Phase 3: Used by ConnectionOrchestrator's fallback state machine, MainViewModel UI,
 * StreamingService notification, and the server's transportStatus protocol.
 *
 * State transitions:
 *   App start ────────────────────────────────► WEBRTC_NEGOTIATING
 *   ICE connected within timeout ─────────────► WEBRTC_P2P
 *   ICE failed / timeout ─────────────────────► WEBSOCKET_RELAY  (fallback)
 *   User selects WebSocket explicitly ────────► WEBSOCKET_RELAY
 *   User selects WebRTC explicitly ───────────► WEBRTC_NEGOTIATING → WEBRTC_P2P
 *   User selects Auto (default) ──────────────► WEBRTC_NEGOTIATING → best available
 *   P2P degrades → fallback triggered ────────► FALLBACK_TRIGGERED → WEBSOCKET_RELAY
 *   P2P recovers → return to WebRTC ──────────► WEBRTC_NEGOTIATING → WEBRTC_P2P
 */
enum class TransportMode {
    /** WebRTC P2P via SRTP/DTLS — ICE connected, audio flowing directly to browser. */
    WEBRTC_P2P,

    /** All audio relayed through the server via WebSocket binary frames. */
    WEBSOCKET_RELAY,

    /** ICE gathering/checking in progress — not yet connected. */
    WEBRTC_NEGOTIATING,

    /**
     * Transient state: WebRTC failed or degraded, WebSocket fallback activated.
     * ICE restart is pending — may recover back to WEBRTC_P2P.
     */
    FALLBACK_TRIGGERED;

    /** Short label for notification / dashboard badge. */
    val label: String get() = when (this) {
        WEBRTC_P2P         -> "⚡ P2P"
        WEBSOCKET_RELAY    -> "📡 Relay"
        WEBRTC_NEGOTIATING -> "🔄 ICE…"
        FALLBACK_TRIGGERED -> "⚠ Fallback"
    }

    /** Wire protocol string sent to server in transportStatus messages. */
    val wireValue: String get() = when (this) {
        WEBRTC_P2P         -> "webrtc_p2p"
        WEBSOCKET_RELAY    -> "websocket_relay"
        WEBRTC_NEGOTIATING -> "negotiating"
        FALLBACK_TRIGGERED -> "fallback"
    }

    /** Single emoji icon for the foreground notification badge. */
    val emoji: String get() = when (this) {
        WEBRTC_P2P         -> "⚡"
        WEBSOCKET_RELAY    -> "📡"
        WEBRTC_NEGOTIATING -> "🔄"
        FALLBACK_TRIGGERED -> "⚠"
    }
}
