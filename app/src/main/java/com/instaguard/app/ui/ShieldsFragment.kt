package com.instaguard.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.instaguard.app.databinding.FragmentShieldsBinding
import com.instaguard.app.service.AppBlockerService
import com.instaguard.app.util.GuardManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tab 4: Shields Fragment (v15.0 Obsidian & Volt-Lime Design System)
 * Central Command for All Active Defenses:
 * - AI Visual Shield & Safe Browser Guard (with Live HUD diagnostic)
 * - Auto-Skip Suggestive Content Switch
 * - Only Reels & Shorts Blocker (Allows IG DMs & YT Long Form)
 * - Last 5-Min Dopamine Killer Dimmer
 * - Night Sleep Guard (11:30 PM – 6:00 AM) with Math + PIN Anti-Cheat
 * - Master PIN Setup & Management
 */
class ShieldsFragment : Fragment() {

    private var _binding: FragmentShieldsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentShieldsBinding.inflate(inflater, container, false)
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
        binding.tvAutoSkippedCount.typeface = Typeface.MONOSPACE
        binding.tvBrowserBlockedCount.typeface = Typeface.MONOSPACE
        binding.tvScannerDiagnosticLive.typeface = Typeface.MONOSPACE
    }

    private fun setupListeners() {
        binding.btnMasterPinSettings.setOnClickListener {
            (activity as? MainActivity)?.showMasterPinSettingsDialog()
        }

        binding.switchAiVisualShield.setOnCheckedChangeListener { _, isChecked ->
            val mainActivity = activity as? MainActivity ?: return@setOnCheckedChangeListener
            val guardManager = mainActivity.guardManager
            if (!isChecked) {
                mainActivity.promptPinForVisualShieldDisable()
            } else {
                guardManager.prefs.isAiVisualShieldEnabled = true
                refreshShieldsUI(guardManager)
                Toast.makeText(requireContext(), "🛡️ AI Visual Shield & Safe Browser Guard Activated", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchAutoSkipVisual.setOnCheckedChangeListener { _, isChecked ->
            val mainActivity = activity as? MainActivity ?: return@setOnCheckedChangeListener
            mainActivity.guardManager.prefs.isVisualAutoSkipEnabled = isChecked
            val msg = if (isChecked) "⏭️ Auto-Skip Suggestive Videos Enabled" else "⏭️ Auto-Skip Suggestive Videos Disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchOnlyReelsShorts.setOnCheckedChangeListener { _, isChecked ->
            val mainActivity = activity as? MainActivity ?: return@setOnCheckedChangeListener
            mainActivity.guardManager.prefs.isOnlyReelsShortsMode = isChecked
            val msg = if (isChecked) {
                "🎯 Only Reels & Shorts Blocker Active (IG DMs & YT Long Videos Allowed)"
            } else {
                "Standard Full-App Allowance Mode Active"
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchDopamineDimmer.setOnCheckedChangeListener { _, isChecked ->
            val mainActivity = activity as? MainActivity ?: return@setOnCheckedChangeListener
            mainActivity.guardManager.prefs.isDopamineDimmerEnabled = isChecked
            val msg = if (isChecked) {
                "🌑 Last 5-Min Dopamine Dimmer Tint Enabled"
            } else {
                "🌑 Last 5-Min Dopamine Dimmer Tint Disabled"
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchNightSleepGuard.setOnCheckedChangeListener { buttonView, isChecked ->
            val mainActivity = activity as? MainActivity ?: return@setOnCheckedChangeListener
            val guardManager = mainActivity.guardManager
            if (!isChecked && AppBlockerService.isNightSleepTimeWindowNow()) {
                // Anti-Cheat: Require 3-stage math + PIN during 11:30 PM - 6:00 AM
                buttonView.setOnCheckedChangeListener(null)
                buttonView.isChecked = true
                setupListeners()
                Toast.makeText(
                    requireContext(),
                    "🌙 Night Sleep Window is active! Solve 3-Stage Math Challenge + Master PIN to disable.",
                    Toast.LENGTH_LONG
                ).show()
                mainActivity.startNightSleepDisableMathChallenge(step = 1)
                return@setOnCheckedChangeListener
            }

            guardManager.prefs.isNightSleepGuardEnabled = isChecked
            val msg = if (isChecked) {
                "🌙 Night Sleep Guard (11:30 PM – 6:00 AM) Enabled"
            } else {
                "🌙 Night Sleep Guard Disabled"
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshShieldsUI(guardManager: GuardManager) {
        if (context == null || _binding == null) return

        val isVisualEnabled = guardManager.prefs.isAiVisualShieldEnabled
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val autoSkipped = guardManager.prefs.getAutoSkippedToday(todayStr)
        val browserBlocked = guardManager.prefs.getBrowserBlockedToday(todayStr)

        // Temporarily detach listeners to prevent recursive triggers
        binding.switchAiVisualShield.setOnCheckedChangeListener(null)
        binding.switchAiVisualShield.isChecked = isVisualEnabled

        binding.switchAutoSkipVisual.setOnCheckedChangeListener(null)
        binding.switchAutoSkipVisual.isChecked = guardManager.prefs.isVisualAutoSkipEnabled

        binding.switchOnlyReelsShorts.setOnCheckedChangeListener(null)
        binding.switchOnlyReelsShorts.isChecked = guardManager.prefs.isOnlyReelsShortsMode

        binding.switchDopamineDimmer.setOnCheckedChangeListener(null)
        binding.switchDopamineDimmer.isChecked = guardManager.prefs.isDopamineDimmerEnabled

        binding.switchNightSleepGuard.setOnCheckedChangeListener(null)
        binding.switchNightSleepGuard.isChecked = guardManager.prefs.isNightSleepGuardEnabled

        // Re-attach listeners
        setupListeners()

        if (isVisualEnabled) {
            binding.tvAiVisualShieldStatus.text = "ACTIVE • Auto-skips suggestive content & terminates adult searches"
            binding.tvAiVisualShieldStatus.setTextColor(Color.parseColor("#D8FF3E"))
        } else {
            binding.tvAiVisualShieldStatus.text = "PAUSED • Protection disabled"
            binding.tvAiVisualShieldStatus.setTextColor(Color.parseColor("#9BA3B0"))
        }

        binding.tvAutoSkippedCount.text = "🎬 Auto-Skipped Suggestive Reels: $autoSkipped"
        binding.tvBrowserBlockedCount.text = "🚫 Chrome & Google Explicit Searches Blocked: $browserBlocked"
        binding.tvScannerDiagnosticLive.text = "📡 HUD Status: ${AppBlockerService.currentScannerDiagnosticText}"
    }

    fun updateLiveHud() {
        if (_binding == null) return
        binding.tvScannerDiagnosticLive.text = "📡 HUD Status: ${AppBlockerService.currentScannerDiagnosticText}"
    }
}
