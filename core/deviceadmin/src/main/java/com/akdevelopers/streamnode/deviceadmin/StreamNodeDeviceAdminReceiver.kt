package com.akdevelopers.streamnode.deviceadmin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.akdevelopers.streamnode.core.AppConstants
import com.google.firebase.database.FirebaseDatabase

/**
 * StreamNodeDeviceAdminReceiver — Device Admin lifecycle hooks.
 *
 * Receives system broadcasts when:
 *  - Device Admin is enabled / disabled (user grant/revoke)
 *  - Password changed / failed / succeeded
 *
 * On enable/disable, reports current admin grant status to Firebase RTDB
 * at /users/{streamId}/adminStatus so the server dashboard shows the badge.
 */
class StreamNodeDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "AC_DevAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin ENABLED")
        pushAdminStatus(context, isAdmin = true)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin DISABLED")
        pushAdminStatus(context, isAdmin = false)
    }

    override fun onPasswordChanged(context: Context, intent: Intent) {
        super.onPasswordChanged(context, intent)
        Log.i(TAG, "Password changed remotely")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Log.w(TAG, "Password attempt failed")
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        Log.i(TAG, "Password attempt succeeded")
    }

    // ── Firebase status push ───────────────────────────────────────────────────

    private fun pushAdminStatus(context: Context, isAdmin: Boolean) {
        val prefs    = context.getSharedPreferences(AppConstants.PREFS_FILE, Context.MODE_PRIVATE)
        val streamId = prefs.getString(AppConstants.PREF_STREAM_ID, null) ?: return
        val db       = FirebaseDatabase.getInstance(AppConstants.FIREBASE_DB_URL)
        val ref      = db.getReference(
            "${AppConstants.FIREBASE_PATH_USERS}/$streamId/${AppConstants.FIREBASE_PATH_ADMIN_STATUS}"
        )
        ref.setValue(mapOf(
            "isDeviceAdmin" to isAdmin,
            "isDeviceOwner" to DeviceAdminCommander.isDeviceOwner(context),
            "lastUpdated"   to System.currentTimeMillis(),
        ))
    }
}
