package com.akdevelopers.streamnode.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.akdevelopers.streamnode.R
import com.akdevelopers.streamnode.service.TransportMode

/**
 * StreamingNotificationManager — owns the foreground notification lifecycle.
 *
 * Extracted from StreamingService so notification channel creation, notification
 * building, and update calls are a named concern that can be tested and modified
 * without editing the service.
 *
 * Usage:
 *   notifManager.createChannel()              // once in Service.onCreate()
 *   startForeground(NOTIF_ID, notifManager.build(StreamStatus.CONNECTING))
 *   notifManager.update(StreamStatus.STREAMING)
 */
class StreamingNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "streamnode_stream"
        const val NOTIF_ID   = 1

        private const val SETUP_CLASS = "com.akdevelopers.streamnode.ui.setup.SetupActivity"
    }

    /**
     * Phase 5 — current transport mode.
     * Set by StreamingService.wireConnectionOrchestrator() whenever
     * ConnectionOrchestrator fires onTransportModeChanged.
     * Null means we haven't received a transport report yet (no suffix shown).
     */
    var currentTransportMode: TransportMode? = null

    /** Create the notification channel. Must be called before build(). */
    fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            "StreamNode Streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Active while StreamNode is running" }
        notificationManager().createNotificationChannel(ch)
    }

    /** Build a notification for [status]. Does not post it. */
    fun build(status: StreamStatus): Notification {
        val openPi = PendingIntent.getActivity(
            context, 0,
            Intent().setClassName(context, SETUP_CLASS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleAction = if (status == StreamStatus.STREAMING || status == StreamStatus.CALL_ACTIVE) {
            val i  = Intent(context, StreamingService::class.java).apply { action = StreamingService.ACTION_STOP_MIC }
            val pi = PendingIntent.getService(context, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            NotificationCompat.Action(R.drawable.ic_mic_notif, "⏸ Pause", pi)
        } else {
            val i  = Intent(context, StreamingService::class.java).apply { action = StreamingService.ACTION_START_MIC }
            val pi = PendingIntent.getService(context, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            NotificationCompat.Action(R.drawable.ic_mic_notif, "▶ Resume", pi)
        }

        val exitPi = PendingIntent.getService(
            context, 3,
            Intent(context, StreamingService::class.java).apply { action = StreamingService.ACTION_STOP_FULL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val (title, text) = when (status) {
            StreamStatus.CONNECTING     -> "StreamNode"                to "Connecting to server…"
            StreamStatus.CONNECTED_IDLE -> "StreamNode — Ready"        to "Connected · tap Resume to stream"
            StreamStatus.STREAMING      -> {
                // Phase 5: append transport badge when streaming so users see transport at a glance
                val transportSuffix = currentTransportMode?.let { " · ${it.emoji} ${it.label}" } ?: ""
                "StreamNode — LIVE 🔴" to "Streaming to PC$transportSuffix"
            }
            StreamStatus.CALL_ACTIVE    -> {
                val transportSuffix = currentTransportMode?.let { " · ${it.emoji}" } ?: ""
                "StreamNode — 📞 Call LIVE" to "Streaming call · both voices$transportSuffix"
            }
            StreamStatus.RECONNECTING   -> "StreamNode"                to "Reconnecting…"
            StreamStatus.MIC_ERROR      -> "StreamNode"                to "Microphone error"
            StreamStatus.IDLE           -> "StreamNode"                to "Idle"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_notif)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(openPi)
            .addAction(toggleAction)
            .addAction(R.drawable.ic_mic_notif, "✕ Exit", exitPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true).build()
    }

    /** Post an updated notification for [status]. */
    fun update(status: StreamStatus) {
        notificationManager().notify(NOTIF_ID, build(status))
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}
