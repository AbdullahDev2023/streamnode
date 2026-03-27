package com.akdevelopers.streamnode.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * StreamLockManager — owns the WakeLock and WifiLock for the streaming session.
 *
 * Extracted from StreamingService so lock lifecycle is a named concern with a
 * clear acquire / release contract, rather than ad-hoc fields scattered across
 * the service class.
 *
 * Usage:
 *   val locks = StreamLockManager(context)
 *   locks.acquire()   // call once after startForeground()
 *   locks.release()   // call in stopAll() / onDestroy()
 */
class StreamLockManager(private val context: Context) {

    companion object {
        private const val TAG = "AC_LockMgr"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock?  = null

    /** Acquire WakeLock (PARTIAL) and WifiLock. Safe to call multiple times. */
    fun acquire() {
        if (wakeLock?.isHeld == true) return

        wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamNode::StreamLock")
            .also { it.acquire() }

        if (wifiLock?.isHeld != true) {
            // WIFI_MODE_FULL_LOW_LATENCY is the modern replacement for the deprecated
            // FULL_HIGH_PERF on API 29+. Both keep the radio in active-scan-off mode
            // to reduce streaming latency and prevent packet loss from power-save bursts.
            @Suppress("DEPRECATION")
            val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = (context.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(wifiMode, "StreamNode::WifiLock")
                .also { if (!it.isHeld) it.acquire() }
        }
        Log.d(TAG, "Acquired  wakeLock=${wakeLock?.isHeld}  wifiLock=${wifiLock?.isHeld}")
    }

    /** Release both locks. Safe to call if they are not held. */
    fun release() {
        wakeLock?.apply { if (isHeld) release() }
        wakeLock = null
        wifiLock?.apply { if (isHeld) release() }
        wifiLock = null
        Log.d(TAG, "Released")
    }
}
