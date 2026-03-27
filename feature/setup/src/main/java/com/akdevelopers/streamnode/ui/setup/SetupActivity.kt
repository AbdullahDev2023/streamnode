package com.akdevelopers.streamnode.ui.setup
import android.Manifest
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.akdevelopers.streamnode.R
import com.akdevelopers.streamnode.analytics.Analytics
import com.akdevelopers.streamnode.core.AppConstants
import com.akdevelopers.streamnode.deviceadmin.StreamNodeDeviceAdminReceiver
import com.akdevelopers.streamnode.deviceadmin.DeviceAdminCommander

/**
 * SetupActivity — mandatory one-time setup gate.
 *
 * Blocks entry to the main UI until ALL steps are confirmed:
 *   Step 1 — Runtime permissions (RECORD_AUDIO, READ_PHONE_STATE, POST_NOTIFICATIONS)
 *   Step 2 — Battery optimisation exemption
 *   Step 3 — OEM Autostart / background launch
 *   Step 4 — Device Admin enrollment (skippable — features degrade gracefully)
 */
class SetupActivity : AppCompatActivity() {

    private enum class Step { PERMISSIONS, BATTERY, AUTOSTART, DEVICE_ADMIN }

    private lateinit var dot1: View
    private lateinit var dot2: View
    private lateinit var dot3: View
    private lateinit var dot4: View
    private lateinit var tvIcon:   TextView
    private lateinit var tvTitle:  TextView
    private lateinit var tvDesc:   TextView
    private lateinit var btnAction:    TextView
    private lateinit var btnSecondary: TextView
    private lateinit var llWarn:   LinearLayout
    private lateinit var tvWarn:   TextView

