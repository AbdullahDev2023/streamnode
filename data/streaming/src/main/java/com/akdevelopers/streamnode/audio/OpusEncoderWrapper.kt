package com.akdevelopers.streamnode.audio
import com.theeasiestway.opus.Constants
import com.theeasiestway.opus.Opus

/**
 * Wrapper around theeasiestway/android-opus-codec (opus.aar).
 * Package confirmed from classes.jar: com.theeasiestway.opus
 *
 * Falls back gracefully: if native init fails, encode() returns null
 * and AudioCaptureEngine sends raw PCM instead.
 *
 * vbrEnabled — when true (default) Opus uses variable bitrate for best
 * quality-to-size ratio; when false it uses constant bitrate (CBR),
 * which is useful for metered / strict-budget links.
 */
class OpusEncoderWrapper(
    sampleRate: Int,
    bitrate: Int = 32_000,
    complexity: Int = 9,   // raised from 5 → higher quality at same bitrate
    val vbrEnabled: Boolean = true,  // Feature 1: VBR / CBR selection
) {
    private val codec: Opus? = runCatching {
        val sr = when (sampleRate) {
            8_000  -> Constants.SampleRate._8000()
            12_000 -> Constants.SampleRate._12000()
            16_000 -> Constants.SampleRate._16000()
            24_000 -> Constants.SampleRate._24000()
            else   -> Constants.SampleRate._48000()
        }
        Opus().also { c ->
            c.encoderInit(sr, Constants.Channels.mono(), Constants.Application.audio())
            c.encoderSetBitrate(Constants.Bitrate.instance(bitrate))
            c.encoderSetComplexity(Constants.Complexity.instance(complexity))
            // Feature 1: VBR mode.
            // Use reflection so this compiles against any version of the opus.aar —
            // older builds may not expose encoderSetVbr().  If the method is absent
            // the call silently no-ops; Opus's native default is already VBR, so
            // skipping the call is safe (we just can't force CBR on old AARs).
            runCatching {
                val vbrMethod = c.javaClass.getMethod("encoderSetVbr", Int::class.java)
                vbrMethod.invoke(c, if (vbrEnabled) 1 else 0)
            }
        }
    }.getOrNull()

    private var frameSize: Constants.FrameSize = Constants.FrameSize._320()

    /** Call after init so frame size constant matches AudioRecord buffer size. */
    fun updateFrameSize(samples: Int) {
        frameSize = when {
            samples <= 120  -> Constants.FrameSize._120()
            samples <= 240  -> Constants.FrameSize._240()
            samples <= 320  -> Constants.FrameSize._320()
            samples <= 480  -> Constants.FrameSize._480()
            samples <= 960  -> Constants.FrameSize._960()
            samples <= 1920 -> Constants.FrameSize._1920()
            else            -> Constants.FrameSize._2880()
        }
    }

    val isAvailable: Boolean get() = codec != null

    /**
     * Encodes one 16-bit PCM frame (ByteArray) into an Opus packet.
     * Returns null on failure; caller sends raw PCM instead.
     */
    fun encode(pcmBytes: ByteArray): ByteArray? =
        runCatching { codec?.encode(pcmBytes, frameSize) }.getOrNull()

    fun release() {
        runCatching { codec?.encoderRelease() }
    }
}
