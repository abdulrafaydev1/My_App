package com.instaguard.app.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.instaguard.app.R
import com.instaguard.app.databinding.ActivityMainBinding
import com.instaguard.app.service.AppBlockerService
import com.instaguard.app.util.GuardManager
import com.instaguard.app.util.UsageStatsHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Main Dashboard Activity for InstaGuard Pro v6.0.
 * Features a Cyber-Dark UI, live Top-Center Reel/Short counter, Focus Streak,
 * Discipline Score gamification, Any-App picker, 5-min Maintenance mode,
 * strictly isolated per-app 1h limit and 5h cooldown monitoring, and analytics.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var guardManager: GuardManager
    private val uiHandler = Handler(Looper.getMainLooper())
    private var statusPulseAnimator: ObjectAnimator? = null
    private var hasAnimatedEntrance = false
    private var last7DayReportRenderMs: Long = 0L

    private val liveTickerRunnable = object : Runnable {
        override fun run() {
            updateAllAppCards()
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

        applyMonospaceTypefaces()
        setupListeners()
        setupTestModeSwitch()
        startStatusPulseAnimation()
        playStaggeredEntranceAnimation()
    }

    override fun onResume() {
        super.onResume()
        AppBlockerService.syncFromPrefs(this)

        updatePermissionsUI()
        updateAllAppCards()

        uiHandler.removeCallbacks(liveTickerRunnable)
        uiHandler.post(liveTickerRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(liveTickerRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        statusPulseAnimator?.cancel()
    }

    private fun applyMonospaceTypefaces() {
        val monospaceViews = listOf(
            binding.tvFocusStreak, binding.tvDisciplineScore,
            binding.tvInstagramTimer, binding.tvInstagramLimit, binding.tvInstagramStatus, binding.tvInstagramScrolls,
            binding.tvYouTubeTimer, binding.tvYouTubeLimit, binding.tvYouTubeStatus, binding.tvYouTubeScrolls,
            binding.tvFacebookTimer, binding.tvFacebookLimit, binding.tvFacebookStatus, binding.tvFacebookScrolls,
            binding.tvTikTokTimer, binding.tvTikTokLimit, binding.tvTikTokStatus, binding.tvTikTokScrolls,
            binding.tvCustomAppsSummary,
            binding.tvAnalyticsTodayTotal, binding.tvAnalyticsScrollsTotal,
            binding.tvAnalyticsInstagram, binding.tvAnalyticsYouTube,
            binding.tvAnalyticsFacebook, binding.tvAnalyticsTikTok, binding.tvAnalytics7DayReport
        )
        for (view in monospaceViews) {
            view.typeface = Typeface.MONOSPACE
        }
    }

    /**
     * Staggered slide-up and fade-in entrance animation for the dashboard cards.
     */
    private fun playStaggeredEntranceAnimation() {
        if (hasAnimatedEntrance) return
        hasAnimatedEntrance = true

        // Feature 4 B: Sleek 450ms scale + fade-in launch animation on Header & Streak Card
        binding.layoutHeader.scaleX = 0.88f
        binding.layoutHeader.scaleY = 0.88f
        binding.layoutHeader.animate().scaleX(1.0f).scaleY(1.0f).setDuration(450).start()

        binding.cardFocusStreak.scaleX = 0.88f
        binding.cardFocusStreak.scaleY = 0.88f
        binding.cardFocusStreak.animate().scaleX(1.0f).scaleY(1.0f).setDuration(450).setStartDelay(80).start()

        val viewsToAnimate = listOf(
            binding.layoutHeader,
            binding.cardFocusStreak,
            binding.cardOverallStatus,
            binding.cardTestMode,
            binding.btnMasterPinSettings,
            binding.btnAddCustomApp,
            binding.btnMaintenanceMode,
            binding.layoutSectionTitle,
            binding.cardInstagram,
            binding.cardYouTube,
            binding.cardFacebook,
            binding.cardTikTok,
            binding.cardCustomApps,
            binding.cardAnalyticsReport
        )

        for ((index, view) in viewsToAnimate.withIndex()) {
            view.alpha = 0f
            view.translationY = 60f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(350)
                .setStartDelay((index * 45).toLong())
                .start()
        }
    }

    /**
     * Smooth breathing pulse animation on the live status indicator dot.
     */
    private fun startStatusPulseAnimation() {
        statusPulseAnimator?.cancel()
        val pulse = ObjectAnimator.ofFloat(binding.viewStatusDot, View.ALPHA, 1f, 0.25f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        pulse.start()
        statusPulseAnimator = pulse
    }

    /**
     * Anti-Bypass Guard: Test mode cannot be toggled if ANY app has an active cooldown or session.
     */
    private fun setupTestModeSwitch() {
        binding.switchTestMode.isChecked = guardManager.prefs.isTestMode
        binding.switchTestMode.setOnCheckedChangeListener { buttonView, isChecked ->
            val anyLocked = AppBlockerService.APP_KEYS.any { AppBlockerService.isAppLocked(it, this) }
            val anyActiveSession = AppBlockerService.APP_KEYS.any { AppBlockerService.getLiveUsageMs(it, this) > 120_000L }

            if (anyLocked || anyActiveSession) {
                Toast.makeText(this, "Cannot switch mode while sessions or locks are active!", Toast.LENGTH_LONG).show()
                buttonView.setOnCheckedChangeListener(null)
                buttonView.isChecked = guardManager.prefs.isTestMode
                buttonView.setOnCheckedChangeListener { _, _ -> setupTestModeSwitch() }
                return@setOnCheckedChangeListener
            }

            guardManager.prefs.isTestMode = isChecked
            updateAllAppCards()
            val msg = if (isChecked) {
                "Test Mode (1m Limit / 2m Lock) Enabled"
            } else {
                "Normal Mode (1h Limit / 5h Lock) Enabled"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupListeners() {
        // Permission 1: Usage Access Settings
        binding.btnGrantUsage.setOnClickListener {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }

        // Permission 2: Accessibility Settings
        binding.btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }

        // Permission 3: Display Over Other Apps Settings (PiP Defense)
        binding.btnGrantOverlay.setOnClickListener {
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

        // Refresh Button
        binding.btnRefresh.setOnClickListener {
            AppBlockerService.syncFromPrefs(this)
            updatePermissionsUI()
            updateAllAppCards()
            Toast.makeText(this, R.string.btn_refresh, Toast.LENGTH_SHORT).show()
        }

        // Feature 3: Master PIN Settings Dialog
        binding.btnMasterPinSettings.setOnClickListener {
            showMasterPinSettingsDialog()
        }

        // Feature 4 C: Add / Manage Custom Apps to Block
        binding.btnAddCustomApp.setOnClickListener {
            showAddCustomAppDialog()
        }

        // Feature 4 D: PIN-Protected Maintenance / Uninstall Mode
        binding.btnMaintenanceMode.setOnClickListener {
            showMaintenanceModeDialog()
        }
    }

    /**
     * Updates each of the 4 cards strictly using its independent app key at 30 FPS.
     */
    private fun updateAllAppCards() {
        val prefs = guardManager.prefs
        val limitMs = prefs.getUsageLimitMs()
        val cooldownMs = prefs.getCooldownDurationMs()

        // Feature 3: Focus Streak & Discipline Score Gamification
        updateFocusStreakAndDisciplineScore()

        // 1. Instagram
        renderAppCard(
            appKey = "INSTAGRAM",
            card = binding.cardInstagram,
            tvBadge = binding.tvInstagramBadge,
            tvTimer = binding.tvInstagramTimer,
            tvLimit = binding.tvInstagramLimit,
            tvStatus = binding.tvInstagramStatus,
            tvScrolls = binding.tvInstagramScrolls,
            progressIndicator = binding.progressInstagram,
            limitMs = limitMs,
            cooldownMs = cooldownMs
        )

        // 2. YouTube
        renderAppCard(
            appKey = "YOUTUBE",
            card = binding.cardYouTube,
            tvBadge = binding.tvYouTubeBadge,
            tvTimer = binding.tvYouTubeTimer,
            tvLimit = binding.tvYouTubeLimit,
            tvStatus = binding.tvYouTubeStatus,
            tvScrolls = binding.tvYouTubeScrolls,
            progressIndicator = binding.progressYouTube,
            limitMs = limitMs,
            cooldownMs = cooldownMs
        )

        // 3. Facebook
        renderAppCard(
            appKey = "FACEBOOK",
            card = binding.cardFacebook,
            tvBadge = binding.tvFacebookBadge,
            tvTimer = binding.tvFacebookTimer,
            tvLimit = binding.tvFacebookLimit,
            tvStatus = binding.tvFacebookStatus,
            tvScrolls = binding.tvFacebookScrolls,
            progressIndicator = binding.progressFacebook,
            limitMs = limitMs,
            cooldownMs = cooldownMs
        )

        // 4. TikTok
        renderAppCard(
            appKey = "TIKTOK",
            card = binding.cardTikTok,
            tvBadge = binding.tvTikTokBadge,
            tvTimer = binding.tvTikTokTimer,
            tvLimit = binding.tvTikTokLimit,
            tvStatus = binding.tvTikTokStatus,
            tvScrolls = binding.tvTikTokScrolls,
            progressIndicator = binding.progressTikTok,
            limitMs = limitMs,
            cooldownMs = cooldownMs
        )

        // Feature 4 C: Custom Apps Card
        updateCustomAppsCard()

        // 5. Usage Analytics Dashboard (Feature 5)
        updateAnalyticsDashboard()
    }

    private fun renderAppCard(
        appKey: String,
        card: MaterialCardView,
        tvBadge: TextView,
        tvTimer: TextView,
        tvLimit: TextView,
        tvStatus: TextView,
        tvScrolls: TextView,
        progressIndicator: LinearProgressIndicator,
        limitMs: Long,
        cooldownMs: Long
    ) {
        val lockRemaining = AppBlockerService.getRemainingLockMs(appKey, this)
        val usedMs = AppBlockerService.getLiveUsageMs(appKey, this).coerceAtMost(limitMs)
        val isForegroundActive = AppBlockerService.currentForegroundAppKey == appKey

        val todayScrolls = AppBlockerService.getScrollToday(appKey, this)
        val totalScrolls = AppBlockerService.getScrollTotal(appKey, this)
        tvScrolls.text = "🎬 Today's Scrolls: $todayScrolls  •  All-Time: $totalScrolls"

        if (lockRemaining > 0L) {
            // LOCKED STATE (Crimson Red Glowing Border)
            card.strokeColor = Color.parseColor("#EF4444")
            card.strokeWidth = dpToPx(1.5f)

            tvBadge.text = "LOCKED (5H COOLDOWN)"
            tvBadge.setTextColor(Color.parseColor("#EF4444"))
            val badgeBg = GradientDrawable().apply {
                cornerRadius = dpToPx(6f).toFloat()
                setColor(Color.parseColor("#3F1212"))
            }
            tvBadge.background = badgeBg

            tvTimer.text = "Used: " + guardManager.formatWithMillis(limitMs)
            tvTimer.setTextColor(Color.parseColor("#F87171"))

            tvLimit.text = "/ " + guardManager.formatWithMillis(limitMs)
            tvStatus.text = "Unlocks in: " + guardManager.formatWithMillis(lockRemaining)
            tvStatus.setTextColor(Color.parseColor("#F87171"))

            // Cooldown drain-down progress
            val drainProgress = if (cooldownMs > 0L) {
                ((lockRemaining.toFloat() / cooldownMs) * 1000).toInt().coerceIn(0, 1000)
            } else 0
            progressIndicator.setIndicatorColor(Color.parseColor("#EF4444"))
            progressIndicator.progress = drainProgress
        } else {
            // ALLOWED STATE
            if (isForegroundActive) {
                // Currently in Foreground: Emerald Green Active Border
                card.strokeColor = Color.parseColor("#10B981")
                card.strokeWidth = dpToPx(1.5f)
            } else {
                // Default Slate Border
                card.strokeColor = Color.parseColor("#2A364F")
                card.strokeWidth = dpToPx(1f)
            }

            tvBadge.text = if (isForegroundActive) "ACTIVE IN USE" else "ALLOWED"
            tvBadge.setTextColor(Color.parseColor("#10B981"))
            val badgeBg = GradientDrawable().apply {
                cornerRadius = dpToPx(6f).toFloat()
                setColor(Color.parseColor("#064E3B"))
            }
            tvBadge.background = badgeBg

            tvTimer.text = guardManager.formatWithMillis(usedMs)
            tvTimer.setTextColor(Color.parseColor("#38BDF8")) // Bright Cyan

            tvLimit.text = "/ " + guardManager.formatWithMillis(limitMs)

            val remainingAllowance = maxOf(0L, limitMs - usedMs)
            tvStatus.text = guardManager.formatWithMillis(remainingAllowance) + " left"
            tvStatus.setTextColor(Color.parseColor("#94A3B8"))

            val progress = if (limitMs > 0L) {
                ((usedMs.toFloat() / limitMs) * 1000).toInt().coerceIn(0, 1000)
            } else 0
            progressIndicator.progress = progress

            // Dynamic progress tint: Green -> Warning Amber at 80% -> Red at 95%
            when {
                progress >= 950 -> progressIndicator.setIndicatorColor(Color.parseColor("#EF4444"))
                progress >= 800 -> progressIndicator.setIndicatorColor(Color.parseColor("#F59E0B"))
                else -> progressIndicator.setIndicatorColor(Color.parseColor("#10B981"))
            }
        }
    }

    /**
     * Refreshes permission status indicators with live status dot tinting.
     */
    private fun updatePermissionsUI() {
        val hasUsage = UsageStatsHelper.hasUsageStatsPermission(this)
        val hasAccessibility = GuardManager.isAccessibilityServiceEnabled(this, AppBlockerService::class.java)
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        val isServiceFullyActive = hasUsage && hasAccessibility

        if (isServiceFullyActive) {
            binding.cardOverallStatus.strokeColor = Color.parseColor("#10B981")
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
            binding.imgOverallStatus.setImageResource(R.drawable.ic_check_circle)
            binding.imgOverallStatus.setColorFilter(Color.parseColor("#10B981"))
            binding.tvOverallStatusTitle.text = "PROTECTION ACTIVE"
            binding.tvOverallStatusTitle.setTextColor(Color.parseColor("#10B981"))
            binding.tvOverallStatusDesc.text = "Strict 1h limit and 5h cooldown actively enforced for all 4 apps."
        } else {
            binding.cardOverallStatus.strokeColor = Color.parseColor("#F59E0B")
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
            binding.imgOverallStatus.setImageResource(R.drawable.ic_warning)
            binding.imgOverallStatus.setColorFilter(Color.parseColor("#F59E0B"))
            binding.tvOverallStatusTitle.text = "SETUP REQUIRED"
            binding.tvOverallStatusTitle.setTextColor(Color.parseColor("#F59E0B"))
            binding.tvOverallStatusDesc.text = "Grant required permissions below to activate 5-hour focus protection."
        }

        // Usage Access Permission Card
        if (hasUsage) {
            binding.imgUsageStatus.setImageResource(R.drawable.ic_check_circle)
            binding.imgUsageStatus.setColorFilter(Color.parseColor("#10B981"))
            binding.tvUsageStatusBadge.text = getString(R.string.status_granted)
            binding.tvUsageStatusBadge.setTextColor(Color.parseColor("#10B981"))
            binding.btnGrantUsage.isEnabled = false
            binding.btnGrantUsage.text = getString(R.string.status_granted)
        } else {
            binding.imgUsageStatus.setImageResource(R.drawable.ic_warning)
            binding.imgUsageStatus.setColorFilter(Color.parseColor("#F59E0B"))
            binding.tvUsageStatusBadge.text = getString(R.string.status_action_required)
            binding.tvUsageStatusBadge.setTextColor(Color.parseColor("#F59E0B"))
            binding.btnGrantUsage.isEnabled = true
            binding.btnGrantUsage.text = getString(R.string.btn_grant_usage_access)
        }

        // Accessibility Service Permission Card
        if (hasAccessibility) {
            binding.imgAccessibilityStatus.setImageResource(R.drawable.ic_check_circle)
            binding.imgAccessibilityStatus.setColorFilter(Color.parseColor("#10B981"))
            binding.tvAccessibilityStatusBadge.text = getString(R.string.status_granted)
            binding.tvAccessibilityStatusBadge.setTextColor(Color.parseColor("#10B981"))
            binding.btnEnableAccessibility.isEnabled = false
            binding.btnEnableAccessibility.text = getString(R.string.status_granted)
        } else {
            binding.imgAccessibilityStatus.setImageResource(R.drawable.ic_warning)
            binding.imgAccessibilityStatus.setColorFilter(Color.parseColor("#F59E0B"))
            binding.tvAccessibilityStatusBadge.text = getString(R.string.status_action_required)
            binding.tvAccessibilityStatusBadge.setTextColor(Color.parseColor("#F59E0B"))
            binding.btnEnableAccessibility.isEnabled = true
            binding.btnEnableAccessibility.text = getString(R.string.btn_enable_accessibility)
        }

        // Overlay Permission Card (PiP Defense)
        if (hasOverlay) {
            binding.imgOverlayStatus.setImageResource(R.drawable.ic_check_circle)
            binding.imgOverlayStatus.setColorFilter(Color.parseColor("#10B981"))
            binding.tvOverlayStatusBadge.text = getString(R.string.status_granted)
            binding.tvOverlayStatusBadge.setTextColor(Color.parseColor("#10B981"))
            binding.btnGrantOverlay.isEnabled = false
            binding.btnGrantOverlay.text = getString(R.string.status_granted)
        } else {
            binding.imgOverlayStatus.setImageResource(R.drawable.ic_warning)
            binding.imgOverlayStatus.setColorFilter(Color.parseColor("#F59E0B"))
            binding.tvOverlayStatusBadge.text = getString(R.string.status_action_required)
            binding.tvOverlayStatusBadge.setTextColor(Color.parseColor("#F59E0B"))
            binding.btnGrantOverlay.isEnabled = true
            binding.btnGrantOverlay.text = getString(R.string.btn_grant_overlay)
        }
    }

    private fun showMasterPinSettingsDialog() {
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
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
        }
        val etCurrentPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(Color.WHITE)
            hint = "••••"
            setHintTextColor(Color.parseColor("#64748B"))
        }

        val tvNewLabel = TextView(this).apply {
            text = "New 4-Digit PIN:"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, dpToPx(12f), 0, 0)
        }
        val etNewPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(Color.WHITE)
            hint = "••••"
            setHintTextColor(Color.parseColor("#64748B"))
        }

        val tvConfirmLabel = TextView(this).apply {
            text = "Confirm New PIN:"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, dpToPx(12f), 0, 0)
        }
        val etConfirmPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(Color.WHITE)
            hint = "••••"
            setHintTextColor(Color.parseColor("#64748B"))
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

    private fun updateFocusStreakAndDisciplineScore() {
        val streak = guardManager.prefs.focusStreakDays
        binding.tvFocusStreak.text = "🔥 ${streak}-Day Active Streak"

        val totalScrolls = AppBlockerService.getTotalScrollsToday(this)
        val lockedCount = AppBlockerService.APP_KEYS.count { AppBlockerService.isAppLocked(it, this) }

        // Start at 100, penalize 15 points per locked app, and 1 point per 10 scrolls over 50
        val scrollPenalty = if (totalScrolls > 50) (totalScrolls - 50) / 10 else 0
        val disciplineScore = (100 - (lockedCount * 15) - scrollPenalty).coerceIn(10, 100)

        binding.tvDisciplineScore.text = "Discipline Score: $disciplineScore%"
        binding.progressDisciplineScore.progress = disciplineScore

        val (rankText, rankColor) = when {
            disciplineScore >= 90 -> "🏆 Grandmaster Focus" to Color.parseColor("#10B981")
            disciplineScore >= 75 -> "🧘 Focus Monk" to Color.parseColor("#38BDF8")
            disciplineScore >= 50 -> "⚔️ Focus Warrior" to Color.parseColor("#F59E0B")
            else -> "📱 Reel Rookie" to Color.parseColor("#EF4444")
        }

        binding.tvDisciplineRank.text = rankText
        binding.tvDisciplineRank.setTextColor(rankColor)
        val rankBg = GradientDrawable().apply {
            cornerRadius = dpToPx(6f).toFloat()
            setColor(Color.parseColor("#1E293B"))
            setStroke(dpToPx(1f), rankColor)
        }
        binding.tvDisciplineRank.background = rankBg
        binding.progressDisciplineScore.setIndicatorColor(rankColor)

        binding.tvStreakSubtitle.text = when {
            disciplineScore >= 90 -> "Peak focus! Iron discipline maintained."
            disciplineScore >= 75 -> "Great work! Keep distractions locked away."
            disciplineScore >= 50 -> "Watch your scroll count and stay on track."
            else -> "High usage detected! Time to lock in."
        }
    }

    private fun updateCustomAppsCard() {
        val customPackages = guardManager.prefs.getCustomTrackedPackages()
        binding.tvCustomAppsCountBadge.text = "${customPackages.size} APPS"

        if (customPackages.isEmpty()) {
            binding.tvCustomAppsSummary.text = "No custom apps added yet. Tap '+ Add Custom App' to monitor any app."
        } else {
            val pm = packageManager
            val names = customPackages.map { pkg ->
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    pkg
                }
            }
            binding.tvCustomAppsSummary.text = "Active Custom Shield:\n" + names.joinToString(separator = "  •  ")
        }
    }

    private fun showAddCustomAppDialog() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = pm.queryIntentActivities(mainIntent, 0)

        // Filter out InstaGuard and default core apps
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
                updateCustomAppsCard()
                Toast.makeText(this, "Shield updated: ${currentTracked.size} custom apps monitored!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showMaintenanceModeDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24f), dpToPx(16f), dpToPx(24f), dpToPx(8f))
        }

        val tvInfo = TextView(this).apply {
            text = "Enter your 4-digit Master PIN to unlock a 5-minute Maintenance Window. This temporarily pauses anti-tamper shields for uninstallation or system management."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
        }

        val etPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(Color.WHITE)
            hint = "••••"
            setHintTextColor(Color.parseColor("#64748B"))
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

    private fun updateAnalyticsDashboard() {
        val instaMs = AppBlockerService.getLiveDailyTotalMs("INSTAGRAM", this)
        val ytMs = AppBlockerService.getLiveDailyTotalMs("YOUTUBE", this)
        val fbMs = AppBlockerService.getLiveDailyTotalMs("FACEBOOK", this)
        val ttMs = AppBlockerService.getLiveDailyTotalMs("TIKTOK", this)

        val totalTodayMs = instaMs + ytMs + fbMs + ttMs

        binding.tvAnalyticsTodayTotal.text = "Today's Total Screen Time: " + formatDurationHoursMinutesSeconds(totalTodayMs)

        val totalScrollsToday = AppBlockerService.getTotalScrollsToday(this)
        binding.tvAnalyticsScrollsTotal.text = "🔥 Total Reels/Shorts Scrolled Today: $totalScrollsToday"

        val instaPct = if (totalTodayMs > 0L) ((instaMs * 100) / totalTodayMs).toInt() else 0
        val ytPct = if (totalTodayMs > 0L) ((ytMs * 100) / totalTodayMs).toInt() else 0
        val fbPct = if (totalTodayMs > 0L) ((fbMs * 100) / totalTodayMs).toInt() else 0
        val ttPct = if (totalTodayMs > 0L) ((ttMs * 100) / totalTodayMs).toInt() else 0

        val instaScrolls = AppBlockerService.getScrollToday("INSTAGRAM", this)
        val ytScrolls = AppBlockerService.getScrollToday("YOUTUBE", this)
        val fbScrolls = AppBlockerService.getScrollToday("FACEBOOK", this)
        val ttScrolls = AppBlockerService.getScrollToday("TIKTOK", this)

        binding.tvAnalyticsInstagram.text = "📷 Instagram: ${formatDuration(instaMs)} ($instaPct%) • 🎬 $instaScrolls scrolls"
        binding.progressAnalyticsInstagram.progress = instaPct

        binding.tvAnalyticsYouTube.text = "▶️ YouTube: ${formatDuration(ytMs)} ($ytPct%) • 🎬 $ytScrolls scrolls"
        binding.progressAnalyticsYouTube.progress = ytPct

        binding.tvAnalyticsFacebook.text = "👥 Facebook: ${formatDuration(fbMs)} ($fbPct%) • 🎬 $fbScrolls scrolls"
        binding.progressAnalyticsFacebook.progress = fbPct

        binding.tvAnalyticsTikTok.text = "🎵 TikTok: ${formatDuration(ttMs)} ($ttPct%) • 🎬 $ttScrolls scrolls"
        binding.progressAnalyticsTikTok.progress = ttPct

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - last7DayReportRenderMs > 1500L) {
            last7DayReportRenderMs = now
            render7DayHistoryTable()
        }
    }

    private fun render7DayHistoryTable() {
        val sdfKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sdfDay = SimpleDateFormat("EEE (dd)", Locale.US)
        val cal = Calendar.getInstance()

        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "%-8s | %-4s | %-4s | %-4s | %-4s | %-6s\n", "DAY", "IG", "YT", "FB", "TT", "TOTAL"))
        sb.append("---------------------------------------\n")

        for (i in 0..6) {
            val dateKey = sdfKey.format(cal.time)
            val dayLabel = if (i == 0) "Today" else sdfDay.format(cal.time)

            val ig = if (i == 0) AppBlockerService.getLiveDailyTotalMs("INSTAGRAM", this) else guardManager.prefs.getDailyTotal(dateKey, "INSTAGRAM")
            val yt = if (i == 0) AppBlockerService.getLiveDailyTotalMs("YOUTUBE", this) else guardManager.prefs.getDailyTotal(dateKey, "YOUTUBE")
            val fb = if (i == 0) AppBlockerService.getLiveDailyTotalMs("FACEBOOK", this) else guardManager.prefs.getDailyTotal(dateKey, "FACEBOOK")
            val tt = if (i == 0) AppBlockerService.getLiveDailyTotalMs("TIKTOK", this) else guardManager.prefs.getDailyTotal(dateKey, "TIKTOK")

            val dayTotal = ig + yt + fb + tt

            sb.append(
                String.format(
                    Locale.US,
                    "%-8s | %-4s | %-4s | %-4s | %-4s | %-6s",
                    dayLabel.take(8),
                    formatShortDuration(ig),
                    formatShortDuration(yt),
                    formatShortDuration(fb),
                    formatShortDuration(tt),
                    formatShortDuration(dayTotal)
                )
            )
            if (i < 6) sb.append("\n")

            cal.add(Calendar.DAY_OF_YEAR, -1)
        }

        binding.tvAnalytics7DayReport.text = sb.toString()
    }

    private fun formatDuration(ms: Long): String {
        val safeMs = maxOf(0L, ms)
        val hours = safeMs / 3_600_000
        val minutes = (safeMs % 3_600_000) / 60_000
        val seconds = (safeMs % 60_000) / 1_000
        return if (hours > 0) {
            String.format(Locale.US, "%02dh %02dm %02ds", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02dm %02ds", minutes, seconds)
        }
    }

    private fun formatDurationHoursMinutesSeconds(ms: Long): String {
        val safeMs = maxOf(0L, ms)
        val hours = safeMs / 3_600_000
        val minutes = (safeMs % 3_600_000) / 60_000
        val seconds = (safeMs % 60_000) / 1_000
        return String.format(Locale.US, "%02dh %02dm %02ds", hours, minutes, seconds)
    }

    private fun formatShortDuration(ms: Long): String {
        val safeMs = maxOf(0L, ms)
        val hours = safeMs / 3_600_000
        val minutes = (safeMs % 3_600_000) / 60_000
        return if (hours > 0) {
            String.format(Locale.US, "%dh%02d", hours, minutes)
        } else {
            String.format(Locale.US, "%02dm", minutes)
        }
    }

    private fun dpToPx(dp: Float): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
