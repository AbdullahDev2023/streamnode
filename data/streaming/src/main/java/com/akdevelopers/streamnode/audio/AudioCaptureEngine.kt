package com.akdevelopers.streamnode.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import com.akdevelopers.streamnode.analytics.StreamNodeLogger
import com.akdevelopers.streamnode.util.ByteArrayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Captures microphone audio as raw 16-bit PCM frames.
 * Quality (sample rate, effects, silence gate) is controlled by [AudioQualityConfig].
 *
 *  - Uses VOICE_COMMUNICATION source so hardware AEC/NS/AGC can attach.
 *  - Silence gate: frames whose RMS amplitude falls below [AudioQualityConfig.silenceGateRms]
 *    are dropped — saves significant bandwidth during quiet periods.
 *    Dropped-frame count is exposed via [silenceDropRate] for telemetry / VOX metrics.
 *  - Acoustic effects (AEC, NS, AGC) are attached when the device supports them.
 *
 * When [callMode] is true the engine switches to speakerphone-capture strategy:
 *   - Forces speakerphone ON so both call voices are audible to the microphone.
 *   - Uses MediaRecorder.AudioSource.MIC (never VOICE_COMMUNICATION) so Android
 *     does not mute the recording during telephony focus.
 *   - Restores the original speakerphone state on stop().
 */
