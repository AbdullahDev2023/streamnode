package com.akdevelopers.streamnode.analytics

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * StreamNodeLogger — highest-level structured logger for the StreamNode Android app.
 *
 * Every log call is emitted in two forms simultaneously:
 *  1. Android Logcat  — human-readable, tagged "AC/<module>"
 *  2. In-memory ring  — last [RING_CAPACITY] structured [LogEntry] objects,
 *     queryable at runtime for diagnostics / crash reports.
 *
 * Logcat format:
 *   I/AC/WebRTC: [2025-06-15 14:32:01.123] [INFO   ] [WebRTC] Offer sent  sdpLen=1234
 *
 * Usage:
 *   private val log = StreamNodeLogger.forModule("WebRTC")
 *   log.d("ICE state", "state" to "CONNECTED")
 *   log.i("Offer sent", "sdpLen" to sdp.length, "attempt" to 1)
 *   log.w("Reconnect scheduled", "delayMs" to 2000L)
 *   log.e("Fatal socket error", throwable, "attempt" to 3)
 *
 * Structured fields appear as key=value after the message:
 *   [14:32:01.123] [INFO   ] [Control] Opened  url=wss://host/control attempt=1
 *
 * Diagnostic export:
 *   StreamNodeLogger.exportRingAsJson()   → compact JSON array of last N entries
 *   StreamNodeLogger.getRecentErrors()    → ERROR entries from last 5 minutes
 *   StreamNodeLogger.clearRing()          → wipe the in-memory ring
 *
 * Level filtering:
 *   StreamNodeLogger.minimumLevel = Level.DEBUG  (default — shows everything)
 *   Set to INFO/WARN/ERROR in production if needed.
 */
object StreamNodeLogger {

    // ── Level ──────────────────────────────────────────────────────────────────
    enum class Level(val priority: Int, val label: String) {
        VERBOSE(0, "VERBOSE"),
        DEBUG  (1, "DEBUG  "),
        INFO   (2, "INFO   "),
        WARN   (3, "WARN   "),
        ERROR  (4, "ERROR  ");
    }

    /** Minimum level that reaches Logcat and the ring. Adjust at runtime. */
    @Volatile var minimumLevel: Level = Level.DEBUG

    // ── Ring buffer ────────────────────────────────────────────────────────────
    private const val RING_CAPACITY = 500
    private val ring      = ConcurrentLinkedDeque<LogEntry>()
    private val seqSource = AtomicLong(0L)

    /** One structured log entry stored in the ring. */
    data class LogEntry(
        val seq:       Long,
        val timestamp: Long,            // System.currentTimeMillis()
        val level:     Level,
        val module:    String,
        val message:   String,
        val fields:    Map<String, Any?>,
        val throwable: Throwable? = null
    )

    // ── Thread-local date formatter ────────────────────────────────────────────
    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    // ── Core write ─────────────────────────────────────────────────────────────

    @JvmStatic
    fun write(
        level:     Level,
        module:    String,
        message:   String,
        fields:    Array<out Pair<String, Any?>>,
        throwable: Throwable?
    ) {
        if (level.priority < minimumLevel.priority) return

        val now       = System.currentTimeMillis()
        val fieldsMap: Map<String, Any?> =
            if (fields.isEmpty()) emptyMap() else linkedMapOf(*fields)

        // Store in ring
        val entry = LogEntry(
            seq       = seqSource.incrementAndGet(),
            timestamp = now,
            level     = level,
            module    = module,
            message   = message,
            fields    = fieldsMap,
            throwable = throwable
        )
        ring.addLast(entry)
        while (ring.size > RING_CAPACITY) ring.pollFirst()

        // Build Logcat string
        val ts       = fmt.get()!!.format(Date(now))
        val fieldStr = if (fieldsMap.isEmpty()) ""
                       else "  " + fieldsMap.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val msg      = "[$ts] [${level.label}] [$module] $message$fieldStr"
        val tag      = "AC/$module"

        when (level) {
            Level.VERBOSE -> Log.v(tag, msg)
            Level.DEBUG   -> Log.d(tag, msg)
            Level.INFO    -> Log.i(tag, msg)
            Level.WARN    -> if (throwable != null) Log.w(tag, msg, throwable)
                             else                   Log.w(tag, msg)
            Level.ERROR   -> if (throwable != null) Log.e(tag, msg, throwable)
                             else                   Log.e(tag, msg)
        }
    }

