package com.akdevelopers.streamnode.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import com.akdevelopers.streamnode.analytics.StreamNodeLogger
import com.akdevelopers.streamnode.core.AppConstants
import java.io.File

/**
 * TelemetryReporter — periodically samples device metrics and sends them over
 * the /control WebSocket as {"type":"telemetry", ...} JSON messages.
 *
 * Phase 2 optimizations applied:
 *  1. Adaptive interval — setInterval() lets StreamingService stretch the reporting
 *     cadence based on screen state (60 s → 120 s → 300 s) without restarting.
 *  2. Delta telemetry — only fields that changed beyond their threshold are sent
 *     each tick. A full resync is forced every TELEMETRY_FULL_RESYNC_INTERVAL_MS
 *     (5 min) so the dashboard never drifts out of sync. This reduces per-tick
 *     data from ~800 bytes to ~50-150 bytes for a stable, streaming device.
 *
 * Metrics collected (all fields):
 *   batteryLevel (0-100), charging (bool), signalDbm (rough dBm),
 *   cpuTempC (°C, -1 if unavailable), wifiSsid (string|"N/A"),
 *   usedRamMB, totalRamMB, netType, linkSpeedMbps, screenOn,
 *   streamFps, streamKbps, streamUptimeSec,
 *   voxDropRate, voxEnabled, voxThreshold.
 */
