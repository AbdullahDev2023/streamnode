package com.akdevelopers.streamnode.service

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.akdevelopers.streamnode.analytics.Analytics
import com.akdevelopers.streamnode.analytics.CrashManager
import com.akdevelopers.streamnode.audio.AudioCaptureEngine
import com.akdevelopers.streamnode.audio.AudioQualityConfig
import com.akdevelopers.streamnode.audio.AudioQualityPreset
import com.akdevelopers.streamnode.audio.InternalAudioCaptureEngine
import com.akdevelopers.streamnode.core.AppConstants
import com.akdevelopers.streamnode.system.PhoneCallMonitor
import java.util.concurrent.atomic.AtomicInteger

/**
 * MicOrchestrator — owns the microphone session lifecycle.
 *
 * Manages:
 *  - [AudioCaptureEngine]  — PCM/Opus capture and encoding
 *  - [PhoneCallMonitor]    — pauses/resumes capture during phone calls
 *  - Exponential-backoff restart on hardware errors
 *  - Periodic metrics collection (frames/s, kbps, uptime)
 *
 * StreamingService drives start/stop; this class handles all the retry,
 * phone-call, and metrics complexity that previously inflated the service.
 *
 * Callbacks (all main-thread):
 *   onFrameReady     — encoded frame ready to be forwarded to the WS
 *   onStatusChange   — mic-driven status update (STREAMING / MIC_ERROR / CONNECTED_IDLE)
 *   onMetrics        — periodic (fps, kbps, uptimeSec) snapshot
 */
