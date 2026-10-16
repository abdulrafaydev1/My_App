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
import android.view.View
import android.widget.EditText
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
 * Main Dashboard Activity for InstaGuard Pro v5.0.
 * Features a Cyber-Dark UI, strictly isolated per-app 1h limit and 5h cooldown monitoring,
 * smooth entrance and pulse animations, dynamic border highlights, Master PIN settings,
 * 7-day usage analytics dashboard, and anti-bypass guards.
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
            binding.tvInstagramTimer, binding.tvInstagramLimit, binding.tvInstagramStatus,
            binding.tvYouTubeTimer, binding.tvYouTubeLimit, binding.tvYouTubeStatus,
            binding.tvFacebookTimer, binding.tvFacebookLimit, binding.tvFacebookStatus,
            binding.tvTikTokTimer, binding.tvTikTokLimit, binding.tvTikTokStatus,
            binding.tvAnalyticsTodayTotal, binding.tvAnalyticsInstagram, binding.tvAnalyticsYouTube,
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

        val viewsToAnimate = listOf(
            binding.layoutHeader,
            binding.cardOverallStatus,
            binding.cardTestMode,
            binding.btnMasterPinSettings,
            binding.layoutSectionTitle,
            binding.cardInstagram,
            binding.cardYouTube,
            binding.cardFacebook,
            binding.cardTikTok,
            binding.cardAnalyticsReport
        )

        for ((index, view) in viewsToAnimate.withIndex()) {
            view.alpha = 0f
            view.translationY = 60f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(350)
                .setStartDelay((index * 60).toLong())
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
    }

    /**
     * Updates each of the 4 cards strictly using its independent app key at 30 FPS.
     */
    private fun updateAllAppCards() {
        val prefs = guardManager.prefs
        val limitMs = prefs.getUsageLimitMs()
        val cooldownMs = prefs.getCooldownDurationMs()

        // 1. Instagram
        renderAppCard(
            appKey = "INSTAGRAM",
            card = binding.cardInstagram,
            tvBadge = binding.tvInstagramBadge,
            tvTimer = binding.tvInstagramTimer,
            tvLimit = binding.tvInstagramLimit,
            tvStatus = binding.tvInstagramStatus,
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
            progressIndicator = binding.progressTikTok,
            limitMs = limitMs,
            cooldownMs = cooldownMs
        )

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
        progressIndicator: LinearProgressIndicator,
        limitMs: Long,
        cooldownMs: Long
    ) {
        val lockRemaining = AppBlockerService.getRemainingLockMs(appKey, this)
        val usedMs = AppBlockerService.getLiveUsageMs(appKey, this).coerceAtMost(limitMs)
        val isForegroundActive = AppBlockerService.currentForegroundAppKey == appKey

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

    private fun updateAnalyticsDashboard() {
        val instaMs = AppBlockerService.getLiveDailyTotalMs("INSTAGRAM", this)
        val ytMs = AppBlockerService.getLiveDailyTotalMs("YOUTUBE", this)
        val fbMs = AppBlockerService.getLiveDailyTotalMs("FACEBOOK", this)
        val ttMs = AppBlockerService.getLiveDailyTotalMs("TIKTOK", this)

        val totalTodayMs = instaMs + ytMs + fbMs + ttMs

        binding.tvAnalyticsTodayTotal.text = "Today's Total Screen Time: " + formatDurationHoursMinutesSeconds(totalTodayMs)

        val instaPct = if (totalTodayMs > 0L) ((instaMs * 100) / totalTodayMs).toInt() else 0
        val ytPct = if (totalTodayMs > 0L) ((ytMs * 100) / totalTodayMs).toInt() else 0
        val fbPct = if (totalTodayMs > 0L) ((fbMs * 100) / totalTodayMs).toInt() else 0
        val ttPct = if (totalTodayMs > 0L) ((ttMs * 100) / totalTodayMs).toInt() else 0

        binding.tvAnalyticsInstagram.text = "📷 Instagram: ${formatDuration(instaMs)} ($instaPct%)"
        binding.progressAnalyticsInstagram.progress = instaPct

        binding.tvAnalyticsYouTube.text = "▶️ YouTube: ${formatDuration(ytMs)} ($ytPct%)"
        binding.progressAnalyticsYouTube.progress = ytPct

        binding.tvAnalyticsFacebook.text = "👥 Facebook: ${formatDuration(fbMs)} ($fbPct%)"
        binding.progressAnalyticsFacebook.progress = fbPct

        binding.tvAnalyticsTikTok.text = "🎵 TikTok: ${formatDuration(ttMs)} ($ttPct%)"
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
