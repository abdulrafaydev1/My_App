package com.instaguard.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.instaguard.app.R
import com.instaguard.app.data.GuardPreferences
import com.instaguard.app.databinding.ActivityMainBinding
import com.instaguard.app.service.AppBlockerService
import com.instaguard.app.util.GuardManager
import com.instaguard.app.util.UsageStatsHelper

/**
 * Main Dashboard Activity for InstaGuard.
 * Provides permission setup controls, service status monitor,
 * live usage metrics, and restricted settings guidance.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var guardManager: GuardManager
    private val uiHandler = Handler(Looper.getMainLooper())

    private val liveUpdateRunnable = object : Runnable {
        override fun run() {
            refreshDashboard()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        guardManager = GuardManager(applicationContext)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
        uiHandler.removeCallbacks(liveUpdateRunnable)
        uiHandler.post(liveUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(liveUpdateRunnable)
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
            refreshDashboard()
            Toast.makeText(this, R.string.btn_refresh, Toast.LENGTH_SHORT).show()
        }

        // Reset Session Button
        binding.btnResetSession.setOnClickListener {
            guardManager.resetSession()
            refreshDashboard()
            Toast.makeText(this, R.string.session_reset_confirmed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Refreshes permission status indicators, usage progress bar, and lock state.
     */
    private fun refreshDashboard() {
        val hasUsage = UsageStatsHelper.hasUsageStatsPermission(this)
        val hasAccessibility = GuardManager.isAccessibilityServiceEnabled(this, AppBlockerService::class.java)

        updatePermissionsUI(hasUsage, hasAccessibility)
        updateUsageDashboard()
    }

    private fun updatePermissionsUI(hasUsage: Boolean, hasAccessibility: Boolean) {
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

    private fun updateUsageDashboard() {
        val isLocked = guardManager.isCooldownActive()
        val currentUsageMs = guardManager.getCurrentSessionUsageMs()
        val remainingAllowanceMs = guardManager.getRemainingAllowanceMs()

        val progressPercent = ((currentUsageMs.toFloat() / GuardPreferences.USAGE_LIMIT_MS) * 100).toInt().coerceIn(0, 100)
        binding.progressUsage.progress = progressPercent

        binding.tvUsedTime.text = guardManager.formatMinutesSeconds(currentUsageMs)
        binding.tvRemainingAllowance.text = getString(
            R.string.usage_remaining_format,
            guardManager.formatMinutesSeconds(remainingAllowanceMs)
        )

        if (isLocked) {
            binding.tvLockBadge.text = "LOCKED"
            binding.tvLockBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            binding.tvLockBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_red_bg)

            binding.layoutLockCooldownInfo.visibility = View.VISIBLE
            val remainingCooldownMs = guardManager.getRemainingCooldownMs()
            binding.tvDashboardCountdown.text = "Cooldown Remaining: " + guardManager.formatCountdown(remainingCooldownMs)
        } else {
            binding.tvLockBadge.text = "UNLOCKED"
            binding.tvLockBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            binding.tvLockBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_green_bg)

            binding.layoutLockCooldownInfo.visibility = View.GONE
        }
    }
}
