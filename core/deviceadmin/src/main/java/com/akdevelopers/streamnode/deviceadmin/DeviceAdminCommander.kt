package com.akdevelopers.streamnode.deviceadmin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.akdevelopers.streamnode.core.AppConstants
import com.google.firebase.database.FirebaseDatabase

/**
 * DeviceAdminCommander — executes all admin_* commands from the server.
 *
 * Called by CommandProcessor when action starts with "admin_".
 * Each method guards itself with isAdminActive() before executing.
 *
 * Device Admin commands: lock, wipe, camera, keyguard, password policy
 * Device Owner commands: reboot, clear_app_data, install_app, uninstall_app,
 *                        system settings (brightness, volume)
 *
 * Use  ADB to grant Device Owner (once, during provisioning):
 *   adb shell dpm set-device-owner com.akdevelopers.streamnode/.deviceadmin.StreamNodeDeviceAdminReceiver
 */
object DeviceAdminCommander {

    private const val TAG = "AC_AdminCmd"

    // ── DPM helpers ────────────────────────────────────────────────────────────

    private fun dpm(ctx: Context) =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(ctx: Context) =
        ComponentName(ctx, StreamNodeDeviceAdminReceiver::class.java)

    fun isAdminActive(ctx: Context)  = dpm(ctx).isAdminActive(admin(ctx))
    fun isDeviceOwner(ctx: Context)  = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    // ── Public entry point — called by CommandProcessor ────────────────────────

    fun execute(ctx: Context, action: String, payload: String = "") {
        Log.i(TAG, "execute: action=$action payload=${payload.take(60)}")
        when (action) {
            AppConstants.ADMIN_CMD_LOCK           -> lock(ctx)
            AppConstants.ADMIN_CMD_WIPE           -> wipe(ctx)
            AppConstants.ADMIN_CMD_REBOOT         -> reboot(ctx)
            AppConstants.ADMIN_CMD_CAMERA_DISABLE -> setCameraDisabled(ctx, payload != "false")
            AppConstants.ADMIN_CMD_RESET_PASSWORD -> resetPassword(ctx, payload)
            AppConstants.ADMIN_CMD_SET_BRIGHTNESS -> setBrightness(ctx, payload.toIntOrNull() ?: 128)
            AppConstants.ADMIN_CMD_SET_VOLUME     -> setVolume(ctx, payload.toIntOrNull() ?: 10)
            AppConstants.ADMIN_CMD_CLEAR_APP_DATA -> clearAppData(ctx, payload)
            AppConstants.ADMIN_CMD_INSTALL_APP    -> Log.w(TAG, "install_app: APK push via Firebase URL not yet wired")
            AppConstants.ADMIN_CMD_UNINSTALL_APP  -> uninstallPackage(ctx, payload)
            AppConstants.ADMIN_CMD_MAX_FAILS      -> setMaxFailedPasswords(ctx, payload.toIntOrNull() ?: 10)
            AppConstants.ADMIN_CMD_MAX_LOCK_TIME  -> setMaxLockTime(ctx, payload.toLongOrNull() ?: 300_000L)
            else                                  -> Log.w(TAG, "Unknown admin action: $action")
        }
        pushAdminLog(ctx, action, payload)
    }

    // ── Device Admin commands (require Device Admin) ───────────────────────────

    private fun lock(ctx: Context) {
        if (!isAdminActive(ctx)) { Log.e(TAG, "lock: not admin"); return }
        dpm(ctx).lockNow()
        Log.i(TAG, "Screen locked")
    }

    private fun wipe(ctx: Context) {
        if (!isAdminActive(ctx)) { Log.e(TAG, "wipe: not admin"); return }
        Log.w(TAG, "WIPE DATA triggered!")
        dpm(ctx).wipeData(0)
    }

    private fun setCameraDisabled(ctx: Context, disable: Boolean) {
        if (!isAdminActive(ctx)) { Log.e(TAG, "camera: not admin"); return }
        dpm(ctx).setCameraDisabled(admin(ctx), disable)
        Log.i(TAG, "Camera disabled=$disable")
    }

