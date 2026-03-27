package com.akdevelopers.streamnode.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * InternalAudioCaptureEngine — captures the phone's internal audio output
 * (music, media, games) using [AudioPlaybackCaptureConfiguration].
 *
 * Requirements:
 *  - API 29+ (Android 10, Q)
 *  - A valid [MediaProjection] token held by StreamingService
 *  - RECORD_AUDIO permission (already held for mic capture)
 *
 * No additional manifest permission is needed beyond RECORD_AUDIO for
 * USAGE_MEDIA / USAGE_GAME / USAGE_UNKNOWN targets. CAPTURE_AUDIO_OUTPUT
 * is only required for OEM system audio / USAGE_VOICE_COMMUNICATION.
 *
 * Thread-safety: [start] and [stop] are safe to call from any thread.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class InternalAudioCaptureEngine(
    private val mediaProjection: MediaProjection,
    private val config:          AudioQualityConfig,
    private val onFrameReady:    (ByteArray) -> Unit,
    private val onError:         (String) -> Unit,
) {
    companion object { private const val TAG = "AC_IntAudioEng" }

    private var audioRecord: AudioRecord?        = null
    private var opusEncoder: OpusEncoderWrapper? = null
    private val scope        = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob:  Job?                = null

    /** Start capturing internal audio. No-op if already started. */
    fun start() {
        if (captureJob?.isActive == true) { Log.w(TAG, "start: already running"); return }
        Log.i(TAG, "start: sampleRate=${config.sampleRate} bitrate=${config.opusBitrate}")
        try {
            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(config.sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR) {
                onError("getMinBufferSize failed: $minBuf"); return
            }

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, config.frameBytes * 8))
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                val msg = "AudioRecord failed to init (state=${audioRecord?.state})"
                Log.e(TAG, msg); audioRecord?.release(); audioRecord = null
                onError(msg); return
            }

            opusEncoder = OpusEncoderWrapper(
                sampleRate = config.sampleRate,
                bitrate    = config.opusBitrate,
                complexity = config.opusComplexity,
            ).also { it.updateFrameSize(config.frameSamples) }

            audioRecord!!.startRecording()
            captureJob = scope.launch { captureLoop() }
            Log.i(TAG, "Internal audio capture started")
        } catch (e: Exception) {
            Log.e(TAG, "start: exception — ${e.message}", e)
            releaseResources()
            onError("InternalAudioCaptureEngine start failed: ${e.message}")
        }
    }

    /** Stop capture and release all hardware resources. Safe to call multiple times. */
    fun stop() {
        Log.i(TAG, "stop")
        captureJob?.cancel(); captureJob = null
        releaseResources()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun captureLoop() {
        val buf = ByteArray(config.frameBytes)
        while (coroutineContext.isActive) {
            val read = audioRecord?.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING) ?: break
            when {
                read == buf.size -> {
                    val encoded = opusEncoder?.encode(buf) ?: buf.copyOf()
                    onFrameReady(encoded)
                }
                read == 0 -> { /* device muted / silent — keep looping */ }
                read < 0  -> {
                    val msg = "AudioRecord.read error: $read"
                    Log.e(TAG, msg); onError(msg); break
                }
                else -> Log.v(TAG, "captureLoop: short read ($read < ${buf.size})")
            }
        }
        Log.d(TAG, "captureLoop: exited")
    }

    private fun releaseResources() {
        try { audioRecord?.stop()    } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { opusEncoder?.release() } catch (_: Exception) {}
        opusEncoder = null
    }
}
