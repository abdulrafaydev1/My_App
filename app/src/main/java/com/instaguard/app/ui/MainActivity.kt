package com.instaguard.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.instaguard.app.R
import com.instaguard.app.databinding.ActivityMainBinding
import com.instaguard.app.service.AppBlockerService
import com.instaguard.app.util.GuardManager
import com.instaguard.app.util.UsageStatsHelper

/**
 * Main Dashboard Activity for InstaGuard v3.3 (Isolated Multi-App Focus).
 * Displays strictly isolated real-time millisecond usage and cooldown timers for
 * Instagram, YouTube, Facebook, and TikTok ticking smoothly at 33ms (~30 FPS).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var guardManager: GuardManager
    private val uiHandler = Handler(Looper.getMainLooper())

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

        applyMonospaceTypefaces()
        setupListeners()
        setupTestModeSwitch()
    }

    override fun onResume() {
        super.onResume()
        // Sync in-memory states strictly from per-app preferences
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

    private fun applyMonospaceTypefaces() {
        // Monospace all timer textviews to prevent digit width jittering
        val monospaceViews = listOf(
            binding.tvInstagramTimer, binding.tvInstagramLimit, binding.tvInstagramStatus,
            binding.tvYouTubeTimer, binding.tvYouTubeLimit, binding.tvYouTubeStatus,
            binding.tvFacebookTimer, binding.tvFacebookLimit, binding.tvFacebookStatus,
            binding.tvTikTokTimer, binding.tvTikTokLimit, binding.tvTikTokStatus
        )
        for (view in monospaceViews) {
            view.typeface = Typeface.MONOSPACE
        }
    }

    private fun setupTestModeSwitch() {
        binding.switchTestMode.isChecked = guardManager.prefs.isTestMode
        binding.switchTestMode.setOnCheckedChangeListener { _, isChecked ->
            guardManager.prefs.isTestMode = isChecked
            AppBlockerService.resetAll(this)
            updateAllAppCards()
            val msg = if (isChecked) {
                "Test Mode (1m Limit / 2m Lock) Enabled"
            } else {
                "Normal Mode (1h Limit / 3h Lock) Enabled"
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

        // Reset All Timers Button
        binding.btnResetAllTimers.setOnClickListener {
            AppBlockerService.resetAll(this)
            updateAllAppCards()
            Toast.makeText(this, "All social app timers have been reset.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Updates each of the 4 cards strictly using its independent app key.
     */
    private fun updateAllAppCards() {
        val prefs = guardManager.prefs
        val limitMs = prefs.getUsageLimitMs()

        // 1. Instagram
        renderAppCard(
            appKey = "INSTAGRAM",
            tvBadge = binding.tvInstagramBadge,
            tvTimer = binding.tvInstagramTimer,
            tvLimit = binding.tvInstagramLimit,
            tvStatus = binding.tvInstagramStatus,
            progressIndicator = binding.progressInstagram,
            limitMs = limitMs
        )

        // 2. YouTube
        renderAppCard(
            appKey = "YOUTUBE",
            tvBadge = binding.tvYouTubeBadge,
            tvTimer = binding.tvYouTubeTimer,
            tvLimit = binding.tvYouTubeLimit,
            tvStatus = binding.tvYouTubeStatus,
            progressIndicator = binding.progressYouTube,
            limitMs = limitMs
        )

        // 3. Facebook
        renderAppCard(
            appKey = "FACEBOOK",
            tvBadge = binding.tvFacebookBadge,
            tvTimer = binding.tvFacebookTimer,
            tvLimit = binding.tvFacebookLimit,
            tvStatus = binding.tvFacebookStatus,
            progressIndicator = binding.progressFacebook,
            limitMs = limitMs
        )

        // 4. TikTok
        renderAppCard(
            appKey = "TIKTOK",
            tvBadge = binding.tvTikTokBadge,
            tvTimer = binding.tvTikTokTimer,
            tvLimit = binding.tvTikTokLimit,
            tvStatus = binding.tvTikTokStatus,
            progressIndicator = binding.progressTikTok,
            limitMs = limitMs
        )
    }

    private fun renderAppCard(
        appKey: String,
        tvBadge: TextView,
        tvTimer: TextView,
        tvLimit: TextView,
        tvStatus: TextView,
        progressIndicator: LinearProgressIndicator,
        limitMs: Long
    ) {
        val lockRemaining = AppBlockerService.getRemainingLockMs(appKey, this)
        val usedMs = AppBlockerService.getLiveUsageMs(appKey, this).coerceAtMost(limitMs)

        if (lockRemaining > 0L) {
            tvBadge.text = "LOCKED"
            tvBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            tvBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_red_bg)

            tvTimer.text = guardManager.formatWithMillis(limitMs)
            tvLimit.text = "/ " + guardManager.formatWithMillis(limitMs)
            tvStatus.text = "Unlocks in: " + guardManager.formatWithMillis(lockRemaining)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))

            progressIndicator.progress = 1000
        } else {
            tvBadge.text = "ALLOWED"
            tvBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            tvBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_green_bg)

            tvTimer.text = guardManager.formatWithMillis(usedMs)
            tvLimit.text = "/ " + guardManager.formatWithMillis(limitMs)

            val remainingAllowance = maxOf(0L, limitMs - usedMs)
            tvStatus.text = guardManager.formatWithMillis(remainingAllowance) + " left"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary_light))

            val progress = if (limitMs > 0L) {
                ((usedMs.toFloat() / limitMs) * 1000).toInt().coerceIn(0, 1000)
            } else 0
            progressIndicator.progress = progress
        }
    }

    /**
     * Refreshes permission status indicators.
     */
    private fun updatePermissionsUI() {
        val hasUsage = UsageStatsHelper.hasUsageStatsPermission(this)
        val hasAccessibility = GuardManager.isAccessibilityServiceEnabled(this, AppBlockerService::class.java)
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        val isServiceFullyActive = hasUsage && hasAccessibility

        if (isServiceFullyActive) {
            binding.cardOverallStatus.strokeColor = ContextCompat.getColor(this, R.color.status_green)
            binding.layoutOverallStatusBg.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green_bg))
            binding.imgOverallStatus.setImageResource(R.drawable.ic_check_circle)
            binding.imgOverallStatus.setColorFilter(ContextCompat.getColor(this, R.color.status_green))
            binding.tvOverallStatusTitle.text = getString(R.string.service_active)
            binding.tvOverallStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            binding.tvOverallStatusDesc.text = "Active protection for Instagram, YouTube, Facebook & TikTok."
        } else {
            binding.cardOverallStatus.strokeColor = ContextCompat.getColor(this, R.color.status_amber)
            binding.layoutOverallStatusBg.setBackgroundColor(ContextCompat.getColor(this, R.color.status_amber_bg))
            binding.imgOverallStatus.setImageResource(R.drawable.ic_warning)
            binding.imgOverallStatus.setColorFilter(ContextCompat.getColor(this, R.color.status_amber))
            binding.tvOverallStatusTitle.text = getString(R.string.service_inactive)
            binding.tvOverallStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            binding.tvOverallStatusDesc.text = getString(R.string.service_inactive_desc)
        }

        // Usage Access Permission Card
        if (hasUsage) {
            binding.imgUsageStatus.setImageResource(R.drawable.ic_check_circle)
            binding.imgUsageStatus.setColorFilter(ContextCompat.getColor(this, R.color.status_green))
            binding.tvUsageStatusBadge.text = getString(R.string.status_granted)
            binding.tvUsageStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            binding.btnGrantUsage.isEnabled = false
            binding.btnGrantUsage.text = getString(R.string.status_granted)
        } else {
            binding.imgUsageStatus.setImageResource(R.drawable.ic_warning)
            binding.imgUsageStatus.setColorFilter(ContextCompat.getColor(this, R.color.status_amber))
            binding.tvUsageStatusBadge.text = getString(R.string.status_action_required)
            binding.tvUsageStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            binding.btnGrantUsage.isEnabled = true
            binding.btnGrantUsage.text = getString(R.string.btn_grant_usage_access)
        }

        // Accessibility Service Permission Card
        if (hasAccessibility) {
            binding.imgAccessibilityStatus.setImageResource(R.drawable.ic_check_circle)
            binding.imgAccessibilityStatus.setColorFilter(ContextCompat.getColor(this, R.color.status_green))
            binding.tvAccessibilityStatusBadge.text = getString(R.string.status_granted)
            binding.tvAccessibilityStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            binding.btnEnableAccessibility.isEnabled = false
            binding.btnEnableAccessibility.text = getString(R.string.status_granted)
        } else {
            binding.imgAccessibilityStatus.setImageResource(R.drawable.ic_warning)
            binding.imgAccessibilityStatus.setColorFilter(ContextCompat.getColor(this, R.color.status_amber))
            binding.tvAccessibilityStatusBadge.text = getString(R.string.status_action_required)
            binding.tvAccessibilityStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            binding.btnEnableAccessibility.isEnabled = true
            binding.btnEnableAccessibility.text = getString(R.string.btn_enable_accessibility)
        }

        // Overlay Permission Card (PiP Defense)
        if (hasOverlay) {
            binding.imgOverlayStatus.setImageResource(R.drawable.ic_check_circle)
            binding.imgOverlayStatus.setColorFilter(ContextCompat.getColor(this, R.color.status_green))
            binding.tvOverlayStatusBadge.text = getString(R.string.status_granted)
            binding.tvOverlayStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            binding.btnGrantOverlay.isEnabled = false
            binding.btnGrantOverlay.text = getString(R.string.status_granted)
        } else {
            binding.imgOverlayStatus.setImageResource(R.drawable.ic_warning)
            binding.imgOverlayStatus.setColorFilter(ContextCompat.getColor(this, R.color.status_amber))
            binding.tvOverlayStatusBadge.text = getString(R.string.status_action_required)
            binding.tvOverlayStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            binding.btnGrantOverlay.isEnabled = true
            binding.btnGrantOverlay.text = getString(R.string.btn_grant_overlay)
        }
    }
}
