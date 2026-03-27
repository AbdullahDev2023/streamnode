package com.akdevelopers.streamnode.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * StreamLogger — process-wide log event bus.
 *
 * Lives in domain:streaming so both data:streaming and feature:stream can access it
 * without a circular dependency. StreamingService (feature:stream) forwards its
 * own log() calls here too, so all log events flow through one SharedFlow.
 *
 * Usage:
 *   StreamLogger.log("🔌 Connected")          // from any module
 *   StreamLogger.events.collect { ... }       // from MainActivity
 */
object StreamLogger {

    private val _events = MutableSharedFlow<String>(replay = 200, extraBufferCapacity = 100)
    val events: SharedFlow<String> = _events

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Emit a timestamped log line. Safe to call from any thread. */
    fun log(msg: String) {
        val line = if (msg.startsWith("[")) msg
                   else "[${fmt.format(Date())}]  $msg"
        _events.tryEmit(line)
    }
}
