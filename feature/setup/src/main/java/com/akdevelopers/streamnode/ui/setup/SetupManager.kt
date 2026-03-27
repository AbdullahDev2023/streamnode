package com.akdevelopers.streamnode.ui.setup
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.akdevelopers.streamnode.core.AppConstants

/**
 * Tracks completion of mandatory one-time setup steps.
 *
 * All state is stored in [AppConstants.PREFS_FILE] — the single shared
 * SharedPreferences file for the whole app. Keys are defined in [AppConstants]
 * so no string literals live outside that central registry.
 */
object SetupManager {

    // ── Step checks ───────────────────────────────────────────────────────────

    fun isAutostartConfirmed(ctx: Context): Boolean =
        ctx.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
            .getBoolean(AppConstants.PREF_AUTOSTART_CONFIRMED, false)

    fun markAutostartConfirmed(ctx: Context) {
        ctx.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(AppConstants.PREF_AUTOSTART_CONFIRMED, true).apply()
    }

    /** True if the OS is ignoring battery optimizations for this app. */
    fun isBatteryExempt(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(ctx.packageName)

    /**
     * True if this OEM device needs manual autostart setup AND
     * the user has not confirmed it yet.
     */
    fun autostartStepRequired(ctx: Context): Boolean =
        OemAutostartHelper.needsAutostartSetup() && !isAutostartConfirmed(ctx)

    /** All mandatory setup is complete — safe to show main UI. */
    fun isComplete(ctx: Context, allPermsGranted: Boolean): Boolean =
        allPermsGranted && isBatteryExempt(ctx) && !autostartStepRequired(ctx)

    /** Open the system battery optimisation exemption dialog. */
    fun requestBatteryExemption(ctx: Activity) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                }
            )
        }
    }
}
