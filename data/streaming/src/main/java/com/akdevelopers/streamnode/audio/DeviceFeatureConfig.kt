package com.akdevelopers.streamnode.audio

/**
 * DeviceFeatureConfig — runtime feature flags pushed from the server dashboard.
 *
 * Sent by the server as a {"type":"feature_config", ...} JSON message over the
 * /control WebSocket whenever the dashboard changes a setting, and replayed to
 * the phone immediately on /control reconnect.
 *
 * All defaults match v5.2 behaviour exactly — a device that never receives this
 * message behaves identically to previous releases.
 *
 * ── Feature map ──────────────────────────────────────────────────────────────
 *  vbrEnabled           Feature 1: Opus VBR mode (default: true = VBR)
 *  frameSizeMs          Feature 1: Opus frame duration (default: 60 ms)
 *  sampleRate           Feature 2: PCM + Opus sample rate (default: 48 000 Hz)
 *  voxEnabled           Feature 3: VOX silence gate on/off
 *  voxThresholdRms      Feature 3: RMS gate threshold (0 = gate off)
 *  internalAudioEnabled Feature 4: AudioPlaybackCapture (API 29+)
 *  internalAudioMix     Feature 4: Mix mic + internal (false = standalone)
 *  metricsIntervalMs    Feature 9: Telemetry send interval (default: 60 000 ms)
 *  extendedMetrics      Feature 9: Send audio/RAM/storage metrics (default: false)
 */
data class DeviceFeatureConfig(
    val vbrEnabled:            Boolean = true,
    val frameSizeMs:           Int     = 60,
    val sampleRate:            Int     = 48_000,
    val voxEnabled:            Boolean = false,
    val voxThresholdRms:       Double  = 0.0,
    val internalAudioEnabled:  Boolean = false,
    val internalAudioMix:      Boolean = false,
    val metricsIntervalMs:     Long    = 60_000L,
    val extendedMetrics:       Boolean = false,
) {
    companion object {
        /** Parses a feature_config JSON object. Unknown keys are silently ignored. */
        fun fromJson(json: org.json.JSONObject): DeviceFeatureConfig = DeviceFeatureConfig(
            vbrEnabled           = json.optBoolean("vbrEnabled",           true),
            frameSizeMs          = json.optInt    ("frameSizeMs",          60),
            sampleRate           = json.optInt    ("sampleRate",           48_000),
            voxEnabled           = json.optBoolean("voxEnabled",           false),
            voxThresholdRms      = json.optDouble ("voxThresholdRms",      0.0),
            internalAudioEnabled = json.optBoolean("internalAudioEnabled", false),
            internalAudioMix     = json.optBoolean("internalAudioMix",     false),
            metricsIntervalMs    = json.optLong   ("metricsIntervalMs",    60_000L),
            extendedMetrics      = json.optBoolean("extendedMetrics",      false),
        )

        /** The defaults — identical to v5.2 behaviour. */
        val DEFAULT = DeviceFeatureConfig()
    }
}