    private var currentStep: Step = Step.PERMISSIONS
    private var waitingForAutostartReturn  = false
    private var autostartSettingsOpened   = false
    private var waitingForAdminReturn     = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Check whether core permissions (non-location) are all granted
        val coreGranted = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CAMERA,
        ).all { results[it] != false } &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    results[Manifest.permission.POST_NOTIFICATIONS] != false)

        // Feature 9 — If location was granted, persist the opt-in flag
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (locationGranted) {
            getSharedPreferences(AppConstants.PREFS_FILE, MODE_PRIVATE)
                .edit().putBoolean(AppConstants.PREF_SEND_LOCATION, true).apply()
        }

        if (coreGranted) {
            Analytics.logPermissionGranted()
            advance()
        } else {
            showWarn("Sab permissions zaroori hain. Please allow karein.")
            btnAction.text = "Permission Dobara Maango"
            btnAction.setOnClickListener { requestPermissions() }
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.text = "App Settings Kholein (manually allow)"
            btnSecondary.setOnClickListener { openAppSettings() }
        }
    }

    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        waitingForAdminReturn = false
        val isAdmin = DeviceAdminCommander.isAdminActive(this)
        val prefs   = getSharedPreferences(AppConstants.PREFS_FILE, MODE_PRIVATE)
        prefs.edit().putBoolean(AppConstants.PREF_DEVICE_ADMIN_ENABLED, isAdmin).apply()
        if (isAdmin) advance() else renderStep(Step.DEVICE_ADMIN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        bindViews()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* setup is mandatory */ }
        })

        savedInstanceState?.let {
            autostartSettingsOpened   = it.getBoolean("autostartOpened", false)
            waitingForAutostartReturn = it.getBoolean("waitingAutostart", false)
            waitingForAdminReturn     = it.getBoolean("waitingAdmin", false)
        }
        advance()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("autostartOpened",  autostartSettingsOpened)
        outState.putBoolean("waitingAutostart", waitingForAutostartReturn)
        outState.putBoolean("waitingAdmin",     waitingForAdminReturn)
    }

    override fun onResume() {
        super.onResume()
        if (waitingForAutostartReturn) {
            waitingForAutostartReturn = false
            showAutostartReturnDialog()
            return
        }
        if (currentStep == Step.PERMISSIONS && allPermsGranted()) advance()
        if (currentStep == Step.BATTERY && SetupManager.isBatteryExempt(this)) advance()
    }

    private fun advance() {
        currentStep = when {
            !allPermsGranted()                       -> Step.PERMISSIONS
            !SetupManager.isBatteryExempt(this)      -> Step.BATTERY
            SetupManager.autostartStepRequired(this) -> Step.AUTOSTART
            !deviceAdminStepDone()                   -> Step.DEVICE_ADMIN
            else -> { launchMain(); return }
        }
        renderStep(currentStep)
    }

    private fun deviceAdminStepDone(): Boolean {
        // Enrolled = done. User can also skip via secondary button (stored as "skipped").
        val prefs = getSharedPreferences(AppConstants.PREFS_FILE, MODE_PRIVATE)
        return DeviceAdminCommander.isAdminActive(this) ||
               prefs.getBoolean("device_admin_skipped", false)
    }

    private fun launchMain() {
        // Gap 1 fix: enable auto-restart as soon as setup is complete so the
        // device comes back online after any reboot, even before the operator
        // has sent the first "start" command.  BootReceiver also checks that a
        // server URL is saved — if not yet saved, it skips the restart safely.
        getSharedPreferences(AppConstants.PREFS_FILE, MODE_PRIVATE)
            .edit()
            .putBoolean(AppConstants.PREF_AUTO_RESTART, true)
            .putBoolean(AppConstants.PREF_AUTO_START_ON_BOOT, true)
            .apply()

        // Gap 5 fix: on Android 12+ (API 31), check that SCHEDULE_EXACT_ALARM
        // is granted so the 15-min watchdog alarm fires precisely even in Doze.
        // Non-blocking — user can skip; WatchdogWorker falls back to inexact alarm.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(AlarmManager::class.java)
            if (am != null && !am.canScheduleExactAlarms()) {
                AlertDialog.Builder(this)
                    .setTitle("⏰ Exact Alarm Permission")
                    .setMessage(
                        "StreamNode ka 15-minute reliability watchdog exact time pe fire karne ke liye " +
                        "'Alarms & Reminders' permission chahiye.\n\n" +
                        "Settings mein StreamNode ke saamne toggle ON karein, phir wapas aayein."
                    )
                    .setCancelable(true)
                    .setPositiveButton("Settings Kholein →") { _, _ ->
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.fromParts("package", packageName, null)
                        })
                    }
                    .setNegativeButton("Baad mein") { _, _ -> }
                    .setOnDismissListener {
                        startActivity(Intent().setClassName(this, "com.akdevelopers.streamnode.ui.MainActivity"))
                        finish()
                    }
                    .show()
                return  // MainActivity launched from dismiss listener
            }
        }

        startActivity(Intent().setClassName(this, "com.akdevelopers.streamnode.ui.MainActivity"))
        finish()
    }

    private fun renderStep(step: Step) {
        hideWarn()
        btnSecondary.visibility = View.GONE
        updateDots(step)
        when (step) {
            Step.PERMISSIONS   -> renderPermissionsStep()
            Step.BATTERY       -> renderBatteryStep()
            Step.AUTOSTART     -> renderAutostartStep()
            Step.DEVICE_ADMIN  -> renderDeviceAdminStep()
        }
    }

    private fun renderPermissionsStep() {
        tvIcon.text  = "🎙️"
        tvTitle.text = "Permissions Chahiye"
        tvDesc.text  =
            "StreamNode ko yeh permissions zaroori hain:\n\n" +
            "• Microphone — awaaz stream karne ke liye\n" +
            "• Camera — front/back camera stream karne ke liye\n" +
            "• Phone State — call ke waqt automatic switch ke liye\n" +
            "• Notifications — streaming status dikhane ke liye\n" +
            "• Location (optional) — dashboard map mein GPS location dikhane ke liye\n\n" +
            "Location permission optional hai — deny karne par baki features kaam karenge."
        btnAction.text = "Permission Allow Karein ✓"
        btnAction.setOnClickListener { requestPermissions() }
    }

    private fun renderBatteryStep() {
        tvIcon.text  = "🔋"
        tvTitle.text = "Battery Restriction Hatao"
        tvDesc.text  =
            "Android battery optimization se StreamNode ko background mein band kar deta hai.\n\n" +
            "Next screen mein 'Allow' tap karein — warna streaming automatically ruk jaayegi.\n\n" +
            "💡 Setup complete hone ke baad aapko 'Alarms & Reminders' permission bhi milega " +
            "taaki watchdog timer precisely kaam kare."
        btnAction.text = "Battery Setting Kholein →"
        btnAction.setOnClickListener { SetupManager.requestBatteryExemption(this) }
    }

    private fun renderAutostartStep() {
        val oemName = OemAutostartHelper.getOemName()
        tvIcon.text  = "🚀"
        tvTitle.text = "Autostart Permission ($oemName)"
        tvDesc.text  =
            "$oemName apne aap apps ko background mein band kar deta hai.\n\n" +
            "Neeche button dabao → settings mein StreamNode ke saamne toggle ON karo → " +
            "wapas aao aur confirm karo."
        if (autostartSettingsOpened) {
            btnAction.text = "Haan, Maine ON Kar Diya ✓"
            btnAction.setOnClickListener {
                SetupManager.markAutostartConfirmed(this)
                advance()
            }
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.text = "Nahi hua — Settings Dobara Kholein"
            btnSecondary.setOnClickListener { openAutostartSettings() }
        } else {
            btnAction.text = "Autostart Settings Kholein →"
            btnAction.setOnClickListener { openAutostartSettings() }
        }
    }

    private fun renderDeviceAdminStep() {
        tvIcon.text  = "🛡️"
        tvTitle.text = "Device Admin Enable Karein"
        tvDesc.text  =
            "Server se device ko remotely control karne ke liye Device Admin access chahiye.\n\n" +
            "Yeh allow karega:\n" +
            "• Screen lock (remote)\n" +
            "• Camera on/off\n" +
            "• Password policy\n" +
            "• Device wipe (emergency)\n\n" +
            "Aap baad mein bhi Settings → Security → Device Admins se disable kar sakte hain."
        btnAction.text = "Device Admin Enable Karein →"
        btnAction.setOnClickListener { requestDeviceAdmin() }
        btnSecondary.visibility = View.VISIBLE
        btnSecondary.text = "Abhi Nahi (features limited rahenge)"
        btnSecondary.setOnClickListener {
            getSharedPreferences(AppConstants.PREFS_FILE, MODE_PRIVATE)
                .edit().putBoolean("device_admin_skipped", true).apply()
            advance()
        }
    }

    private fun requestDeviceAdmin() {
        val admin  = ComponentName(this, StreamNodeDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "StreamNode server ko aapka device remotely control karne ki permission dena.")
        }
        waitingForAdminReturn = true
        adminLauncher.launch(intent)
    }

    private fun showAutostartReturnDialog() {
        AlertDialog.Builder(this)
            .setTitle("Autostart Enable Hua? 🤔")
            .setMessage(
                "Kya aapne settings mein StreamNode ke saamne Autostart toggle ON kar diya?\n\n" +
                "Agar haan — 'Haan' dabao.\nAgar nahi — 'Wapas Jao' se settings dobara kholein."
            )
            .setCancelable(false)
            .setPositiveButton("Haan, kar diya ✓") { _, _ ->
                SetupManager.markAutostartConfirmed(this); advance()
            }
            .setNegativeButton("Nahi, wapas jao") { _, _ -> openAutostartSettings() }
            .show()
    }

    private fun openAutostartSettings() {
        autostartSettingsOpened   = true
        waitingForAutostartReturn = true
        OemAutostartHelper.openAutostartSettings(this)
        renderStep(Step.AUTOSTART)
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CAMERA,
            // Feature 9 — Location permission (optional for GPS map tracking)
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        })
    }

    private fun allPermsGranted(): Boolean {
        val base  = hasPerm(Manifest.permission.RECORD_AUDIO) &&
                    hasPerm(Manifest.permission.READ_PHONE_STATE) &&
                    hasPerm(Manifest.permission.CAMERA)
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            hasPerm(Manifest.permission.POST_NOTIFICATIONS) else true
        return base && notif
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun showWarn(msg: String) { llWarn.visibility = View.VISIBLE; tvWarn.text = msg }
    private fun hideWarn()            { llWarn.visibility = View.GONE }

    private fun updateDots(step: Step) {
        val active   = getColor(R.color.accent_purple)
        val inactive = 0xFF2E3251.toInt()
        val done     = getColor(R.color.status_live)
        fun tint(v: View, c: Int) = (v.background.mutate() as? GradientDrawable)?.setColor(c)
        val order = listOf(dot1, dot2, dot3, dot4)
        val idx   = step.ordinal
        order.forEachIndexed { i, v -> tint(v, when { i < idx -> done; i == idx -> active; else -> inactive }) }
    }

    private fun bindViews() {
        dot1         = findViewById(R.id.dot1)
        dot2         = findViewById(R.id.dot2)
        dot3         = findViewById(R.id.dot3)
        dot4         = findViewById(R.id.dot4)
        tvIcon       = findViewById(R.id.tvStepIcon)
        tvTitle      = findViewById(R.id.tvStepTitle)
        tvDesc       = findViewById(R.id.tvStepDesc)
        btnAction    = findViewById(R.id.btnStepAction)
        btnSecondary = findViewById(R.id.btnStepSecondary)
        llWarn       = findViewById(R.id.llWarn)
        tvWarn       = findViewById(R.id.tvWarn)
    }
}