class TelemetryReporter(
    private val context: Context,
    private val sendFn: (String) -> Unit,
    private val intervalMs: Long = AppConstants.TELEMETRY_INTERVAL_SCREEN_ON_MS,
) {
    // ── External metric suppliers ─────────────────────────────────────────────
    @Volatile var getVoxMetrics: (() -> Triple<Double, Boolean, Double>)? = null
    @Volatile var getStreamMetrics: (() -> Triple<Float, Float, Int>)? = null

    private val log     = StreamNodeLogger.forModule("Telemetry")
    private val handler = Handler(Looper.getMainLooper())

    /** Current reporting interval. Updated dynamically via setInterval(). */
    @Volatile private var currentIntervalMs: Long = intervalMs

    // ── Phase 2: Delta state ──────────────────────────────────────────────────
    /** Snapshot of the last values sent, for delta comparison. */
    private var lastBattery:      Int     = -1
    private var lastCharging:     Boolean = false
    private var lastSignal:       Int     = -999
    private var lastCpuTemp:      Float   = Float.MIN_VALUE
    private var lastWifiSsid:     String  = ""
    private var lastUsedRam:      Int     = -1
    private var lastTotalRam:     Int     = -1
    private var lastNetType:      String  = ""
    private var lastLinkSpeed:    Int     = -999
    private var lastScreenOn:     Boolean? = null
    private var lastStreamFps:    Float   = -1f
    private var lastStreamKbps:   Float   = -1f
    private var lastUptime:       Int     = -1
    private var lastVoxDrop:      Double  = -1.0
    private var lastVoxEnabled:   Boolean? = null
    private var lastVoxThreshold: Double  = -1.0
    /** Epoch ms of the last forced full resync. */
    private var lastFullResyncMs: Long    = 0L

    /**
     * Change the telemetry interval at runtime without restarting the reporter.
     * The new interval takes effect on the next scheduled tick.
     * Floor: 10 s to prevent runaway sampling in broken callers.
     */
    fun setInterval(newIntervalMs: Long) {
        currentIntervalMs = newIntervalMs.coerceAtLeast(10_000L)
        log.d("Telemetry interval updated", "intervalMs" to currentIntervalMs)
    }

    fun start() {
        log.i("TelemetryReporter started", "intervalMs" to currentIntervalMs)
        handler.post(reportRunnable)
    }

    fun stop() {
        log.i("TelemetryReporter stopped")
        handler.removeCallbacks(reportRunnable)
    }

    // ── Runnable ──────────────────────────────────────────────────────────────

    private val reportRunnable = object : Runnable {
        override fun run() {
            try {
                val now = System.currentTimeMillis()
                val forceFullResync = (now - lastFullResyncMs) >= AppConstants.TELEMETRY_FULL_RESYNC_INTERVAL_MS
                val payload = buildTelemetry(forceFullResync)
                if (payload != null) {
                    sendFn(payload)
                    if (forceFullResync) {
                        lastFullResyncMs = now
                        log.d("Telemetry full resync sent")
                    } else {
                        log.d("Telemetry delta sent")
                    }
                } else {
                    log.d("Telemetry: no changes — skipped this tick")
                }
            } catch (e: Exception) {
                log.w("Telemetry send failed", "error" to e.message)
            }
            handler.postDelayed(this, currentIntervalMs)
        }
    }

    // ── Telemetry assembly ────────────────────────────────────────────────────

    /**
     * Samples all metrics and returns a JSON string.
     *
     * @param forceFullResync when true, all fields are included regardless of whether
     *   they changed (periodic belt-and-suspenders resync every 5 min).
     * @return JSON string, or null if nothing changed (delta mode, no changes detected).
     */
    private fun buildTelemetry(forceFullResync: Boolean): String? {
        // ── Sample all metrics ────────────────────────────────────────────────
        val bm       = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery  = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging

        val tm     = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val signal = getSignalStrength(tm)
        val cpuTemp  = readCpuTemp()
        val wifiSsid = getWifiSsid(context)

        val runtime    = Runtime.getRuntime()
        val usedRam    = ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)).toInt()
        val totalRam   = (runtime.maxMemory() / (1024 * 1024)).toInt()

        val cm      = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val netCaps = cm.getNetworkCapabilities(cm.activeNetwork)
        val netType = when {
            netCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     == true -> "wifi"
            netCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            netCaps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            else -> "unknown"
        }
        val linkSpeed: Int = if (netType == "wifi") {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    (netCaps?.transportInfo as? WifiInfo)?.linkSpeed ?: -1
                else {
                    @Suppress("DEPRECATION")
                    val wm = context.applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    wm.connectionInfo?.linkSpeed ?: -1
                }
            } catch (_: Exception) { -1 }
        } else -1

        val pm       = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOn = pm.isInteractive

        val voxTriple    = getVoxMetrics?.invoke()
        val streamTriple = getStreamMetrics?.invoke()

        // ── Delta detection ───────────────────────────────────────────────────
        // Determine which fields changed beyond their noise thresholds.
        // On forceFullResync every field is treated as changed.
        val batChanged     = forceFullResync || battery  != lastBattery  || charging != lastCharging
        val sigChanged     = forceFullResync || Math.abs(signal - lastSignal) >= 5
        val tempChanged    = forceFullResync || Math.abs(cpuTemp - lastCpuTemp) >= 1.0f
        val ssidChanged    = forceFullResync || wifiSsid != lastWifiSsid
        val ramChanged     = forceFullResync || Math.abs(usedRam - lastUsedRam) >= 5
        val netChanged     = forceFullResync || netType != lastNetType || linkSpeed != lastLinkSpeed
        val screenChanged  = forceFullResync || screenOn != lastScreenOn
        val streamChanged  = forceFullResync || streamTriple != null && (
            Math.abs((streamTriple.first  - lastStreamFps)  / (lastStreamFps.coerceAtLeast(1f)))  > 0.05f ||
            Math.abs((streamTriple.second - lastStreamKbps) / (lastStreamKbps.coerceAtLeast(1f))) > 0.05f
        )
        val voxChanged     = forceFullResync || voxTriple != null && (
            voxTriple.second != lastVoxEnabled || voxTriple.third != lastVoxThreshold ||
            Math.abs(voxTriple.first - lastVoxDrop) > 0.02
        )
        val uptimeChanged  = forceFullResync || (streamTriple != null && streamTriple.third != lastUptime)

        val anyChanged = batChanged || sigChanged || tempChanged || ssidChanged ||
                         ramChanged || netChanged || screenChanged ||
                         streamChanged || voxChanged || uptimeChanged

        // Nothing changed → skip this tick (save data + server CPU)
        if (!anyChanged) return null

        // ── Update cached snapshot for next-tick delta comparison ─────────────
        if (batChanged)    { lastBattery = battery; lastCharging = charging }
        if (sigChanged)    { lastSignal  = signal }
        if (tempChanged)   { lastCpuTemp = cpuTemp }
        if (ssidChanged)   { lastWifiSsid = wifiSsid }
        if (ramChanged)    { lastUsedRam = usedRam; lastTotalRam = totalRam }
        if (netChanged)    { lastNetType = netType; lastLinkSpeed = linkSpeed }
        if (screenChanged) { lastScreenOn = screenOn }
        if (streamTriple != null) {
            if (streamChanged) { lastStreamFps = streamTriple.first; lastStreamKbps = streamTriple.second }
            if (uptimeChanged) { lastUptime = streamTriple.third }
        }
        if (voxTriple != null && voxChanged) {
            lastVoxDrop = voxTriple.first; lastVoxEnabled = voxTriple.second
            lastVoxThreshold = voxTriple.third
        }

        // ── Build JSON (only changed fields + always-present header fields) ────
        // Pre-built via StringBuilder — avoids JSONObject allocation.
        // The server's handleControl merges delta fields onto ch.telemetry so
        // unchanged fields are preserved from the previous full snapshot.
        return buildString {
            append("{\"type\":\"telemetry\"")
            append(",\"ts\":").append(System.currentTimeMillis())
            // Mark as delta or full so server can decide merge vs replace
            if (!forceFullResync) append(",\"delta\":true")

            if (batChanged) {
                append(",\"batteryLevel\":").append(battery)
                append(",\"charging\":").append(charging)
            }
            if (sigChanged)  append(",\"signalDbm\":").append(signal)
            if (tempChanged) append(",\"cpuTempC\":").append(cpuTemp)
            if (ssidChanged) append(",\"wifiSsid\":").append(jsonStr(wifiSsid))
            if (ramChanged) {
                append(",\"usedRamMB\":").append(usedRam)
                append(",\"totalRamMB\":").append(totalRam)
            }
            if (netChanged) {
                append(",\"netType\":").append(jsonStr(netType))
                append(",\"linkSpeedMbps\":").append(linkSpeed)
            }
            if (screenChanged) append(",\"screenOn\":").append(screenOn)

            if (voxTriple != null && voxChanged) {
                append(",\"voxDropRate\":").append(voxTriple.first)
                append(",\"voxEnabled\":").append(voxTriple.second)
                append(",\"voxThreshold\":").append(voxTriple.third)
            }
            if (streamTriple != null) {
                if (streamChanged) {
                    append(",\"streamFps\":").append(streamTriple.first)
                    append(",\"streamKbps\":").append(streamTriple.second)
                }
                if (uptimeChanged) append(",\"streamUptimeSec\":").append(streamTriple.third)
            }
            append("}")
        }
    }

    // ── Signal strength ───────────────────────────────────────────────────────

    private fun getSignalStrength(tm: TelephonyManager): Int {
        return try {
            (tm.signalStrength?.level ?: 0) * 20
        } catch (_: SecurityException) { 0 }
          catch (_: Exception)         { 0 }
    }

    // ── CPU temperature ───────────────────────────────────────────────────────

    private fun readCpuTemp(): Float {
        for (zone in 0..9) {
            val file = File("/sys/class/thermal/thermal_zone$zone/temp")
            if (!file.exists()) continue
            return try {
                val raw = file.readText().trim().toFloat()
                if (raw > 1000f) raw / 1000f else raw
            } catch (_: Exception) { continue }
        }
        return -1f
    }

    // ── Wi-Fi SSID ────────────────────────────────────────────────────────────

    private fun getWifiSsid(ctx: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork ?: return "N/A"
                val caps    = cm.getNetworkCapabilities(network) ?: return "N/A"
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "N/A"
                val wifiInfo = caps.transportInfo as? WifiInfo ?: return "N/A"
                wifiInfo.ssid.removeSurrounding("\"").takeIf { it.isNotBlank() } ?: "N/A"
            } else {
                @Suppress("DEPRECATION")
                val wm = ctx.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wm.connectionInfo?.ssid?.removeSurrounding("\"") ?: "N/A"
            }
        } catch (_: Exception) { "N/A" }
    }

    // ── JSON string helper ────────────────────────────────────────────────────

    /** Inline JSON string escaper — avoids JSONObject allocation for string values. */
    private fun jsonStr(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"'  -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
