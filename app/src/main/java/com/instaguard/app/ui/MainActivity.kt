package com.instaguard.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.instaguard.app.R
import com.instaguard.app.databinding.ActivityMainBinding
import com.instaguard.app.service.AppBlockerService
import com.instaguard.app.util.GuardManager
import com.instaguard.app.util.UsageStatsHelper

/**
 * Main Dashboard Activity for InstaGuard.
 * Features a high-precision 33ms (~30 FPS) millisecond UI timer,
 * test mode toggle, live usage progress bar, and permission setup cards.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var guardManager: GuardManager
    private val uiHandler = Handler(Looper.getMainLooper())

    private val liveTickerRunnable = object : Runnable {
        override fun run() {
            updateLiveTimerDisplay()
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
        // If timer is at zero, fallback sync with UsageStatsManager
        guardManager.syncWithUsageStatsIfEmpty()

        updatePermissionsUI()
        updateLiveTimerDisplay()

        uiHandler.removeCallbacks(liveTickerRunnable)
        uiHandler.post(liveTickerRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(liveTickerRunnable)
    }

    private fun applyMonospaceTypefaces() {
        binding.tvUsedTime.typeface = Typeface.MONOSPACE
        binding.tvAllowanceLimit.typeface = Typeface.MONOSPACE
        binding.tvRemainingAllowance.typeface = Typeface.MONOSPACE
        binding.tvDashboardCountdown.typeface = Typeface.MONOSPACE
    }

    private fun setupTestModeSwitch() {
        binding.switchTestMode.isChecked = guardManager.prefs.isTestMode
        binding.switchTestMode.setOnCheckedChangeListener { _, isChecked ->
            guardManager.prefs.isTestMode = isChecked
            guardManager.resetSession()
            updateLiveTimerDisplay()
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

        // Refresh Button
        binding.btnRefresh.setOnClickListener {
            updatePermissionsUI()
            guardManager.syncWithUsageStatsIfEmpty()
            updateLiveTimerDisplay()
            Toast.makeText(this, R.string.btn_refresh, Toast.LENGTH_SHORT).show()
        }

        // Reset Session Button
        binding.btnResetSession.setOnClickListener {
            guardManager.resetSession()
            updateLiveTimerDisplay()
            Toast.makeText(this, R.string.session_reset_confirmed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Updates the UI timer every 33ms with exact millisecond precision.
     */
    private fun updateLiveTimerDisplay() {
        val isLocked = guardManager.isCooldownActive()
        val currentUsageMs = guardManager.getCurrentSessionUsageMs()
        val limitMs = guardManager.prefs.getUsageLimitMs()
        val remainingAllowanceMs = guardManager.getRemainingAllowanceMs()

        binding.tvUsedTime.text = guardManager.formatWithMillis(currentUsageMs)
        binding.tvAllowanceLimit.text = "/ " + guardManager.formatWithMillis(limitMs)
        binding.tvRemainingAllowance.text = guardManager.formatWithMillis(remainingAllowanceMs) + " left"

        // Scale progress bar across 1000 units for smooth visual transitions
        val progressInt = if (limitMs > 0L) {
            ((currentUsageMs.toFloat() / limitMs) * 1000).toInt().coerceIn(0, 1000)
        } else 0
        binding.progressUsage.progress = progressInt

        if (isLocked) {
            binding.tvLockBadge.text = "LOCKED"
            binding.tvLockBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            binding.tvLockBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_red_bg)

            binding.layoutLockCooldownInfo.visibility = View.VISIBLE
            val remainingCooldownMs = guardManager.getRemainingCooldownMs()
            binding.tvCooldownTitle.text = if (guardManager.prefs.isTestMode) {
                "Instagram Cooldown Active (2 Min)"
            } else {
                "Instagram Cooldown Active (3 Hours)"
            }
            binding.tvDashboardCountdown.text = "Remaining: " + guardManager.formatWithMillis(remainingCooldownMs)
        } else {
            binding.tvLockBadge.text = "UNLOCKED"
            binding.tvLockBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            binding.tvLockBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_green_bg)

            binding.layoutLockCooldownInfo.visibility = View.GONE
        }
    }

    /**
     * Refreshes permission status indicators.
     */
    private fun updatePermissionsUI() {
        val hasUsage = UsageStatsHelper.hasUsageStatsPermission(this)
        val hasAccessibility = GuardManager.isAccessibilityServiceEnabled(this, AppBlockerService::class.java)
        val isServiceFullyActive = hasUsage && hasAccessibility

        // Overall Protection Status Card
        if (isServiceFullyActive) {
            binding.cardOverallStatus.strokeColor = ContextCompat.getColor(this, R.color.status_green)
            binding.layoutOverallStatusBg.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green_bg))
            binding.imgOverallStatus.setImageResource(R.drawable.ic_check_circle)
            binding.imgOverallStatus.setColorFilter(ContextCompat.getColor(this, R.color.status_green))
            binding.tvOverallStatusTitle.text = getString(R.string.service_active)
            binding.tvOverallStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            binding.tvOverallStatusDesc.text = getString(R.string.service_active_desc)
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
    }
}
