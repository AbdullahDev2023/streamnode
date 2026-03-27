package com.akdevelopers.streamnode.audio

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface

/**
 * ScreenCaptureEngine — encodes the Android screen to H.264 Baseline NAL units.
 *
 * Usage:
 *   val engine = ScreenCaptureEngine(projection, width, height, dpi) { nalUnit ->
 *       screenWsClient.sendFrame(nalUnit)
 *   }
 *   engine.start()
 *   // ...
 *   engine.stop()
 *
 * Design decisions (matching the plan):
 *  - H.264 Baseline profile (avc1.42E01E) — widest WebCodecs browser support.
 *  - ~1 Mbps target bitrate, keyframe every 2 s.
 *  - VirtualDisplay feeds a Surface connected to MediaCodec input.
 *  - Output: raw Annex-B NAL byte arrays forwarded via [onNalUnit].
 *
 * Thread-safety: start/stop must be called from the same thread (main or a
 * dedicated handler); the MediaCodec callback fires on its own internal thread.
 */
class ScreenCaptureEngine(
    private val projection:  MediaProjection,
    private val width:       Int,
    private val height:      Int,
    private val densityDpi:  Int,
    private val onNalUnit:   (ByteArray) -> Unit,
    private val onError:     (String) -> Unit = {}
) {
    private val TAG = "AC_ScreenCapture"

    private var codec:          MediaCodec?     = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inputSurface:   Surface?        = null

    @Volatile private var running = false

    /** Saved SPS+PPS Annex-B bytes delivered in the CODEC_CONFIG output buffer.
     *  Must be prepended to every IDR keyframe so the browser VideoDecoder
     *  can initialize (Android does NOT embed them automatically). */
    @Volatile private var codecConfigBytes: ByteArray? = null

    companion object {
        private const val MIME        = "video/avc"          // H.264
        private const val BITRATE     = 1_000_000            // 1 Mbps
        private const val FRAME_RATE  = 30
        private const val I_FRAME_SEC = 2                    // keyframe every 2 s
        private const val PROFILE     = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        private const val LEVEL       = MediaCodecInfo.CodecProfileLevel.AVCLevel31
    }

    fun start() {
        if (running) { Log.w(TAG, "start: already running"); return }
        Log.i(TAG, "start: ${width}x${height} @ ${densityDpi}dpi")

        try {
            // ── Configure MediaCodec ──────────────────────────────────────────
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE,        BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE,      FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_SEC)
                setInteger(MediaFormat.KEY_PROFILE,         PROFILE)
                setInteger(MediaFormat.KEY_LEVEL,           LEVEL)
            }

            codec = MediaCodec.createEncoderByType(MIME).also { c ->
                c.setCallback(object : MediaCodec.Callback() {

                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        // Surface-mode encoder — no manual input buffers needed.
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo
                    ) {
                        if (!running) { codec.releaseOutputBuffer(index, false); return }
                        val buf = codec.getOutputBuffer(index) ?: run {
                            codec.releaseOutputBuffer(index, false); return
                        }
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        codec.releaseOutputBuffer(index, false)

                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // Save SPS/PPS — Android does NOT embed these in keyframes
                            // automatically; we must prepend them ourselves.
                            codecConfigBytes = bytes
                            return
                        }

                        // Prepend saved SPS/PPS to every IDR keyframe so the browser
                        // VideoDecoder can (re-)initialize on each key frame.
                        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        val payload: ByteArray = if (isKey) {
                            val sps = codecConfigBytes
                            if (sps != null) sps + bytes else bytes
                        } else bytes

                        try { onNalUnit(payload) } catch (e: Exception) {
                            Log.e(TAG, "onNalUnit delivery error: ${e.message}")
                        }
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        Log.e(TAG, "MediaCodec error: ${e.diagnosticInfo}")
                        onError("MediaCodec error: ${e.diagnosticInfo}")
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        Log.i(TAG, "Output format changed: $format")
                    }
                })
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = c.createInputSurface()
                c.start()
            }

            // ── Create VirtualDisplay → feeds encoded frames into codec ───────
            virtualDisplay = projection.createVirtualDisplay(
                "StreamNodeScreen",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )

            running = true
            Log.i(TAG, "start: capture running ✓")

        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            onError("ScreenCaptureEngine start failed: ${e.message}")
            release()
        }
    }

    fun stop() {
        if (!running) return
        Log.i(TAG, "stop")
        running = false
        release()
    }

    private fun release() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null

        try { codec?.stop() }   catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null

        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null

        codecConfigBytes = null

        // Do NOT stop the MediaProjection here — the service owns it
        // and may need it for other operations.
        Log.d(TAG, "release complete")
    }
}