class MicOrchestrator(
    private val context:      Context,
    private val config:       AudioQualityConfig,
    private val startEpochMs: Long,
    private val serverUrlHost: String = ""
) {
    companion object {
        private const val TAG = "AC_MicOrch"
        private const val STEP_UP_HYSTERESIS_MS = 30_000L   // Phase 6 Step 29
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onFrameReady:   ((ByteArray) -> Unit)? = null
    var onStatusChange: ((StreamStatus) -> Unit)? = null
    var onMetrics: ((fps: Float, kbps: Float, uptimeSec: Int) -> Unit)? = null
    /**
     * Phase 6 Step 28: Fired on the main thread when the VOX silence gate transitions
     * between silence and active audio. StreamingService wires this to send the
     * silence notification protocol JSON to the server over /control.
     *
     * true  = sustained silence detected (~5 s), AudioRecord soft-suspended
     * false = audio resumed, AudioRecord restarted
     */
    var onSilenceStateChanged: ((Boolean) -> Unit)? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var captureEngine:    AudioCaptureEngine?         = null
    private var phoneCallMonitor: PhoneCallMonitor?           = null
    /** Feature 4 — internal audio capture engine (API 29+). */
    private var internalEngine:   InternalAudioCaptureEngine? = null
    private var restartAttempt    = 0
    @Volatile private var released = false
    /** True while a phone call is active — drives call-mode AudioCaptureEngine. */
    @Volatile private var inCallMode = false
    /** Current quality config — may be swapped at runtime via setQuality(). */
    @Volatile private var currentConfig: AudioQualityConfig = config
    private val frameCounter      = AtomicInteger(0)
    private var bytesInWindow     = 0L
    private val handler           = Handler(Looper.getMainLooper())

    // ── Phase 6 Step 29: Adaptive Bitrate state ───────────────────────────────
    /**
     * Timestamp of the last quality step-UP (i.e. quality improvement).
     * Step-ups are gated by a 30 s hysteresis window (STEP_UP_HYSTERESIS_MS) to
     * prevent oscillation on fluctuating links. Step-downs are always immediate.
     */
    private var lastRttStepUpMs = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start microphone capture.
     * Fires [onStatusChange](STREAMING) once the first frame flows.
     * @return false if capture is already active.
     */
    fun start(): Boolean {
        if (captureEngine != null) { Log.w(TAG, "start: already active"); return false }
        released       = false
        restartAttempt = 0
        frameCounter.set(0); bytesInWindow = 0L

        Analytics.setStreamingActiveUserProperty(true)
        Analytics.setQualityUserProperty(config.preset.name)
        Analytics.logStreamStart(
            quality = config.preset.name,
            urlHost = serverUrlHost
        )

        phoneCallMonitor = PhoneCallMonitor(
            context       = context,
            onCallStarted = {
                Log.i(TAG, "PhoneCallMonitor: call started — switching to call-mode capture")
                switchToCallMode()
            },
            onCallEnded   = {
                Log.i(TAG, "PhoneCallMonitor: call ended — restoring normal capture")
                switchToNormalMode()
            }
        ).also { it.register() }

        spawnEngine()
        schedulePeriodicMetrics()
        return true
    }

    /**
     * Stop microphone capture immediately. Does NOT release the AudioRecord coroutine scope —
     * call [release] for final service teardown.
     *
     * NOTE: does NOT emit a status update. The caller (StreamingService) decides the
     * post-stop status because it knows the current WebSocket state.
     */
    fun stop() {
        handler.removeCallbacks(metricsRunnable)
        captureEngine?.stop()
        captureEngine    = null
        // Feature 4 — stop internal audio engine if active
        internalEngine?.stop()
        internalEngine   = null
        phoneCallMonitor?.unregister()
        phoneCallMonitor = null
        restartAttempt   = 0

        Analytics.setStreamingActiveUserProperty(false)
        Analytics.logStreamStop()
        Log.i(TAG, "stop: mic stopped")
    }

    /**
     * Full teardown — stops capture and releases AudioRecord's coroutine scope.
     * Must be called when the service is being destroyed, never for a pause/resume cycle.
     */
    fun release() {
        released = true
        handler.removeCallbacks(metricsRunnable)
        phoneCallMonitor?.unregister()
        phoneCallMonitor = null
        restartAttempt   = 0
        // Feature 4 — tear down internal engine before releasing capture engine
        internalEngine?.stop()
        internalEngine = null
        // release() calls stop() + supervisorJob.cancel() inside AudioCaptureEngine.
        captureEngine?.release()
        captureEngine = null
        Log.i(TAG, "release: engine released")
    }

    val isActive: Boolean get() = captureEngine != null

    // ── Feature 3: VOX metrics supplier for TelemetryReporter ────────────────
    /**
     * Returns (framesDroppedRate 0.0–1.0, voxEnabled, voxThresholdRms).
     * [AudioCaptureEngine.silenceDropRate] resets its counter on each call, so calling
     * this once per telemetry interval gives the drop ratio for that window.
     */
    fun voxMetrics(): Triple<Double, Boolean, Double> {
        val dropped   = captureEngine?.silenceDropRate ?: 0
        val threshold = currentConfig.silenceGateRms
        // Use the expected frames-per-window as the total (denominator) rather than
        // dropped + windowFrames — the old formula shrank the denominator when dropped
        // was large and inflated the ratio. Using the window ceiling as total gives a
        // true ratio: dropped / expected_total, capped to 1.0 for safety.
        val windowFrames = (60_000L / currentConfig.frameMs.toLong()).toInt().coerceAtLeast(1)
        val rate = (dropped.toDouble() / windowFrames).coerceIn(0.0, 1.0)
        return Triple(rate, threshold > 0.0, threshold)
    }

    // ── Phase 6 Step 29: Adaptive Bitrate Ladder ──────────────────────────────

    /**
     * Automatically adjust audio quality based on measured round-trip time.
     *
     * Three-tier ladder:
     *  RTT <  150 ms                   → HIGH_QUALITY (192 kbps)
     *  RTT  150–400 ms or short blip   → MEDIUM       (96 kbps)
     *  RTT > 400 ms  (sustained poor)  → LOW          (32 kbps)
     *
     * Quality degradation (step-down) is applied immediately.
     * Quality improvement (step-up)   is gated by a 30 s hysteresis window to
     * prevent oscillation on fluctuating links.
     *
     * Opt-in: only runs when [AppConstants.PREF_ADAPTIVE_BITRATE] = true (default).
     * Call-mode engines are excluded — CALL_CAPTURE config is always fixed.
     *
     * Safe to call from any thread (marshalled onto the main thread internally).
     *
     * @param rtt  Latest measured round-trip time in milliseconds (ping → pong).
     */
    fun autoQualityFromRtt(rtt: Long) {
        handler.post {
            if (released || inCallMode) return@post
            // Opt-in check — default true so it activates out-of-the-box
            val adaptiveEnabled = context.getSharedPreferences(
                com.akdevelopers.streamnode.core.AppConstants.PREFS_FILE, 0
            ).getBoolean(com.akdevelopers.streamnode.core.AppConstants.PREF_ADAPTIVE_BITRATE, true)
            if (!adaptiveEnabled) return@post

            val targetPreset = when {
                rtt > 400L -> AudioQualityPreset.LOW
                rtt > 150L -> AudioQualityPreset.MEDIUM
                else       -> AudioQualityPreset.HIGH_QUALITY
            }
            val currentPreset = currentConfig.preset
            if (targetPreset == currentPreset) return@post

            // ordinal: HIGH_QUALITY=0, MEDIUM=1, LOW=2
            // step-down = ordinal increases (quality worsens) → immediate
            // step-up   = ordinal decreases (quality improves) → 30 s hysteresis
            if (targetPreset.ordinal > currentPreset.ordinal) {
                android.util.Log.i("AC_MicOrch",
                    "autoQualityFromRtt: step-DOWN ${currentPreset.name} → ${targetPreset.name} (rtt=${rtt}ms)")
                setQuality(targetPreset)
            } else {
                val now = System.currentTimeMillis()
                if (now - lastRttStepUpMs >= STEP_UP_HYSTERESIS_MS) {
                    lastRttStepUpMs = now
                    android.util.Log.i("AC_MicOrch",
                        "autoQualityFromRtt: step-UP ${currentPreset.name} → ${targetPreset.name} (rtt=${rtt}ms)")
                    setQuality(targetPreset)
                }
            }
        }
    }

    /**
     * Hot-swap the sample rate at runtime without stopping the stream.
     * Rebuilds the AudioQualityConfig with the new sample rate and restarts the engine.
     * Supported values: 8000, 16000, 32000, 48000 Hz.
     */
    fun setSampleRate(sampleRate: Int) {
        handler.post {
            if (released) return@post
            val validRates = setOf(8_000, 16_000, 32_000, 48_000)
            if (sampleRate !in validRates) {
                Log.w(TAG, "setSampleRate: invalid rate $sampleRate — ignored")
                return@post
            }
            if (sampleRate == currentConfig.sampleRate) {
                Log.d(TAG, "setSampleRate: $sampleRate already active, skipping")
                return@post
            }
            Log.i(TAG, "setSampleRate: ${currentConfig.sampleRate} Hz → $sampleRate Hz")
            currentConfig = currentConfig.copy(sampleRate = sampleRate)
            if (captureEngine != null && !inCallMode) {
                captureEngine?.stop()
                captureEngine = null
                restartAttempt = 0
                spawnEngine(callMode = false)
            }
        }
    }

    /**
     * Safe to call from any thread — marshalled onto the main thread internally.
     * During a phone call the call-capture config takes priority; the new preset
     * will take effect when the call ends and normal capture resumes.
     */
    fun setQuality(preset: AudioQualityPreset) {
        handler.post {
            if (released) return@post
            val newConfig = AudioQualityConfig.fromPreset(preset)
            if (newConfig == currentConfig) {
                Log.d(TAG, "setQuality: preset=${preset.name} already active, skipping")
                return@post
            }
            Log.i(TAG, "setQuality: ${currentConfig.preset.name} → ${preset.name} (${newConfig.opusBitrate/1000} kbps)")
            currentConfig = newConfig
            // Only restart the engine if we're not in call mode
            // (call mode always uses CALL_CAPTURE regardless of currentConfig).
            if (captureEngine != null && !inCallMode) {
                captureEngine?.stop()
                captureEngine = null
                restartAttempt = 0
                spawnEngine(callMode = false)
            }
        }
    }

    /**
     * Feature 1 — Apply individual audio parameters (VBR, frame size, sample rate) at runtime.
     *
     * This is a fine-grained companion to [setQuality]: while setQuality replaces the entire
     * config from a named preset, applyFeatureConfig patches only the parameters that changed.
     * Useful when the dashboard sends a `feature_config` or `set_audio_config` message without
     * changing the overall quality preset label.
     *
     * Safe to call from any thread. Engine is restarted only if the config actually changed
     * and a capture engine is currently active.
     *
     * @param vbrEnabled  true = Opus VBR (default); false = CBR (metered/restricted links)
     * @param frameSizeMs Opus frame duration in ms. Valid values: 2, 5, 10, 20, 40, 60.
     *                    Smaller = lower latency; larger = better compression.
     */
    fun applyFeatureConfig(
        vbrEnabled:  Boolean = currentConfig.vbrEnabled,
        frameSizeMs: Int     = currentConfig.frameMs,
    ) {
        handler.post {
            if (released) return@post
            val newConfig = currentConfig.copy(
                vbrEnabled = vbrEnabled,
                frameMs    = frameSizeMs.coerceIn(2, 60),
            )
            if (newConfig == currentConfig) {
                Log.d(TAG, "applyFeatureConfig: no change, skipping engine restart")
                return@post
            }
            Log.i(TAG, "applyFeatureConfig: " +
                "vbr=${currentConfig.vbrEnabled}→${newConfig.vbrEnabled}  " +
                "frameMs=${currentConfig.frameMs}→${newConfig.frameMs}")
            currentConfig = newConfig
            if (captureEngine != null && !inCallMode) {
                captureEngine?.stop()
                captureEngine = null
                restartAttempt = 0
                spawnEngine(callMode = false)
            }
        }
    }

    // ── Feature 8: Advanced Audio Config ─────────────────────────────────────

    /**
     * Replace the entire audio config at runtime with a fully custom set of parameters.
     *
     * This is the backing implementation for the "Advanced Audio Config" dashboard panel
     * (Feature 8). Unlike [setQuality] (which selects a named preset) or [applyFeatureConfig]
     * (which patches only VBR/frameMs), this method replaces [currentConfig] wholesale with
     * a [AudioQualityConfig.custom] instance built from the dashboard's individual controls:
     *   sample rate, bitrate, frame size, VBR, Opus complexity, AGC/NS/AEC, VOX threshold.
     *
     * Safe to call from any thread — marshalled onto the main thread internally.
     * The engine is restarted only if the new config differs from the current one and a
     * capture engine is currently active.
     *
     * @param config  Complete [AudioQualityConfig] produced by [AudioQualityConfig.custom].
     */
    fun applyAudioConfig(config: AudioQualityConfig) {
        handler.post {
            if (released) return@post
            if (config == currentConfig) {
                Log.d(TAG, "applyAudioConfig: no change — skipping engine restart")
                return@post
            }
            Log.i(TAG, "applyAudioConfig: " +
                "sr=${config.sampleRate} br=${config.opusBitrate} fms=${config.frameMs} " +
                "vbr=${config.vbrEnabled} cmp=${config.opusComplexity} " +
                "agc=${config.enableAgc} ns=${config.enableNs} aec=${config.enableAec} " +
                "vox=${config.silenceGateRms}")
            currentConfig = config
            if (captureEngine != null && !inCallMode) {
                captureEngine?.stop()
                captureEngine = null
                restartAttempt = 0
                spawnEngine(callMode = false)
            }
        }
    }

    // ── Feature 3: VOX / Silence Gate ─────────────────────────────────────────

    /**
     * Enable or disable the VOX silence gate at runtime without stopping the stream.
     *
     * When enabled, [AudioCaptureEngine] drops PCM frames whose RMS amplitude is below
     * [threshold], saving 60–80 % of bandwidth during quiet periods.
     * When disabled (enabled = false OR threshold = 0.0) every frame is transmitted.
     *
     * Safe to call from any thread. Engine is restarted only if the config actually changed.
     *
     * @param enabled   true = gate active; false = transmit all frames
     * @param threshold RMS value (0–32767 range for 16-bit PCM). Typical speech ≈ 300–800;
     *                  silence < 100. A threshold of 150 is a safe starting point.
     *                  Ignored when [enabled] = false.
     */
    fun setVox(enabled: Boolean, threshold: Double) {
        handler.post {
            if (released) return@post
            val newRms = if (enabled) threshold.coerceAtLeast(0.0) else 0.0
            if (newRms == currentConfig.silenceGateRms) {
                Log.d(TAG, "setVox: no change (silenceGateRms=$newRms) — skipping engine restart")
                return@post
            }
            Log.i(TAG, "setVox: " +
                "enabled=$enabled  " +
                "threshold=$threshold  " +
                "silenceGateRms: ${currentConfig.silenceGateRms} → $newRms")
            currentConfig = currentConfig.copy(silenceGateRms = newRms)
            // Hot-swap: restart the engine only if it is currently active and not in call mode.
            // Call mode uses CALL_CAPTURE config which is always gate-off — skip restart there.
            if (captureEngine != null && !inCallMode) {
                captureEngine?.stop()
                captureEngine = null
                restartAttempt = 0
                spawnEngine(callMode = false)
            }
        }
    }

    // ── Feature 4: Internal Audio / Media Capture ─────────────────────────────

    /**
     * Toggle internal audio capture (API 29+) at runtime without stopping the stream.
     *
     * When [enabled] = true the phone's media/game audio output is captured via
     * [InternalAudioCaptureEngine] using [AudioPlaybackCaptureConfiguration].
     *
     * When [mixWithMic] = true  — both mic and internal audio frames are forwarded
     *                             simultaneously (time-division interleaving).
     * When [mixWithMic] = false — mic capture is paused; only internal audio flows.
     *
     * Silently ignored on devices below API 29.
     *
     * @param enabled      true = start internal capture; false = stop it
     * @param mixWithMic   true = keep mic running alongside internal audio
     * @param projection   live [MediaProjection] token from [StreamingService]
     */
    fun applyInternalAudio(
        enabled:    Boolean,
        mixWithMic: Boolean,
        projection: android.media.projection.MediaProjection?,
    ) {
        handler.post {
            if (released) return@post

            if (!enabled) {
                // ── Disable path ──────────────────────────────────────────────
                if (internalEngine == null) return@post   // nothing to do
                Log.i(TAG, "applyInternalAudio: disabling internal audio capture")
                internalEngine?.stop(); internalEngine = null
                // Restore mic if it was paused in mic-off mode
                if (captureEngine == null && !inCallMode) {
                    Log.i(TAG, "applyInternalAudio: restoring mic after internal-only mode")
                    spawnEngine(callMode = false)
                }
                return@post
            }

            // ── API guard ─────────────────────────────────────────────────────
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Log.w(TAG, "applyInternalAudio: requires API 29+ — ignored on API ${Build.VERSION.SDK_INT}")
                return@post
            }
            if (projection == null) {
                Log.w(TAG, "applyInternalAudio: projection is null — cannot start internal capture")
                return@post
            }

            // ── Enable path ───────────────────────────────────────────────────
            Log.i(TAG, "applyInternalAudio: enabled=$enabled mixWithMic=$mixWithMic")

            // Stop any existing internal engine before (re)starting
            internalEngine?.stop(); internalEngine = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                internalEngine = InternalAudioCaptureEngine(
                    mediaProjection = projection,
                    config          = currentConfig,
                    onFrameReady    = { frame ->
                        frameCounter.incrementAndGet()
                        bytesInWindow += frame.size
                        onFrameReady?.invoke(frame)
                    },
                    onError = { msg ->
                        Log.e(TAG, "InternalAudioCaptureEngine error: $msg")
                        onStatusChange?.invoke(StreamStatus.MIC_ERROR)
                    },
                )
                internalEngine?.start()
            }

            // If NOT mixing with mic, pause the mic engine to avoid double-send
            if (!mixWithMic && !inCallMode) {
                Log.i(TAG, "applyInternalAudio: mic-off mode — pausing mic engine")
                captureEngine?.stop(); captureEngine = null
            } else if (mixWithMic && captureEngine == null && !inCallMode) {
                Log.i(TAG, "applyInternalAudio: mix mode — ensuring mic engine is running")
                spawnEngine(callMode = false)
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun spawnEngine(callMode: Boolean = false) {
        val engineConfig = if (callMode) AudioQualityConfig.CALL_CAPTURE else currentConfig
        // Phase 6 Step 28: Create engine first, wire callbacks BEFORE start() so
        // onSilenceStateChanged is set before the captureLoop can invoke it.
        val engine = AudioCaptureEngine(
            config       = engineConfig,
            onFrameReady = { frame ->
                frameCounter.incrementAndGet()
                bytesInWindow += frame.size
                onFrameReady?.invoke(frame)
            },
            onError   = { msg -> onError(msg) },
            context   = context,
            callMode  = callMode
        )
        // Wire silence state changes (main-thread-dispatched by AudioCaptureEngine)
        engine.onSilenceStateChanged = { active ->
            // already on main thread from AudioCaptureEngine.mainHandler.post
            onSilenceStateChanged?.invoke(active)
        }
        captureEngine = engine
        engine.start()
        val status = if (callMode) StreamStatus.CALL_ACTIVE else StreamStatus.STREAMING
        onStatusChange?.invoke(status)
    }

    /**
     * Switches to call-capture mode: tears down the current engine and starts a new one
     * configured for speakerphone MIC recording so both voices are captured during a call.
     */
    private fun switchToCallMode() {
        if (released) return
        inCallMode = true
        captureEngine?.stop()
        captureEngine = null
        Analytics.logPhoneCallStarted()
        spawnEngine(callMode = true)
    }

    /**
     * Restores normal capture after a call ends.  Uses scheduleRestart so the same
     * exponential-backoff logic applies if AudioRecord needs time to release.
     */
    private fun switchToNormalMode() {
        if (released) return
        inCallMode = false
        captureEngine?.stop()
        captureEngine = null
        Analytics.logPhoneCallEnded()
        scheduleRestart("call ended")
    }

    private fun onError(msg: String) {
        CrashManager.recordNonFatal("AudioCaptureEngine error: $msg", "quality=${config.preset.name}")
        Analytics.logMicError(msg, config.preset.name)
        onStatusChange?.invoke(StreamStatus.MIC_ERROR)
        scheduleRestart(msg)
    }

    private fun scheduleRestart(reason: String) {
        captureEngine?.stop(); captureEngine = null
        val delayMs = minOf(3_000L shl restartAttempt, 30_000L)
        restartAttempt++
        Log.i(TAG, "scheduleRestart: attempt=$restartAttempt delay=${delayMs}ms reason=$reason")
        Analytics.logMicRestartScheduled(restartAttempt, delayMs)

        handler.postDelayed({
            // Guard: don't restart if stop()/release() was called while we were waiting.
            if (!released && captureEngine == null) {
                spawnEngine(callMode = inCallMode)
            }
        }, delayMs)
    }

    private fun schedulePeriodicMetrics() {
        // BUG FIX #4: Handler.postDelayed(Runnable, Object, long) is API 28+.
        // minSdk = 26 (Android 8.0/8.1) — use the API 1 two-arg overload instead.
        // We guard double-scheduling by checking the released flag and calling
        // removeCallbacks(metricsRunnable) before each re-post.
        handler.removeCallbacks(metricsRunnable)
        handler.postDelayed(metricsRunnable, AppConstants.METRICS_INTERVAL_MS)
    }

    private val metricsRunnable: Runnable = object : Runnable {
        override fun run() {
            if (released || captureEngine == null) return
            val frames    = frameCounter.getAndSet(0)
            val bytes     = bytesInWindow.also { bytesInWindow = 0L }
            val windowSec = AppConstants.METRICS_INTERVAL_MS / 1_000f
            val fps       = frames / windowSec
            val kbps      = (bytes * 8f) / (windowSec * 1_000f)
            val uptimeSec = ((System.currentTimeMillis() - startEpochMs) / 1_000).toInt()
            Analytics.logRealtimeSnapshot(fps, kbps, uptimeSec)
            onMetrics?.invoke(fps, kbps, uptimeSec)
            // Re-schedule using the API-1 overload (no Object token parameter).
            handler.postDelayed(this, AppConstants.METRICS_INTERVAL_MS)
        }
    }
}