    private fun resetPassword(ctx: Context, newPassword: String) {
        if (!isAdminActive(ctx)) { Log.e(TAG, "resetPassword: not admin"); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            dpm(ctx).resetPassword(newPassword, DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY)
        } else {
            // resetPasswordWithToken requires pre-set token — log guidance
            Log.w(TAG, "resetPassword on API 26+: use resetPasswordWithToken after provisioning a token")
        }
    }

    private fun setMaxFailedPasswords(ctx: Context, max: Int) {
        if (!isAdminActive(ctx)) return
        dpm(ctx).setMaximumFailedPasswordsForWipe(admin(ctx), max)
        Log.i(TAG, "Max failed passwords set to $max")
    }

    private fun setMaxLockTime(ctx: Context, ms: Long) {
        if (!isAdminActive(ctx)) return
        dpm(ctx).setMaximumTimeToLock(admin(ctx), ms)
        Log.i(TAG, "Max lock time set to ${ms}ms")
    }

    // ── Device Owner commands (require Device Owner) ───────────────────────────

    private fun reboot(ctx: Context) {
        if (!isDeviceOwner(ctx)) { Log.e(TAG, "reboot: not device owner"); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dpm(ctx).reboot(admin(ctx))
        } else {
            Log.e(TAG, "reboot: requires API 24+")
        }
    }

    private fun clearAppData(ctx: Context, packageName: String) {
        if (!isDeviceOwner(ctx)) { Log.e(TAG, "clearAppData: not device owner"); return }
        if (packageName.isBlank()) { Log.e(TAG, "clearAppData: empty packageName"); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm(ctx).clearApplicationUserData(admin(ctx), packageName, ctx.mainExecutor) { pkg, succeeded ->
                Log.i(TAG, "clearAppData $pkg → succeeded=$succeeded")
            }
        } else {
            Log.e(TAG, "clearAppData: requires API 28+")
        }
    }

    private fun uninstallPackage(ctx: Context, packageName: String) {
        if (!isDeviceOwner(ctx)) { Log.e(TAG, "uninstall: not device owner"); return }
        if (packageName.isBlank()) return
        // PackageInstaller silent uninstall via DevicePolicyManager
        val packageInstaller = ctx.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = packageInstaller.createSession(params)
        packageInstaller.abandonSession(sessionId)
        dpm(ctx).setUninstallBlocked(admin(ctx), packageName, false)
        Log.i(TAG, "Uninstall unblocked for $packageName — user will see prompt")
    }

    // ── System settings (require WRITE_SETTINGS — Device Owner grants this) ───

    private fun setBrightness(ctx: Context, value: Int) {
        val clamped = value.coerceIn(0, 255)
        try {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Log.i(TAG, "Brightness set to $clamped")
        } catch (e: Exception) { Log.e(TAG, "setBrightness failed: ${e.message}") }
    }

    private fun setVolume(ctx: Context, level: Int) {
        val am  = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
        Log.i(TAG, "Volume set to $level / $max")
    }

    // ── Admin log — writes command result to Firebase RTDB ────────────────────

    private fun pushAdminLog(ctx: Context, action: String, payload: String) {
        val prefs    = ctx.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
        val streamId = prefs.getString(AppConstants.PREF_STREAM_ID, null) ?: return
        val db       = FirebaseDatabase.getInstance(AppConstants.FIREBASE_DB_URL)
        db.getReference(
            "${AppConstants.FIREBASE_PATH_USERS}/$streamId/${AppConstants.FIREBASE_PATH_ADMIN_LOG}"
        ).push().setValue(mapOf(
            "action"    to action,
            "payload"   to payload,
            "ts"        to System.currentTimeMillis(),
            "isAdmin"   to isAdminActive(ctx),
            "isOwner"   to isDeviceOwner(ctx),
        ))
    }
}
