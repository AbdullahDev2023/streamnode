package com.akdevelopers.streamnode.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.akdevelopers.streamnode.R
import com.akdevelopers.streamnode.analytics.Analytics
import com.akdevelopers.streamnode.service.StreamIdentity
import com.akdevelopers.streamnode.service.StreamLogger
import com.akdevelopers.streamnode.service.StreamingService
import com.akdevelopers.streamnode.service.StreamStatus
import com.akdevelopers.streamnode.service.TransportMode
import com.akdevelopers.streamnode.ui.setup.SetupManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MainActivity — live log console only.
 *
 * No buttons, no controls.
 * On launch (after setup is complete) the service auto-starts and begins
 * WebRTC/WebSocket streaming. Every event — ICE negotiation, signaling,
 * mic start/stop, connection state — is appended to the on-screen log.
 *
 * Setup dialogs are handled entirely by SetupActivity (mandatory gate).
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Views
    private lateinit var tvStatusBadge:    TextView
    private lateinit var tvStreamId:       TextView
    private lateinit var tvLog:            TextView
    private lateinit var scrollLog:        ScrollView
    // Phase 5 — transport row
    private lateinit var tvTransportStatus: TextView
    private lateinit var rgTransport:       RadioGroup
    private lateinit var rbTransportAuto:   RadioButton
    private lateinit var rbTransportWebRtc: RadioButton
    private lateinit var rbTransportWs:     RadioButton
    /** Prevents the RadioGroup listener from triggering when we update it programmatically. */
    private var suppressTransportListener = false

    // Log buffer — keep last MAX_LOG_LINES lines to avoid unbounded growth
    private val logBuffer = StringBuilder()
    private var logLineCount = 0
    private val MAX_LOG_LINES = 500

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Gate: if setup not complete, redirect and finish
        if (!allPermsGranted() ||
            SetupManager.autostartStepRequired(this) ||
            !SetupManager.isBatteryExempt(this)) {
            startActivity(Intent().setClassName(
                this, "com.akdevelopers.streamnode.ui.setup.SetupActivity"))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        Analytics.logAppOpen(alreadyRunning = StreamingService.isRunning.value)

        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvStreamId    = findViewById(R.id.tvStreamId)
        tvLog         = findViewById(R.id.tvLog)
        scrollLog     = findViewById(R.id.scrollLog)

        // Phase 5 — transport row
        tvTransportStatus = findViewById(R.id.tvTransportStatus)
        rgTransport       = findViewById(R.id.rgTransport)
        rbTransportAuto   = findViewById(R.id.rbTransportAuto)
        rbTransportWebRtc = findViewById(R.id.rbTransportWebRtc)
        rbTransportWs     = findViewById(R.id.rbTransportWs)

        // Restore the last user-pinned preference so the button is pre-selected
        suppressTransportListener = true
        when (viewModel.savedTransportPreference(this)) {
            "webrtc"    -> rgTransport.check(R.id.rbTransportWebRtc)
            "websocket" -> rgTransport.check(R.id.rbTransportWs)
            else        -> rgTransport.check(R.id.rbTransportAuto)
        }
        suppressTransportListener = false

        // Listen for user taps on the RadioGroup
        rgTransport.setOnCheckedChangeListener { _, checkedId ->
            if (suppressTransportListener) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.rbTransportWebRtc -> "webrtc"
                R.id.rbTransportWs     -> "websocket"
                else                   -> "auto"
            }
            appendLog("🔌 Transport pinned → $mode")
            viewModel.setTransport(this, mode)
        }

        tvStreamId.text = "Stream ID: ${StreamIdentity.getStreamId(this)}"

        appendLog("StreamNode started")
        appendLog("Stream ID: ${StreamIdentity.getStreamId(this)}")
        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLog("Connections will auto-start. Streaming")
        appendLog("begins ONLY when server sends 'start'.")
        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        // ── Observe status → update badge ────────────────────────────────────
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StreamingService.status.collect { status ->
                    applyStatusBadge(status)
                }
            }
        }

        // ── Phase 5: Observe transport mode → update status label ─────────────
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.transportMode.collect { mode ->
                    applyTransportBadge(mode)
                }
            }
        }

        // ── Observe log events → append to console ───────────────────────────
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StreamLogger.events.collect { line ->
                    appendLog(line)
                }
            }
        }

        // ── Observe fetch status message from ViewModel ───────────────────────
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.fetchStatusMessage.collect { msg ->
                    if (!msg.isNullOrBlank()) appendLog(msg)
                }
            }
        }

        // ── Auto-start service if not running ─────────────────────────────────
        if (!StreamingService.isRunning.value) {
            appendLog("Fetching server URL…")
            viewModel.fetchUrlAndAutoConnect(this)
        } else {
            appendLog("Service already running — reconnecting UI")
        }
    }


    // ── Log helpers ────────────────────────────────────────────────────────────

    /**
     * Append a line to the log console.
     * Lines that already carry a [HH:mm:ss] prefix (from StreamingService.log)
     * are written as-is. Plain strings get a timestamp prepended.
     */
    private fun appendLog(raw: String) {
        // Trim buffer if too long
        if (logLineCount >= MAX_LOG_LINES) {
            val text = logBuffer.toString()
            val cutAt = text.indexOf('\n', text.length / 3)
            if (cutAt > 0) {
                logBuffer.clear()
                logBuffer.append(text.substring(cutAt + 1))
                logLineCount = logBuffer.count { it == '\n' }
            }
        }

        val line = if (raw.startsWith("[")) raw
        else {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            "[$ts]  $raw"
        }

        logBuffer.append(line).append('\n')
        logLineCount++

        tvLog.text = logBuffer.toString()
        // Auto-scroll to bottom after layout pass
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ── Status badge ───────────────────────────────────────────────────────────

    private fun applyStatusBadge(status: StreamStatus) {
        val (label, color) = when (status) {
            StreamStatus.STREAMING       -> "● LIVE"            to getColor(R.color.status_live)
            StreamStatus.CONNECTING      -> "● Connecting"      to getColor(R.color.status_connecting)
            StreamStatus.RECONNECTING    -> "● Reconnecting"    to getColor(R.color.status_connecting)
            StreamStatus.CONNECTED_IDLE  -> "● Connected"       to getColor(R.color.accent_purple)
            StreamStatus.CALL_ACTIVE     -> "● Call Live"       to getColor(R.color.status_live)
            StreamStatus.MIC_ERROR       -> "● Mic Error"       to getColor(R.color.status_error)
            StreamStatus.IDLE            -> "● Idle"            to getColor(R.color.status_idle)
        }
        tvStatusBadge.text      = label
        tvStatusBadge.setTextColor(color)
    }

    // ── Phase 5: Transport badge ───────────────────────────────────────────────

    /**
     * Update the transport status TextView and sync the RadioGroup to reflect
     * the live transport without firing the user-change listener.
     *
     * Color coding:
     *  WEBRTC_P2P         → green  — best-case transport
     *  WEBRTC_NEGOTIATING → blue   — ICE in progress
     *  FALLBACK_TRIGGERED → orange — degraded but audio continues
     *  WEBSOCKET_RELAY    → yellow — relay active (user-pinned or settled)
     */
    private fun applyTransportBadge(mode: TransportMode) {
        val (text, color) = when (mode) {
            TransportMode.WEBRTC_P2P         -> "⚡ WebRTC P2P"  to getColor(R.color.status_live)
            TransportMode.WEBRTC_NEGOTIATING -> "🔄 Negotiating" to getColor(R.color.status_connecting)
            TransportMode.FALLBACK_TRIGGERED -> "⚠ WS Fallback"  to 0xFFFF9800.toInt()
            TransportMode.WEBSOCKET_RELAY    -> "📡 WS Relay"     to 0xFFFFCC00.toInt()
        }
        tvTransportStatus.text = text
        tvTransportStatus.setTextColor(color)

        // Reflect live mode in RadioGroup (only when user hasn't pinned a preference)
        suppressTransportListener = true
        when (mode) {
            TransportMode.WEBRTC_P2P,
            TransportMode.WEBRTC_NEGOTIATING -> rgTransport.check(R.id.rbTransportAuto)
            TransportMode.FALLBACK_TRIGGERED  -> { /* keep current selection — auto fallback happened */ }
            TransportMode.WEBSOCKET_RELAY     -> {
                // Only move to WS button if user previously had Auto selected
                // (avoids overriding an explicit WebRTC pin during an ICE restart)
                if (rgTransport.checkedRadioButtonId == R.id.rbTransportAuto) {
                    rgTransport.check(R.id.rbTransportWs)
                }
            }
        }
        suppressTransportListener = false
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun allPermsGranted(): Boolean {
        val base = hasPerm(android.Manifest.permission.RECORD_AUDIO) &&
                   hasPerm(android.Manifest.permission.READ_PHONE_STATE) &&
                   hasPerm(android.Manifest.permission.CAMERA)
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            hasPerm(android.Manifest.permission.POST_NOTIFICATIONS) else true
        return base && notif
    }

    private fun hasPerm(p: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(this, p) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
