package com.akdevelopers.streamnode.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.akdevelopers.streamnode.core.AppConstants
import com.akdevelopers.streamnode.service.StreamingServiceBridge
import java.util.concurrent.TimeUnit

/**
 * Two-layer watchdog that keeps the streaming service alive on OEM-aggressive devices.
 *
 * Layer 1 — WorkManager (every 15 min, survives process kill)
 * Layer 2 — AlarmManager SCHEDULE_EXACT_ALARM (every 15 min, fires precisely)
 *
 * WorkManager alone can be deferred 10+ minutes by the OS scheduler. The exact
 * alarm wakes the device at wall-clock time, acting as a reliable secondary layer.
 *
 * Both layers run [checkAndRestart]: service dead → restart it.
 *
 * KEY FIX: If the service IS running but the WebSocket is down (server was
 * offline but just came back), we call StreamingServiceBridge.triggerReconnect()
 * instead of restarting the whole process.  Restarting resets the exponential
 * backoff, clears the command dedup set, and creates a cold-start reconnect
 * storm.  A targeted reconnect keeps all state intact.
 */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        Log.d(TAG, "WorkManager watchdog fired")
        checkAndRestart(applicationContext)
        // Re-arm the exact alarm from here too, in case it was lost after a reboot.
        scheduleExactAlarm(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG              = "AC_Watchdog"
        private const val WORK_NAME        = "streamnode_watchdog"
        /** Gap P2: no-network job — fires even during extended internet outages. */
        private const val WORK_NAME_OFFLINE = "streamnode_watchdog_offline"
        private val ALARM_RC = AppConstants.WATCHDOG_ALARM_REQUEST_CODE

        // OPT: health-aware progressive back-off ──────────────────────────────
        /** SharedPrefs key: consecutive healthy check streak counter. */
        private const val PREF_HEALTHY_STREAK = "watchdog_healthy_streak"
        /**
         * After this many consecutive healthy checks, the exact alarm interval is
         * doubled (up to WATCHDOG_ALARM_INTERVAL_MS × 2) so the watchdog wakes the
         * CPU less often when everything is fine.  Resets to 0 on any unhealthy check.
         */
        private const val HEALTHY_STREAK_THRESHOLD = 2


        /**
         * Check service health and act:
         *  - Service dead         → startForegroundService (full restart)
         *  - Service alive + WS down → triggerReconnect (no restart, keeps backoff + state)
         *  - Service alive + WS up   → no-op
         *
         * Returns true if any action was taken.
         */
        fun checkAndRestart(ctx: Context): Boolean {
            val prefs       = ctx.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
            val autoRestart = prefs.getBoolean(AppConstants.PREF_AUTO_RESTART, false)
            val url         = prefs.getString(AppConstants.PREF_SERVER_URL, null)
            val isRunning   = StreamingServiceBridge.isRunning.value
            val isConnected = StreamingServiceBridge.isConnected.value

            Log.d(TAG, "check: autoRestart=$autoRestart running=$isRunning connected=$isConnected")

            if (!autoRestart || url.isNullOrBlank()) return false

            // Cold-start grace: WorkManager can fire almost immediately after schedule().
            // At that point the WebSocket TLS handshake is still in progress.
            val startEpoch = prefs.getLong(AppConstants.PREF_SERVICE_START_EPOCH, 0L)
            if (startEpoch > 0L &&
                System.currentTimeMillis() - startEpoch < AppConstants.WATCHDOG_COLD_START_GRACE_MS
            ) {
                Log.d(TAG, "check: service started <${AppConstants.WATCHDOG_COLD_START_GRACE_MS / 1000}s ago — skipping")
                return false
            }

            return when {
                // ── Service process is dead — full restart needed ──────────────
                !isRunning -> {
                    prefs.edit().putInt(PREF_HEALTHY_STREAK, 0).apply()
                    Log.i(TAG, "Service dead — restarting via startForegroundService")
                    try {
                        ctx.startForegroundService(StreamingServiceBridge.buildStartIntent(ctx, url))
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "startForegroundService failed: ${e.message}")
                        false
                    }
                }

                // ── Service alive but WebSocket disconnected ────────────────────
                !isConnected -> {
                    prefs.edit().putInt(PREF_HEALTHY_STREAK, 0).apply()
                    Log.i(TAG, "Service running but WS disconnected — triggering reconnect")
                    val dispatched = StreamingServiceBridge.triggerReconnect()
                    if (!dispatched) {
                        Log.w(TAG, "reconnectCallback null — falling back to service restart")
                        try {
                            ctx.startForegroundService(StreamingServiceBridge.buildStartIntent(ctx, url))
                            true
                        } catch (e: Exception) {
                            Log.e(TAG, "fallback startForegroundService failed: ${e.message}")
                            false
                        }
                    } else true
                }

                // ── Everything healthy — OPT: skip and apply progressive back-off ──
                else -> {
                    val streak = prefs.getInt(PREF_HEALTHY_STREAK, 0) + 1
                    prefs.edit().putInt(PREF_HEALTHY_STREAK, streak).apply()
                    Log.d(TAG, "healthy — skip (streak=$streak)")
                    // After HEALTHY_STREAK_THRESHOLD consecutive healthy checks, extend
                    // the next alarm to 2× the default interval to reduce CPU wake-ups.
                    if (streak >= HEALTHY_STREAK_THRESHOLD) {
                        val extendedMs = AppConstants.WATCHDOG_ALARM_INTERVAL_MS * 2L
                        scheduleExactAlarm(ctx, extendedMs)
                        Log.d(TAG, "Progressive back-off: next alarm in ${extendedMs / 60_000} min")
                    }
                    false
                }
            }
        }


        /** Schedule WorkManager periodic work (survives force-stop). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            scheduleExactAlarm(context)
            // Gap P2 fix: also schedule a no-network-constraint job so the service
            // process stays alive during extended internet outages (days offline).
            // Without network the job can only confirm the service is alive and
            // re-arm the AlarmManager chain — reconnect happens when NetworkChangeReceiver
            // fires on internet restore.
            scheduleOfflineCheck(context)
            Log.d(TAG, "Watchdog scheduled (WorkManager + AlarmManager + offline job)")
        }

        /**
         * Gap P2 fix: no-network-constraint WorkManager job (every 30 min).
         *
         * Fires even when the device has no internet.  Its sole purpose is to:
         *   1. Ensure the StreamingService process is alive (restarts if dead).
         *   2. Re-arm the AlarmManager chain (in case it was lost during a reboot
         *      without SCHEDULE_EXACT_ALARM permission).
         *
         * When internet returns, [NetworkChangeReceiver] triggers reconnectNow()
         * immediately — this job does not attempt reconnect itself.
         */
        private fun scheduleOfflineCheck(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(30, TimeUnit.MINUTES)
                // No network constraint — fires even offline
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    WORK_NAME_OFFLINE,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            Log.d(TAG, "Offline watchdog scheduled (no network constraint, 30 min)")
        }

        /** Cancel both layers. Call when the user explicitly exits. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME_OFFLINE)
            cancelExactAlarm(context)
            Log.d(TAG, "Watchdog cancelled (all layers)")
        }

        fun scheduleExactAlarm(context: Context) {
            scheduleExactAlarm(context, AppConstants.WATCHDOG_ALARM_INTERVAL_MS.toLong())
        }

        /**
         * OPT: Overload that accepts a custom [delayMs] so progressive back-off can
         * schedule the next alarm at 2× the default interval when the service is healthy.
         */
        fun scheduleExactAlarm(context: Context, delayMs: Long) {
            val am       = context.getSystemService(AlarmManager::class.java) ?: return
            val pi       = alarmPendingIntent(context) ?: return
            val triggerAt = System.currentTimeMillis() + delayMs
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    Log.w(TAG, "SCHEDULE_EXACT_ALARM not granted — using inexact alarm")
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    Log.d(TAG, "Exact alarm set for +${delayMs / 60_000} min")
                }
            } catch (e: Exception) {
                Log.e(TAG, "scheduleExactAlarm failed: ${e.message}")
            }
        }

        private fun cancelExactAlarm(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            alarmPendingIntent(context)?.let { am.cancel(it) }
        }

        private fun alarmPendingIntent(context: Context): PendingIntent? =
            PendingIntent.getBroadcast(
                context, ALARM_RC,
                Intent(context, WatchdogAlarmReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
