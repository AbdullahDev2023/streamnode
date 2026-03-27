package com.akdevelopers.streamnode.audio

/**
 * Audio quality presets available for runtime selection.
 *
 * | Preset       | Bitrate  | Use case                          |
 * |--------------|----------|-----------------------------------|
 * | HIGH_QUALITY | 192 kbps | Default — zero audible artefacts  |
 * | MEDIUM       |  96 kbps | Balanced — good on 3G / poor Wi-Fi|
 * | LOW          |  32 kbps | Emergency — very poor connection  |
 *
 * Changed at runtime via dashboard → POST /admin/:id/set-quality?level=MEDIUM
 * Dispatched over /control WebSocket as action="set_quality", url=<preset name>.
 */
enum class AudioQualityPreset(val label: String) {
    HIGH_QUALITY("HD"),
    MEDIUM("MD"),
    LOW("LQ")
}

data class AudioQualityConfig(
    val preset:          AudioQualityPreset,
    val sampleRate:      Int,
    val frameMs:         Int,    // frame duration in milliseconds (20ms = standard Opus)
    val enableAgc:       Boolean,
    val enableNs:        Boolean,
    val enableAec:       Boolean,
    val silenceGateRms:  Double, // RMS threshold; 0 = disabled
    val opusBitrate:     Int,    // Opus target bitrate in bps (e.g. 32_000); 0 = raw PCM fallback
    val opusComplexity:  Int = 9,// 0–10; higher = better quality, slightly more CPU
    val vbrEnabled:      Boolean = true, // true = Variable Bitrate (Opus default); false = CBR
) {
    val frameSamples: Int get() = sampleRate * frameMs / 1000
    val frameBytes:   Int get() = frameSamples * 2  // 16-bit PCM = 2 bytes/sample

    companion object {
        /**
         * 48 kHz · 192 kbps · Opus complexity 10 — absolute maximum quality.
         *
         * Why 192 kbps?
         *   Opus becomes perceptually transparent for mono audio at ~128 kbps,
         *   but 192 kbps eliminates any remaining artefacts on transient-rich content
         *   (claps, sibilants, room noise peaks) — the extra bandwidth costs nothing
         *   on a local/ngrok tunnel and guarantees headroom.
         *
         * No AEC / NS / AGC — all hardware DSP is disabled. Raw ADC samples only.
         * No silence gate — every frame is transmitted regardless of amplitude.
         * No compressor — the signal chain is: mic → PCM → Opus → network.
         *   Removing the compressor means the listener hears exactly what the mic hears.
         */
        val HIGH_QUALITY = AudioQualityConfig(
            preset         = AudioQualityPreset.HIGH_QUALITY,
            sampleRate     = 48_000,
            frameMs        = 20,   // FIX: 60ms → 20ms. 60ms frames meant any single frame drop = 60ms of audible silence/crack. 20ms limits the artifact window to 20ms and dramatically reduces perceptible crackling.
            enableAgc      = false,
            enableNs       = false,
            enableAec      = false,
            silenceGateRms = 0.0,
            opusBitrate    = 192_000,  // above perceptual transparency — zero artefacts
            opusComplexity = 7,        // OPT: was 10; 7 is perceptually identical for voice, ~35% less CPU
            vbrEnabled     = true,     // VBR gives best quality-to-bitrate at high rate
        )

        /**
         * 48 kHz · 96 kbps · Opus complexity 8 — optimised for phone-call capture.
         *
         * Why lower complexity (8) and bitrate (96 kbps)?
         *   During a call the telephony stack consumes CPU heavily.
         *   96 kbps is still perceptually transparent for speech content.
         *   AGC is enabled to normalise the speaker volume (farther from mic)
         *   relative to the caller's voice (directly into mic).
         *
         * Audio source will be switched to MIC (not VOICE_COMMUNICATION) and
         * speakerphone will be forced on so both voices are captured acoustically.
         */
        val CALL_CAPTURE = AudioQualityConfig(
            preset         = AudioQualityPreset.HIGH_QUALITY,
            sampleRate     = 48_000,
            frameMs        = 20,   // FIX: 60ms → 20ms. Same reasoning as HIGH_QUALITY.
            enableAgc      = true,
            enableNs       = true,
            enableAec      = false,
            silenceGateRms = 0.0,
            opusBitrate    = 96_000,
            opusComplexity = 5,        // OPT: was 8; telephony stack is CPU-heavy, 5 = lower overhead
            vbrEnabled     = true,
        )

        /**
         * 48 kHz · 96 kbps · Opus complexity 5 — balanced quality for poor connections.
         * AGC + NS on to compensate for lower bitrate's reduced headroom.
         */
        val MEDIUM = AudioQualityConfig(
            preset         = AudioQualityPreset.MEDIUM,
            sampleRate     = 48_000,
            frameMs        = 20,
            enableAgc      = true,
            enableNs       = true,
            enableAec      = false,
            silenceGateRms = 0.0,
            opusBitrate    = 96_000,
            opusComplexity = 5,        // OPT: was 8; 5 is sufficient for balanced voice quality
            vbrEnabled     = true,
        )

        /**
         * 48 kHz · 32 kbps · Opus complexity 5 — emergency / very poor link.
         * AGC + NS critical at this bitrate. Silence gate enabled to save bandwidth.
         */
        val LOW = AudioQualityConfig(
            preset         = AudioQualityPreset.LOW,
            sampleRate     = 48_000,
            frameMs        = 10,       // Feature 1: 10 ms = lower latency on poor connections
            enableAgc      = true,
            enableNs       = true,
            enableAec      = false,
            silenceGateRms = 150.0,    // drop near-silent frames at low bitrate
            opusBitrate    = 32_000,
            opusComplexity = 3,        // OPT: was 5; minimum viable complexity for emergency link
            vbrEnabled     = false,    // Feature 1: CBR for predictable bandwidth on metered links
        )

        fun fromPreset(preset: AudioQualityPreset): AudioQualityConfig = when (preset) {
            AudioQualityPreset.HIGH_QUALITY -> HIGH_QUALITY
            AudioQualityPreset.MEDIUM       -> MEDIUM
            AudioQualityPreset.LOW          -> LOW
        }

        // ── Feature 8: Advanced Audio Quality Options ─────────────────────────
        /**
         * Build a fully custom config from raw dashboard parameters.
         * All fields are caller-supplied; no preset defaults are applied.
         * The [preset] label is set to HIGH_QUALITY for display only.
         *
         * @param sampleRate    AudioRecord + Opus sample rate (8000|16000|32000|48000)
         * @param bitrate       Opus target bitrate in bps (6_000–510_000)
         * @param frameMs       Opus frame duration in ms (2|5|10|20|40|60)
         * @param vbrEnabled    true = Variable Bitrate; false = CBR
         * @param complexity    Opus encoder complexity 0–10
         * @param enableAgc     Automatic Gain Control
         * @param enableNs      Noise Suppression
         * @param enableAec     Acoustic Echo Cancellation
         * @param silenceGateRms VOX gate RMS threshold; 0.0 = disabled
         */
        fun custom(
            sampleRate:      Int,
            bitrate:         Int,
            frameMs:         Int,
            vbrEnabled:      Boolean,
            complexity:      Int,
            enableAgc:       Boolean,
            enableNs:        Boolean,
            enableAec:       Boolean,
            silenceGateRms:  Double,
        ): AudioQualityConfig = AudioQualityConfig(
            preset         = AudioQualityPreset.HIGH_QUALITY, // display label only
            sampleRate     = sampleRate.let { listOf(8_000, 16_000, 32_000, 48_000).minByOrNull { r -> kotlin.math.abs(r - it) }!! },
            frameMs        = frameMs.coerceIn(2, 60),
            enableAgc      = enableAgc,
            enableNs       = enableNs,
            enableAec      = enableAec,
            silenceGateRms = silenceGateRms.coerceAtLeast(0.0),
            opusBitrate    = bitrate.coerceIn(6_000, 510_000),
            opusComplexity = complexity.coerceIn(0, 10),
            vbrEnabled     = vbrEnabled,
        )
    }
}
