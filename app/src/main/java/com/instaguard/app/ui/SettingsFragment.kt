package com.instaguard.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.instaguard.app.R
import com.instaguard.app.data.GuardPreferences
import com.instaguard.app.databinding.FragmentSettingsBinding
import com.instaguard.app.service.AppBlockerService
import com.instaguard.app.util.GuardManager
import com.instaguard.app.util.UsageStatsHelper

/**
 * Tab 4: Settings Fragment (v12.0)
 * System Configuration & Preferences:
 * - Custom AI Voice Alert (Edit & Test Voice preview)
 * - 5-Minute PIN-Protected Maintenance Mode
 * - Custom App Selector (+ Add Custom Monitored App)
 * - Test Mode Toggle (1m Limit / 2m Lock) with Anti-Bypass Guard
 * - System Permissions Management (Accessibility, Usage Access, Overlay)
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyMonospaceTypefaces()
        setupListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun applyMonospaceTypefaces() {
        binding.tvCurrentCustomVoice.typeface = Typeface.MONOSPACE
    }

    private fun setupListeners() {
        val mainActivity = activity as? MainActivity

        binding.btnEditCustomVoice.setOnClickListener {
            mainActivity?.showEditCustomVoiceDialog()
        }

        binding.btnTestCustomVoice.setOnClickListener {
            mainActivity?.testCustomVoiceMessageNow()
        }

        binding.btnMaintenanceMode.setOnClickListener {
            mainActivity?.showMaintenanceModeDialog()
        }

        binding.btnAddCustomApp.setOnClickListener {
            mainActivity?.showAddCustomAppDialog()
        }

        binding.btnGrantUsage.setOnClickListener {
            mainActivity?.openUsageSettings()
        }

        binding.btnEnableAccessibility.setOnClickListener {
            mainActivity?.openAccessibilitySettings()
        }

        binding.btnGrantOverlay.setOnClickListener {
            mainActivity?.openOverlaySettings()
        }

        setupTestModeSwitch()
    }

    private fun setupTestModeSwitch() {
        val mainActivity = activity as? MainActivity ?: return
        val guardManager = mainActivity.guardManager

        binding.switchTestMode.setOnCheckedChangeListener(null)
        binding.switchTestMode.isChecked = guardManager.prefs.isTestMode
        binding.switchTestMode.setOnCheckedChangeListener { buttonView, isChecked ->
            val ctx = requireContext()
            val anyLocked = AppBlockerService.APP_KEYS.any { AppBlockerService.isAppLocked(it, ctx) }
            val anyActiveSession = AppBlockerService.APP_KEYS.any { AppBlockerService.getLiveUsageMs(it, ctx) > 120_000L }

            if (anyLocked || anyActiveSession) {
                Toast.makeText(ctx, "Cannot switch mode while sessions or locks are active!", Toast.LENGTH_LONG).show()
                buttonView.setOnCheckedChangeListener(null)
                buttonView.isChecked = guardManager.prefs.isTestMode
                setupTestModeSwitch()
                return@setOnCheckedChangeListener
            }

            guardManager.prefs.isTestMode = isChecked
            mainActivity.notifyModeChanged()
            val msg = if (isChecked) {
                "Test Mode (1m Limit / 2m Lock) Enabled"
            } else {
                "Normal Mode (30m Limit / 5h Lock) Enabled"
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshSettingsUI(guardManager: GuardManager) {
        val ctx = context ?: return
        if (_binding == null) return

        // 1. Voice Alert Text
        val currentMsg = guardManager.prefs.customVoiceBlockedMsg.trim()
            .ifEmpty { GuardPreferences.DEFAULT_CUSTOM_VOICE_BLOCKED_MSG }
        binding.tvCurrentCustomVoice.text = "“$currentMsg”"

        // 2. Test Mode Switch
        setupTestModeSwitch()

        // 3. Permissions Check
        val hasUsage = UsageStatsHelper.hasUsageStatsPermission(ctx)
        val hasAccessibility = GuardManager.isAccessibilityServiceEnabled(ctx, AppBlockerService::class.java)
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(ctx)
        } else true

        // Usage Access
        if (hasUsage) {
            binding.imgUsageStatus.setImageResource(R.drawable.ic_check_circle)
            binding.imgUsageStatus.setColorFilter(Color.parseColor("#10B981"))
            binding.tvUsageStatusBadge.text = getString(R.string.status_granted)
            binding.tvUsageStatusBadge.setTextColor(Color.parseColor("#10B981"))
            binding.btnGrantUsage.isEnabled = false
            binding.btnGrantUsage.text = getString(R.string.status_granted)
        } else {
            binding.imgUsageStatus.setImageResource(R.drawable.ic_warning)
            binding.imgUsageStatus.setColorFilter(Color.parseColor("#FFD600"))
            binding.tvUsageStatusBadge.text = getString(R.string.status_action_required)
            binding.tvUsageStatusBadge.setTextColor(Color.parseColor("#FFD600"))
            binding.btnGrantUsage.isEnabled = true
            binding.btnGrantUsage.text = getString(R.string.btn_grant_usage_access)
        }

        // Accessibility Service
        if (hasAccessibility) {
            binding.imgAccessibilityStatus.setImageResource(R.drawable.ic_check_circle)
            binding.imgAccessibilityStatus.setColorFilter(Color.parseColor("#10B981"))
            binding.tvAccessibilityStatusBadge.text = getString(R.string.status_granted)
            binding.tvAccessibilityStatusBadge.setTextColor(Color.parseColor("#10B981"))
            binding.btnEnableAccessibility.isEnabled = false
            binding.btnEnableAccessibility.text = getString(R.string.status_granted)
        } else {
            binding.imgAccessibilityStatus.setImageResource(R.drawable.ic_warning)
            binding.imgAccessibilityStatus.setColorFilter(Color.parseColor("#FFD600"))
            binding.tvAccessibilityStatusBadge.text = getString(R.string.status_action_required)
            binding.tvAccessibilityStatusBadge.setTextColor(Color.parseColor("#FFD600"))
            binding.btnEnableAccessibility.isEnabled = true
            binding.btnEnableAccessibility.text = getString(R.string.btn_enable_accessibility)
        }

        // Display Over Other Apps (Overlay)
        if (hasOverlay) {
            binding.imgOverlayStatus.setImageResource(R.drawable.ic_check_circle)
            binding.imgOverlayStatus.setColorFilter(Color.parseColor("#10B981"))
            binding.tvOverlayStatusBadge.text = getString(R.string.status_granted)
            binding.tvOverlayStatusBadge.setTextColor(Color.parseColor("#10B981"))
            binding.btnGrantOverlay.isEnabled = false
            binding.btnGrantOverlay.text = getString(R.string.status_granted)
        } else {
            binding.imgOverlayStatus.setImageResource(R.drawable.ic_warning)
            binding.imgOverlayStatus.setColorFilter(Color.parseColor("#FFD600"))
            binding.tvOverlayStatusBadge.text = getString(R.string.status_action_required)
            binding.tvOverlayStatusBadge.setTextColor(Color.parseColor("#FFD600"))
            binding.btnGrantOverlay.isEnabled = true
            binding.btnGrantOverlay.text = getString(R.string.btn_grant_overlay)
        }
    }
}
