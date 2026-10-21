package com.instaguard.app.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.instaguard.app.InstaGuardWidgetProvider
import com.instaguard.app.R
import com.instaguard.app.alarm.AlarmFragment
import com.instaguard.app.data.GuardPreferences
import com.instaguard.app.databinding.ActivityMainBinding
import com.instaguard.app.service.AppBlockerService
import com.instaguard.app.util.GuardManager
import com.instaguard.app.util.UsageStatsHelper
import java.util.Locale

/**
 * Main Activity for InstaGuard Pro v12.1 (Premium UI Suite).
 * Features a YouTube/Instagram-inspired persistent Bottom Navigation Bar,
 * OLED Dark-Glass theme, seamless 0-lag fragment caching,
 * real-time 30 FPS live ticker updates, and complete digital wellbeing shields.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var guardManager: GuardManager
        private set

    private val uiHandler = Handler(Looper.getMainLooper())
    private var previewTts: TextToSpeech? = null
    private var isPreviewTtsReady = false

    private lateinit var homeFragment: HomeDashboardFragment
    private lateinit var analyticsFragment: AnalyticsFragment
    private lateinit var alarmFragment: AlarmFragment
    private lateinit var shieldsFragment: ShieldsFragment
    private lateinit var settingsFragment: SettingsFragment
    private var activeFragment: Fragment? = null

    private val liveTickerRunnable = object : Runnable {
        override fun run() {
            tickLiveMetrics()
            uiHandler.postDelayed(this, 33L) // 33ms (~30 FPS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        guardManager = GuardManager(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        checkAndRequestAlarmOptimizations()

        initPreviewTts()
        setupFragments(savedInstanceState)
        setupBottomNavigation()
        setupToolbar()
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        val fm = supportFragmentManager
        if (savedInstanceState == null) {
            homeFragment = HomeDashboardFragment()
            analyticsFragment = AnalyticsFragment()
            alarmFragment = AlarmFragment()
            shieldsFragment = ShieldsFragment()
            settingsFragment = SettingsFragment()

            fm.beginTransaction()
                .add(R.id.fragmentContainer, homeFragment, "HOME")
                .add(R.id.fragmentContainer, analyticsFragment, "ANALYTICS").hide(analyticsFragment)
                .add(R.id.fragmentContainer, alarmFragment, "ALARM").hide(alarmFragment)
                .add(R.id.fragmentContainer, shieldsFragment, "SHIELDS").hide(shieldsFragment)
                .add(R.id.fragmentContainer, settingsFragment, "SETTINGS").hide(settingsFragment)
                .commit()

            activeFragment = homeFragment
        } else {
            homeFragment = fm.findFragmentByTag("HOME") as? HomeDashboardFragment ?: HomeDashboardFragment()
            analyticsFragment = fm.findFragmentByTag("ANALYTICS") as? AnalyticsFragment ?: AnalyticsFragment()
            alarmFragment = fm.findFragmentByTag("ALARM") as? AlarmFragment ?: AlarmFragment()
            shieldsFragment = fm.findFragmentByTag("SHIELDS") as? ShieldsFragment ?: ShieldsFragment()
            settingsFragment = fm.findFragmentByTag("SETTINGS") as? SettingsFragment ?: SettingsFragment()
            activeFragment = homeFragment
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigationView.setOnItemSelectedListener { item ->
            val targetFragment = when (item.itemId) {
                R.id.nav_home -> homeFragment
                R.id.nav_analytics -> analyticsFragment
                R.id.nav_alarm -> alarmFragment
                R.id.nav_shields -> shieldsFragment
                R.id.nav_settings -> settingsFragment
                else -> null
            }

            if (targetFragment != null && targetFragment != activeFragment) {
                val current = activeFragment ?: homeFragment
                supportFragmentManager.beginTransaction()
                    .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                    .hide(current)
                    .show(targetFragment)
                    .commit()
                activeFragment = targetFragment

                // Refresh target fragment immediately on tab switch
                if (targetFragment == alarmFragment) {
                    checkAndRequestAlarmOptimizations()
                }
                refreshActiveTab(targetFragment)
                true
            } else {
                true
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbarMain.title = ""
        binding.tvToolbarTitle.text = "InstaGuard Pro"
        binding.tvToolbarBadge.text = "v14.6 PRO"
        binding.btnRefresh.setOnClickListener {
            AppBlockerService.syncFromPrefs(this)
            refreshAllFragments()
            InstaGuardWidgetProvider.updateAllWidgets(this)
            Toast.makeText(this, R.string.btn_refresh, Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestAlarmOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (am != null && !am.canScheduleExactAlarms()) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun refreshActiveTab(fragment: Fragment) {
        val hasUsage = UsageStatsHelper.hasUsageStatsPermission(this)
        val hasAccessibility = GuardManager.isAccessibilityServiceEnabled(this, AppBlockerService::class.java)

        when (fragment) {
            is HomeDashboardFragment -> fragment.updateDashboard(guardManager, hasUsage, hasAccessibility)
            is AnalyticsFragment -> fragment.updateAnalytics(guardManager)
            is AlarmFragment -> fragment.refreshAlarmsUI()
            is ShieldsFragment -> fragment.refreshShieldsUI(guardManager)
            is SettingsFragment -> fragment.refreshSettingsUI(guardManager)
        }
    }

    private fun refreshAllFragments() {
        val hasUsage = UsageStatsHelper.hasUsageStatsPermission(this)
        val hasAccessibility = GuardManager.isAccessibilityServiceEnabled(this, AppBlockerService::class.java)

        if (::homeFragment.isInitialized && homeFragment.isAdded) {
            homeFragment.updateDashboard(guardManager, hasUsage, hasAccessibility)
        }
        if (::analyticsFragment.isInitialized && analyticsFragment.isAdded) {
            analyticsFragment.updateAnalytics(guardManager)
        }
        if (::alarmFragment.isInitialized && alarmFragment.isAdded) {
            alarmFragment.refreshAlarmsUI()
        }
        if (::shieldsFragment.isInitialized && shieldsFragment.isAdded) {
            shieldsFragment.refreshShieldsUI(guardManager)
        }
        if (::settingsFragment.isInitialized && settingsFragment.isAdded) {
            settingsFragment.refreshSettingsUI(guardManager)
        }
    }

    private fun tickLiveMetrics() {
        val hasUsage = UsageStatsHelper.hasUsageStatsPermission(this)
        val hasAccessibility = GuardManager.isAccessibilityServiceEnabled(this, AppBlockerService::class.java)

        // Always update HomeDashboard at 30 FPS when visible or added
        if (::homeFragment.isInitialized && homeFragment.isAdded) {
            homeFragment.updateDashboard(guardManager, hasUsage, hasAccessibility)
        }

        // When Analytics is foreground, tick its live screen-time counters
        if (activeFragment == analyticsFragment && ::analyticsFragment.isInitialized && analyticsFragment.isAdded) {
            analyticsFragment.updateAnalytics(guardManager)
        }

        // When Shields is foreground, update live scanner HUD diagnostic
        if (activeFragment == shieldsFragment && ::shieldsFragment.isInitialized && shieldsFragment.isAdded) {
            shieldsFragment.updateLiveHud()
        }
    }

    override fun onResume() {
        super.onResume()
        AppBlockerService.isOurAppScreenOpen = true
        AppBlockerService.syncFromPrefs(this)

        refreshAllFragments()
        InstaGuardWidgetProvider.updateAllWidgets(this)

        uiHandler.removeCallbacks(liveTickerRunnable)
        uiHandler.post(liveTickerRunnable)
    }

    override fun onPause() {
        super.onPause()
        AppBlockerService.isOurAppScreenOpen = false
        uiHandler.removeCallbacks(liveTickerRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            previewTts?.stop()
            previewTts?.shutdown()
            previewTts = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initPreviewTts() {
        try {
            previewTts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    previewTts?.language = Locale.US
                    previewTts?.setSpeechRate(0.95f)
                    isPreviewTtsReady = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // -------------------------------------------------------------
    // Action Delegates from Tabs & Fragments
    // -------------------------------------------------------------

    fun notifyModeChanged() {
        refreshAllFragments()
        InstaGuardWidgetProvider.updateAllWidgets(this)
    }

    fun startDeepWork(durationMs: Long) {
        AppBlockerService.activateDeepWorkMode(this, durationMs)
        notifyModeChanged()
        val hours = durationMs / 3_600_000L
        Toast.makeText(
            this,
            "🚀 ${hours}-Hour Deep Work Study Lock Activated! All social apps locked.",
            Toast.LENGTH_LONG
        ).show()
    }

    fun confirmCancelStudyMode() {
        if (!AppBlockerService.isDeepWorkModeActive(this)) {
            Toast.makeText(this, "ℹ️ Study Mode is not currently active.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Stop Study Mode?")
            .setMessage("Are you sure you want to cancel your active Deep Work / Study Mode lock?")
            .setPositiveButton("Yes, Cancel Now") { dialog, _ ->
                AppBlockerService.cancelDeepWorkMode(this)
                notifyModeChanged()
                Toast.makeText(this, "✅ Deep Work / Study Mode Cancelled!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Keep Focusing") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    fun promptPinForVisualShieldDisable() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(22f), dpToPx(14f), dpToPx(22f), dpToPx(8f))
        }

        val tvInfo = TextView(this).apply {
            text = "Enter your 4-digit Master PIN to disable AI Visual Shield & Safe Browser Guard."
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 13f
        }

        val etPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(Color.WHITE)
            hint = "••••"
            setHintTextColor(Color.parseColor("#484F58"))
        }

        container.addView(tvInfo)
        container.addView(etPin)

        AlertDialog.Builder(this)
            .setTitle("Disable AI Visual Shield?")
            .setView(container)
            .setPositiveButton("VERIFY & DISABLE") { dialog, _ ->
                val entered = etPin.text.toString().trim()
                if (entered == guardManager.prefs.masterPin) {
                    guardManager.prefs.isAiVisualShieldEnabled = false
                    shieldsFragment.refreshShieldsUI(guardManager)
                    Toast.makeText(this, "AI Visual Shield Disabled", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "Incorrect Master PIN!", Toast.LENGTH_SHORT).show()
                    shieldsFragment.refreshShieldsUI(guardManager)
                }
            }
            .setNegativeButton("CANCEL") { dialog, _ ->
                shieldsFragment.refreshShieldsUI(guardManager)
                dialog.dismiss()
            }
            .setOnCancelListener {
                shieldsFragment.refreshShieldsUI(guardManager)
            }
            .show()
    }

    fun startNightSleepDisableMathChallenge(step: Int) {
        if (step > 3) {
            showNightSleepDisablePinDialog()
            return
        }

        val num1 = (14..28).random()
        val num2 = (5..9).random()
        val num3 = (20..85).random()
        val isAddition = (0..1).random() == 1

        val problemStr = if (isAddition) "($num1 × $num2) + $num3" else "($num1 × $num2) - $num3"
        val expectedAnswer = if (isAddition) (num1 * num2) + num3 else (num1 * num2) - num3

        val container = FrameLayout(this).apply {
            setPadding(56, 24, 56, 12)
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            hint = "Answer"
            setHintTextColor(Color.parseColor("#484F58"))
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("🌙 Night Sleep Guard Challenge (Step $step of 3)")
            .setMessage("It is currently past 11:30 PM. Solve this challenge to disable Night Sleep Guard:\n\n$problemStr = ?")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(if (step < 3) "Next Step ➔" else "Proceed to PIN ➔") { dialog, _ ->
                val enteredAnswer = input.text.toString().trim().toIntOrNull()
                if (enteredAnswer != null && enteredAnswer == expectedAnswer) {
                    dialog.dismiss()
                    Toast.makeText(this, "✅ Step $step solved!", Toast.LENGTH_SHORT).show()
                    startNightSleepDisableMathChallenge(step + 1)
                } else {
                    dialog.dismiss()
                    Toast.makeText(this, "❌ Incorrect answer! Challenge reset to Step 1.", Toast.LENGTH_LONG).show()
                    startNightSleepDisableMathChallenge(step = 1)
                }
            }
            .setNegativeButton("Keep Sleep Guard ON") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showNightSleepDisablePinDialog() {
        val container = FrameLayout(this).apply {
            setPadding(56, 24, 56, 12)
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            hint = "••••"
            setHintTextColor(Color.parseColor("#484F58"))
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("🔐 Final Step: Master PIN")
            .setMessage("Enter your 4-digit Master PIN to disable Night Sleep Guard during sleep hours.")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Authorize") { dialog, _ ->
                val entered = input.text.toString().trim()
                if (entered == guardManager.prefs.masterPin) {
                    guardManager.prefs.isNightSleepGuardEnabled = false
                    shieldsFragment.refreshShieldsUI(guardManager)
                    notifyModeChanged()
                    Toast.makeText(this, "🌙 Night Sleep Guard Disabled.", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "❌ Incorrect Master PIN!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showMasterPinSettingsDialog() {
        val anyLocked = AppBlockerService.APP_KEYS.any { AppBlockerService.isAppLocked(it, this) }
        if (anyLocked) {
            Toast.makeText(this, "Cannot change Master PIN while any app is locked!", Toast.LENGTH_LONG).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24f), dpToPx(16f), dpToPx(24f), dpToPx(8f))
        }

        val tvCurrentLabel = TextView(this).apply {
            text = "Current 4-Digit PIN:"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 13f
        }
        val etCurrentPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(Color.WHITE)
            hint = "••••"
            setHintTextColor(Color.parseColor("#484F58"))
        }

        val tvNewLabel = TextView(this).apply {
            text = "New 4-Digit PIN:"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 13f
            setPadding(0, dpToPx(12f), 0, 0)
        }
        val etNewPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(Color.WHITE)
            hint = "••••"
            setHintTextColor(Color.parseColor("#484F58"))
        }

        val tvConfirmLabel = TextView(this).apply {
            text = "Confirm New PIN:"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 13f
            setPadding(0, dpToPx(12f), 0, 0)
        }
        val etConfirmPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(Color.WHITE)
            hint = "••••"
            setHintTextColor(Color.parseColor("#484F58"))
        }

        container.addView(tvCurrentLabel)
        container.addView(etCurrentPin)
        container.addView(tvNewLabel)
        container.addView(etNewPin)
        container.addView(tvConfirmLabel)
        container.addView(etConfirmPin)

        AlertDialog.Builder(this)
            .setTitle("🔐 Master PIN Settings")
            .setMessage("Change your 4-digit Master PIN for emergency 5-minute unlock.")
            .setView(container)
            .setPositiveButton("SAVE PIN") { dialog, _ ->
                val currentEntered = etCurrentPin.text.toString().trim()
                val newEntered = etNewPin.text.toString().trim()
                val confirmEntered = etConfirmPin.text.toString().trim()

                if (currentEntered != guardManager.prefs.masterPin) {
                    Toast.makeText(this, "Incorrect current PIN!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newEntered.length != 4 || !newEntered.all { it.isDigit() }) {
                    Toast.makeText(this, "New PIN must be exactly 4 digits!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newEntered != confirmEntered) {
                    Toast.makeText(this, "New PIN and confirmation do not match!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                guardManager.prefs.masterPin = newEntered
                Toast.makeText(this, "Master PIN updated successfully!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    fun showAddCustomAppDialog() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = pm.queryIntentActivities(mainIntent, 0)

        val corePackages = setOf(
            packageName,
            "com.instagram.android", "com.instagram.lite",
            "com.google.android.youtube", "com.google.android.youtube.tv",
            "com.facebook.katana", "com.facebook.lite", "com.facebook.orca",
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.tiktok.live.lite"
        )

        data class AppEntry(val label: String, val packageName: String)
        val appEntries = resolveList.mapNotNull { resolveInfo ->
            val pkg = resolveInfo.activityInfo.packageName
            if (corePackages.contains(pkg)) null
            else {
                val label = resolveInfo.loadLabel(pm).toString()
                AppEntry(label, pkg)
            }
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase(Locale.ROOT) }

        if (appEntries.isEmpty()) {
            Toast.makeText(this, "No additional launchable apps found.", Toast.LENGTH_SHORT).show()
            return
        }

        val currentTracked = guardManager.prefs.getCustomTrackedPackages().toMutableSet()
        val appNames = appEntries.map { "${it.label} (${it.packageName})" }.toTypedArray()
        val checkedItems = BooleanArray(appEntries.size) { i ->
            currentTracked.contains(appEntries[i].packageName)
        }

        AlertDialog.Builder(this)
            .setTitle("📱 Select Apps to Block & Track")
            .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                val pkg = appEntries[which].packageName
                if (isChecked) {
                    currentTracked.add(pkg)
                } else {
                    currentTracked.remove(pkg)
                }
            }
            .setPositiveButton("SAVE") { dialog, _ ->
                guardManager.prefs.setCustomTrackedPackages(currentTracked)
                notifyModeChanged()
                Toast.makeText(this, "Shield updated: ${currentTracked.size} custom apps monitored!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    fun showMaintenanceModeDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24f), dpToPx(16f), dpToPx(24f), dpToPx(8f))
        }

        val tvInfo = TextView(this).apply {
            text = "Enter your 4-digit Master PIN to unlock a 5-minute Maintenance Window. This temporarily pauses anti-tamper shields for uninstallation or system management."
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 13f
        }

        val etPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(Color.WHITE)
            hint = "••••"
            setHintTextColor(Color.parseColor("#484F58"))
            setPadding(0, dpToPx(12f), 0, dpToPx(8f))
        }

        container.addView(tvInfo)
        container.addView(etPin)

        AlertDialog.Builder(this)
            .setTitle("🛠️ 5-Minute Maintenance Mode")
            .setView(container)
            .setPositiveButton("ACTIVATE") { dialog, _ ->
                val entered = etPin.text.toString().trim()
                if (entered == guardManager.prefs.masterPin) {
                    val maintenanceUntil = System.currentTimeMillis() + 5 * 60 * 1000L
                    guardManager.prefs.maintenanceUntilMs = maintenanceUntil
                    Toast.makeText(this, "🛠️ Maintenance Mode active for 5 minutes! Anti-tamper paused.", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "Incorrect Master PIN!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    fun showEditCustomVoiceDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(22f), dpToPx(14f), dpToPx(22f), dpToPx(8f))
        }

        val tvBlockedLabel = TextView(this).apply {
            text = "Voice Message When App Is Blocked / Locked:"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 12.5f
        }
        val etBlockedVoice = EditText(this).apply {
            setText(guardManager.prefs.customVoiceBlockedMsg)
            hint = "Voice message when app is blocked..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#484F58"))
            textSize = 14f
        }

        val tvWarningLabel = TextView(this).apply {
            text = "Extra Message on 10m / 5m / 1m Warning:"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 12.5f
            setPadding(0, dpToPx(12f), 0, 0)
        }
        val etWarningVoice = EditText(this).apply {
            setText(guardManager.prefs.customVoiceWarningMsg)
            hint = "Extra message on 10m/5m/1m warning..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#484F58"))
            textSize = 14f
        }

        container.addView(tvBlockedLabel)
        container.addView(etBlockedVoice)
        container.addView(tvWarningLabel)
        container.addView(etWarningVoice)

        AlertDialog.Builder(this)
            .setTitle("🗣️ Edit Custom Voice Alert")
            .setView(container)
            .setPositiveButton("💾 Save Voice") { dialog, _ ->
                val newBlocked = etBlockedVoice.text.toString().trim()
                    .ifEmpty { GuardPreferences.DEFAULT_CUSTOM_VOICE_BLOCKED_MSG }
                val newWarning = etWarningVoice.text.toString().trim()
                    .ifEmpty { GuardPreferences.DEFAULT_CUSTOM_VOICE_WARNING_MSG }

                guardManager.prefs.customVoiceBlockedMsg = newBlocked
                guardManager.prefs.customVoiceWarningMsg = newWarning
                settingsFragment.refreshSettingsUI(guardManager)
                Toast.makeText(this, "✅ Custom Voice Message Saved!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNeutralButton("🔄 Reset Default") { dialog, _ ->
                guardManager.prefs.customVoiceBlockedMsg = GuardPreferences.DEFAULT_CUSTOM_VOICE_BLOCKED_MSG
                guardManager.prefs.customVoiceWarningMsg = GuardPreferences.DEFAULT_CUSTOM_VOICE_WARNING_MSG
                settingsFragment.refreshSettingsUI(guardManager)
                Toast.makeText(this, "🔄 Default Voice Messages Restored!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun testCustomVoiceMessageNow() {
        val blockedMsg = guardManager.prefs.customVoiceBlockedMsg.trim()
            .ifEmpty { GuardPreferences.DEFAULT_CUSTOM_VOICE_BLOCKED_MSG }
            .replace("{app}", "Instagram", ignoreCase = true)

        val spokenByService = AppBlockerService.previewCustomVoice(blockedMsg)
        if (!spokenByService) {
            if (previewTts == null || !isPreviewTtsReady) {
                initPreviewTts()
            }
            previewTts?.speak(blockedMsg, TextToSpeech.QUEUE_FLUSH, null, "preview_voice_now")
        }
        Toast.makeText(this, "🔊 Speaking Custom Voice Alert...", Toast.LENGTH_SHORT).show()
    }

    fun openUsageSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Overlay permission not required on this Android version.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dpToPx(dp: Float): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
