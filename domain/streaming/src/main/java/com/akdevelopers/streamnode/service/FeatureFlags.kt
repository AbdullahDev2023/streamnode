package com.akdevelopers.streamnode.service

/**
 * FeatureFlags — v6 unified runtime feature toggle state.
 *
 * Sent from the server dashboard via POST /admin/:id/feature-flags and delivered
 * to the phone over the /control WebSocket as:
 *   { type: "cmd", action: "feature_flags", url: "<JSON of this class>" }
 *
 * CommandProcessor parses the JSON and dispatches to MicOrchestrator / player
 * callbacks. All fields are nullable so a partial update only touches the flags
 * that are explicitly set — unset fields keep their current values.
 *
 * Naming mirrors the TypeScript FeatureFlags interface in server/src/channels.ts
 * so JSON round-trips without renaming.
 */
data class FeatureFlags(
    // Feature 1 — VBR + frame size
    val vbrEnabled:              Boolean? = null,
    val frameSizeMs:             Int?     = null,  // 2 | 5 | 10 | 20 | 40 | 60

    // Feature 2 — Sample rate
    val sampleRateHz:            Int?     = null,  // 8000 | 16000 | 32000 | 48000

    // Feature 3 — VOX / silence gate
    val voxEnabled:              Boolean? = null,
    val voxThresholdRms:         Double?  = null,

    // Feature 4 — Internal audio / media capture
    val internalAudioEnabled:    Boolean? = null,
    val internalAudioMixMic:     Boolean? = null,

    // Feature 8 — Advanced quality (fine-grained overrides)
    val customBitrate:           Int?     = null,  // 0 = use preset default
    val opusComplexity:          Int?     = null,  // -1 = use preset default

    // Feature 9 — Metrics reporting
    val telemetryIntervalMs:     Long?    = null,
    val streamMetricsEnabled:    Boolean? = null,

    // Features 5–7 — Browser-side flags (forwarded to /listen clients by the server)
    val jitterControlEnabled:    Boolean? = null,
    val listenerRecordingEnabled: Boolean? = null,
) {
    companion object {
        /**
         * Parse a JSON string received from the server into a [FeatureFlags] instance.
         * Unknown fields are silently ignored; missing fields remain null.
         */
        fun fromJson(json: String): FeatureFlags = runCatching {
            val j = org.json.JSONObject(json)
            FeatureFlags(
                vbrEnabled              = if (j.has("vbrEnabled"))              j.optBoolean("vbrEnabled")              else null,
                frameSizeMs             = if (j.has("frameSizeMs"))             j.optInt("frameSizeMs")                 else null,
                sampleRateHz            = if (j.has("sampleRateHz"))            j.optInt("sampleRateHz")                else null,
                voxEnabled              = if (j.has("voxEnabled"))              j.optBoolean("voxEnabled")              else null,
                voxThresholdRms         = if (j.has("voxThresholdRms"))         j.optDouble("voxThresholdRms")          else null,
                internalAudioEnabled    = if (j.has("internalAudioEnabled"))    j.optBoolean("internalAudioEnabled")    else null,
                internalAudioMixMic     = if (j.has("internalAudioMixMic"))     j.optBoolean("internalAudioMixMic")     else null,
                customBitrate           = if (j.has("customBitrate"))           j.optInt("customBitrate")               else null,
                opusComplexity          = if (j.has("opusComplexity"))          j.optInt("opusComplexity")              else null,
                telemetryIntervalMs     = if (j.has("telemetryIntervalMs"))     j.optLong("telemetryIntervalMs")        else null,
                streamMetricsEnabled    = if (j.has("streamMetricsEnabled"))    j.optBoolean("streamMetricsEnabled")    else null,
                jitterControlEnabled    = if (j.has("jitterControlEnabled"))    j.optBoolean("jitterControlEnabled")    else null,
                listenerRecordingEnabled = if (j.has("listenerRecordingEnabled")) j.optBoolean("listenerRecordingEnabled") else null,
            )
        }.getOrDefault(FeatureFlags())
    }
}