class AudioCaptureEngine(
    private val config: AudioQualityConfig = AudioQualityConfig.HIGH_QUALITY,
    private val onFrameReady: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val context: Context? = null,
    private val callMode: Boolean = false
) {
    private val log = StreamNodeLogger.forModule("AudioCapture")
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns:  NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var opusEncoder: OpusEncoderWrapper? = null

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisorJob)
    private var captureJob: Job? = null

    @Volatile private var stopped = false
    private var savedSpeakerphone: Boolean? = null

    // ── Feature 3: VOX silence-gate metrics ──────────────────────────────────
    /** Counts PCM frames dropped by the silence gate since the last [silenceDropRate] read. */
    private val silenceDropCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Returns the number of frames dropped by the VOX silence gate since the last call,
     * then resets the counter. Called by TelemetryReporter for extended metrics (Feature 9).
     */
    val silenceDropRate: Int get() = silenceDropCounter.getAndSet(0)

    // ── Phase 6 Step 27: Silence-aware AudioRecord suspend ────────────────────
    /**
     * Invoked on the main thread when the VOX gate transitions between active audio
     * and sustained silence (or back). Used by ConnectionOrchestrator to send the
     * silence notification protocol to the server (Step 28).
     *
     * true  = silence started (AudioRecord soft-suspended after ~5 s of quiet)
     * false = audio resumed  (AudioRecord restarted on RMS spike)
     */
    var onSilenceStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Ordered list of audio sources to try, from best to most compatible.
     * In [callMode]: MIC first — records room audio (speakerphone output + your voice).
     * Normal mode: UNPROCESSED (API 29+) → MIC → VOICE_COMMUNICATION.
     */
    private fun sourceCandidates(): List<Int> {
        if (callMode) {
            return listOf(MediaRecorder.AudioSource.MIC,
                          MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        }
        val needsEffects = config.enableAec || config.enableNs || config.enableAgc
        return if (needsEffects) {
            listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                   MediaRecorder.AudioSource.MIC)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(MediaRecorder.AudioSource.UNPROCESSED,
                   MediaRecorder.AudioSource.MIC,
                   MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        } else {
            listOf(MediaRecorder.AudioSource.MIC,
                   MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        }
    }

    @AnyThread
    @SuppressLint("MissingPermission")
    fun start() {
        stopped = false
        if (captureJob?.isActive == true) {
            log.w("start: already running")
            return
        }
        log.i("start",
            "sampleRate" to config.sampleRate,
            "frameBytes" to config.frameBytes,
            "opusBitrate" to config.opusBitrate,
            "callMode" to callMode
        )

        val minBuf = AudioRecord.getMinBufferSize(
            config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR) {
            log.e("getMinBufferSize failed", "result" to minBuf)
            onError("AudioRecord not supported on this device"); return
        }
        val bufSize = maxOf(minBuf, config.frameBytes * 8)

        var record: AudioRecord? = null
        for (source in sourceCandidates()) {
            val candidate = AudioRecord(
                source, config.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                log.i("AudioRecord initialized", "source" to source, "bufSize" to bufSize)
                record = candidate; break
            } else {
                log.w("AudioRecord source failed — trying next",
                    "source" to source, "state" to candidate.state)
                candidate.release()
            }
        }
        if (record == null) {
            log.e("ALL audio sources failed")
            onError("AudioRecord init failed on all sources"); return
        }
        log.i("AudioRecord ready", "sessionId" to record.audioSessionId)

        attachAcousticEffects(record.audioSessionId)
        audioRecord = record

        if (callMode && context != null) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedSpeakerphone = am.isSpeakerphoneOn
            if (!am.isSpeakerphoneOn) {
                am.isSpeakerphoneOn = true
                log.i("callMode: speakerphone forced ON")
            }
        }

        if (config.opusBitrate > 0) {
            opusEncoder = OpusEncoderWrapper(
                sampleRate = config.sampleRate,
                bitrate    = config.opusBitrate,
                complexity = config.opusComplexity,
                vbrEnabled = config.vbrEnabled,  // Feature 1: pass VBR flag
            ).also { it.updateFrameSize(config.frameSamples) }
        }

        record.startRecording()
        log.i("startRecording() called ✓")
        captureJob = scope.launch { captureLoop(record) }
    }

    @WorkerThread
    private suspend fun captureLoop(record: AudioRecord) {
        // Phase 2 OPT: acquire PCM buffer from the pool so that repeated mic restarts
        // (quality changes, call-mode switches, ERROR_DEAD_OBJECT recoveries) reuse the
        // same ByteArray instead of allocating a fresh one each time.
        val buffer = ByteArrayPool.acquire(config.frameBytes)
        var retries = 0

        // Phase 6 Step 27: silence-aware soft-suspend state
        // After ~5 s of consecutive silence (VOX active), stop() AudioRecord to release
        // the DSP pipeline and save ~10 mA.  Poll every 50 ms with READ_NON_BLOCKING
        // to detect audio resumption — resume via startRecording() with zero re-allocation.
        var consecutiveSilenceFrames   = 0
        val silenceSuspendFrames       = (5_000 / config.frameMs.coerceAtLeast(1))
        var audioRecordSuspended       = false

        log.d("captureLoop started",
            "silenceSuspendFrames" to silenceSuspendFrames,
            "voxEnabled" to (config.silenceGateRms > 0.0))

        try {
            while (coroutineContext.isActive) {
                val activeRecord = audioRecord ?: break

                // ── Phase 6: suspended path — poll for RMS spike every 50 ms ─────────
                if (audioRecordSuspended) {
                    delay(50)
                    if (!coroutineContext.isActive) break
                    // Brief startRecording to grab a probe frame
                    activeRecord.startRecording()
                    val probeRead = activeRecord.read(buffer, 0, config.frameBytes, AudioRecord.READ_NON_BLOCKING)
                    if (probeRead == config.frameBytes && rmsOf(buffer) >= config.silenceGateRms) {
                        // Sound detected — resume normal loop
                        audioRecordSuspended = false
                        consecutiveSilenceFrames = 0
                        mainHandler.post { onSilenceStateChanged?.invoke(false) }
                        log.i("AudioRecord soft-resumed — audio detected")
                        // FIX: forward the probe frame before falling through.
                        // Previously this first frame was silently discarded, creating a
                        // 1-frame (~20 ms) click/discontinuity at every voice onset after silence.
                        val probeFrame = opusEncoder?.encode(buffer) ?: buffer.copyOf()
                        onFrameReady(probeFrame)
                        // Fall through to READ_BLOCKING path below (record is already started)
                    } else {
                        // Still silent — stop() again and keep polling
                        activeRecord.stop()
                        continue
                    }
                }

                val read = activeRecord.read(buffer, 0, config.frameBytes, AudioRecord.READ_BLOCKING)
                when {
                    read == config.frameBytes -> {
                        retries = 0
                        if (config.silenceGateRms <= 0.0 || rmsOf(buffer) >= config.silenceGateRms) {
                            // Active audio frame — reset silence counter and forward
                            consecutiveSilenceFrames = 0
                            val frame = opusEncoder?.encode(buffer) ?: buffer.copyOf()
                            onFrameReady(frame)
                        } else {
                            // Frame gated by VOX — count it for telemetry
                            silenceDropCounter.incrementAndGet()
                            consecutiveSilenceFrames++
                            // Phase 6: soft-suspend after sustained silence (VOX must be active)
                            if (config.silenceGateRms > 0.0 &&
                                consecutiveSilenceFrames >= silenceSuspendFrames &&
                                !audioRecordSuspended) {
                                activeRecord.stop()
                                audioRecordSuspended = true
                                consecutiveSilenceFrames = 0
                                mainHandler.post { onSilenceStateChanged?.invoke(true) }
                                log.i("AudioRecord soft-suspended after ~5s silence")
                            }
                        }
                    }
                    read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        log.e("ERROR_DEAD_OBJECT in captureLoop", "retries" to retries)
                        if (!coroutineContext.isActive) break
                        if (retries++ < 5) {
                            delay(800L * retries)
                            restartRecord(activeRecord)
                        } else {
                            onError("AudioRecord dead after $retries retries"); break
                        }
                    }
                    read < 0 -> {
                        if (coroutineContext.isActive) {
                            log.e("AudioRecord read error", "code" to read)
                            onError("AudioRecord read error: $read")
                        } else {
                            log.d("read=$read after intentional stop — clean exit")
                        }
                        break
                    }
                }
            }
        } finally {
            // Phase 2 OPT: return the PCM buffer to the pool now that the loop has
            // exited and no other code holds a reference to it. The pool caps at 8
            // entries so this never causes unbounded memory growth.
            ByteArrayPool.release(buffer)
            // Phase 6: if the coroutine was cancelled while AudioRecord was soft-suspended,
            // fire the silence-end callback so ConnectionOrchestrator sends the correct
            // silence=false notification and the dashboard doesn't stay "silenced" forever.
            if (audioRecordSuspended) {
                mainHandler.post { onSilenceStateChanged?.invoke(false) }
            }
            log.d("captureLoop exited — PCM buffer returned to pool",
                "isActive" to coroutineContext.isActive)
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartRecord(old: AudioRecord) {
        old.stop(); old.release()
        audioRecord = null
        val minBuf = AudioRecord.getMinBufferSize(
            config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, config.frameBytes * 8)
        var record: AudioRecord? = null
        for (source in sourceCandidates()) {
            val candidate = AudioRecord(
                source, config.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                record = candidate; break
            } else { candidate.release() }
        }
        if (record == null) {
            log.e("restartRecord: all sources failed — captureLoop will exit cleanly")
            return
        }
        attachAcousticEffects(record.audioSessionId)
        if (callMode && context != null) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (!am.isSpeakerphoneOn) {
                am.isSpeakerphoneOn = true
                log.i("restartRecord[callMode]: speakerphone re-enabled")
            }
        }
        audioRecord = record
        record.startRecording()
        log.i("restartRecord: AudioRecord restarted ✓", "sessionId" to record.audioSessionId)
    }

    private fun attachAcousticEffects(sessionId: Int) {
        if (config.enableAec && AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sessionId)?.also {
                it.enabled = true
                log.d("AEC attached", "sessionId" to sessionId)
            }
        }
        if (config.enableNs && NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(sessionId)?.also {
                it.enabled = true
                log.d("NoiseSuppressor attached", "sessionId" to sessionId)
            }
        }
        if (config.enableAgc && AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(sessionId)?.also {
                it.enabled = true
                log.d("AGC attached", "sessionId" to sessionId)
            }
        }
    }

    /**
     * Root-mean-square of a 16-bit little-endian mono PCM byte buffer.
     * The low byte is masked with 0xFF before OR to prevent sign-extension corruption.
     */
    private fun rmsOf(buf: ByteArray): Double {
        var sum = 0.0
        val shorts = buf.size / 2
        for (i in 0 until shorts) {
            val s = ((buf[i * 2].toInt() and 0xFF) or (buf[i * 2 + 1].toInt() shl 8)).toShort()
            sum += s * s
        }
        return sqrt(sum / shorts)
    }

    fun stop() {
        stopped = true
        captureJob?.cancel()
        captureJob = null
        aec?.release(); aec = null
        ns?.release();  ns  = null
        agc?.release(); agc = null
        opusEncoder?.release(); opusEncoder = null
        audioRecord?.apply { stop(); release() }
        audioRecord = null
        savedSpeakerphone?.let { wasOn ->
            if (callMode && context != null) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.isSpeakerphoneOn = wasOn
                log.i("stop[callMode]: speakerphone restored", "restored" to wasOn)
            }
            savedSpeakerphone = null
        }
        log.i("stop: mic stopped")
    }

    fun release() {
        stop()
        supervisorJob.cancel()
        log.i("release: engine fully released")
    }
}