    // ── Factory ────────────────────────────────────────────────────────────────

    /** Returns a [ModuleLogger] scoped to [module]. */
    fun forModule(module: String): ModuleLogger = ModuleLogger(module, emptyArray())

    // ── Diagnostic helpers ─────────────────────────────────────────────────────

    /** Snapshot of the ring (oldest first). */
    fun getEntries(): List<LogEntry> = ring.toList()

    /** All ERROR entries within the last [windowMs] milliseconds. */
    fun getRecentErrors(windowMs: Long = 5 * 60_000L): List<LogEntry> {
        val cutoff = System.currentTimeMillis() - windowMs
        return ring.filter { it.level == Level.ERROR && it.timestamp >= cutoff }
    }

    /** Serialises the ring to a compact JSON string (newest last). */
    fun exportRingAsJson(): String {
        val arr = JSONArray()
        for (e in ring) {
            arr.put(JSONObject().apply {
                put("seq",    e.seq)
                put("ts",     fmt.get()!!.format(Date(e.timestamp)))
                put("level",  e.level.name)
                put("module", e.module)
                put("msg",    e.message)
                if (e.fields.isNotEmpty())
                    put("fields", JSONObject(e.fields.mapValues { it.value?.toString() ?: "null" }))
                if (e.throwable != null)
                    put("err", "${e.throwable.javaClass.simpleName}: ${e.throwable.message}")
            })
        }
        return arr.toString()
    }

    /** Clears the in-memory ring. */
    fun clearRing() = ring.clear()

    // ── ModuleLogger ───────────────────────────────────────────────────────────

    /**
     * Scoped logger for a specific module.
     *
     * All methods accept an optional vararg of structured key-value pairs:
     *   log.i("Connected", "url" to url, "attempt" to 1)
     *
     * [fixedFields] are prepended to every entry (used by child loggers).
     */
    class ModuleLogger internal constructor(
        private val module:      String,
        private val fixedFields: Array<out Pair<String, Any?>>
    ) {
        /** Returns a child logger that prepends [moreFixed] to every call. */
        fun child(vararg moreFixed: Pair<String, Any?>): ModuleLogger =
            ModuleLogger(module, (fixedFields.toList() + moreFixed.toList()).toTypedArray())

        private fun merged(extra: Array<out Pair<String, Any?>>): Array<out Pair<String, Any?>> =
            if (fixedFields.isEmpty()) extra
            else (fixedFields.toList() + extra.toList()).toTypedArray()

        fun v(msg: String, vararg f: Pair<String, Any?>) = write(Level.VERBOSE, module, msg, merged(f), null)
        fun d(msg: String, vararg f: Pair<String, Any?>) = write(Level.DEBUG,   module, msg, merged(f), null)
        fun i(msg: String, vararg f: Pair<String, Any?>) = write(Level.INFO,    module, msg, merged(f), null)
        fun w(msg: String, vararg f: Pair<String, Any?>) = write(Level.WARN,    module, msg, merged(f), null)
        fun e(msg: String, vararg f: Pair<String, Any?>) = write(Level.ERROR,   module, msg, merged(f), null)

        fun w(msg: String, t: Throwable, vararg f: Pair<String, Any?>) =
            write(Level.WARN,  module, msg, merged(f), t)
        fun e(msg: String, t: Throwable, vararg f: Pair<String, Any?>) =
            write(Level.ERROR, module, msg, merged(f), t)
    }
}
