package com.instaguard.app.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.instaguard.app.R
import com.instaguard.app.databinding.FragmentHomeDashboardBinding
import com.instaguard.app.service.AppBlockerService
import com.instaguard.app.util.GuardManager
import java.util.Locale

/**
 * Tab 1: Home Dashboard Fragment (v12.0)
 * Ultra-Premium Digital Wellbeing Dashboard:
 * - Hero Protection Status & Combined Daily Social Allowance
 * - Dedicated Scroll Counter & Active App Monitoring
 * - Focus Streak & Gamified Discipline Score
 * - Instant Deep Work / Study Mode with Cancel Support
 * - 30 FPS Real-Time Usage Cards for Instagram, YouTube, Facebook, TikTok, & Custom Apps.
 */
class HomeDashboardFragment : Fragment() {

    private var _binding: FragmentHomeDashboardBinding? = null
    private val binding get() = _binding!!

    private var statusPulseAnimator: ObjectAnimator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyMonospaceTypefaces()
        setupClickListeners()
        startStatusPulseAnimation()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        statusPulseAnimator?.cancel()
        _binding = null
    }

    private fun applyMonospaceTypefaces() {
        val monospaceViews = listOf(
            binding.tvFocusStreak,
            binding.tvDisciplineScore,
            binding.tvDeepWorkStatus,
            binding.tvDailyRemaining,
            binding.tvTotalScrollsToday,
            binding.tvInstagramTimer, binding.tvInstagramLimit, binding.tvInstagramStatus, binding.tvInstagramScrolls,
            binding.tvYouTubeTimer, binding.tvYouTubeLimit, binding.tvYouTubeStatus, binding.tvYouTubeScrolls,
            binding.tvFacebookTimer, binding.tvFacebookLimit, binding.tvFacebookStatus, binding.tvFacebookScrolls,
            binding.tvTikTokTimer, binding.tvTikTokLimit, binding.tvTikTokStatus, binding.tvTikTokScrolls,
            binding.tvCustomAppsSummary
        )
        for (v in monospaceViews) {
            v.typeface = Typeface.MONOSPACE
        }
    }

    private fun setupClickListeners() {
        binding.btnDeepWork1Hour.setOnClickListener {
            (activity as? MainActivity)?.startDeepWork(3_600_000L)
        }

        binding.btnDeepWork2Hour.setOnClickListener {
            (activity as? MainActivity)?.startDeepWork(7_200_000L)
        }

        binding.btnCancelStudyMode.setOnClickListener {
            (activity as? MainActivity)?.confirmCancelStudyMode()
        }
    }

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
     * Called by MainActivity's 30 FPS live ticker to update all cards and metrics.
     */
    fun updateDashboard(guardManager: GuardManager, hasUsage: Boolean, hasAccessibility: Boolean) {
        val ctx = context ?: return
        if (_binding == null) return

        val prefs = guardManager.prefs
        val limitMs = prefs.getUsageLimitMs()
        val cooldownMs = prefs.getCooldownDurationMs()

        // 1. Overall Status Card & Combined Remaining Allowance
        val isServiceFullyActive = hasUsage && hasAccessibility
        if (isServiceFullyActive) {
            binding.cardOverallStatus.strokeColor = Color.parseColor("#10B981")
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
            binding.imgOverallStatus.setImageResource(R.drawable.ic_check_circle)
            binding.imgOverallStatus.setColorFilter(Color.parseColor("#10B981"))
            binding.tvOverallStatusTitle.text = "PROTECTION ACTIVE"
            binding.tvOverallStatusTitle.setTextColor(Color.parseColor("#10B981"))
            binding.tvOverallStatusDesc.text = "Strict limits and cooldowns actively enforced for all social apps."
        } else {
            binding.cardOverallStatus.strokeColor = Color.parseColor("#FFD600")
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFD600"))
            binding.imgOverallStatus.setImageResource(R.drawable.ic_warning)
            binding.imgOverallStatus.setColorFilter(Color.parseColor("#FFD600"))
            binding.tvOverallStatusTitle.text = "SETUP REQUIRED"
            binding.tvOverallStatusTitle.setTextColor(Color.parseColor("#FFD600"))
            binding.tvOverallStatusDesc.text = "Grant required permissions in Settings to activate full focus protection."
        }

        // Calculate combined allowance remaining across the 4 core apps
        var combinedRemainingMs = 0L
        for (key in AppBlockerService.APP_KEYS) {
            val used = AppBlockerService.getLiveUsageMs(key, ctx)
            val rem = maxOf(0L, limitMs - used)
            combinedRemainingMs += rem
        }
        binding.tvDailyRemaining.text = formatAllowanceTime(combinedRemainingMs)

        val totalScrollsToday = AppBlockerService.getTotalScrollsToday(ctx)
        binding.tvTotalScrollsToday.text = "Scrolls Today: $totalScrollsToday"

        // 2. Focus Streak & Discipline Score
        updateFocusStreakAndDisciplineScore(guardManager, totalScrollsToday)

        // 3. Deep Work / Study Mode
        if (AppBlockerService.isDeepWorkModeActive(ctx)) {
            val deepWorkRem = AppBlockerService.getDeepWorkRemainingMs(ctx)
            binding.tvDeepWorkStatus.text = "Active Study Lock: ${guardManager.formatWithMillis(deepWorkRem)} remaining"
            binding.tvDeepWorkStatus.setTextColor(Color.parseColor("#FF1744"))
            binding.btnCancelStudyMode.visibility = View.VISIBLE
            binding.btnCancelStudyMode.isEnabled = true
            binding.btnCancelStudyMode.alpha = 1.0f
        } else {
            binding.tvDeepWorkStatus.text = "No active Study Mode session"
            binding.tvDeepWorkStatus.setTextColor(Color.parseColor("#8B949E"))
            binding.btnCancelStudyMode.visibility = View.GONE
            binding.btnCancelStudyMode.isEnabled = false
        }

        // 4. Per-App Cards
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
            cooldownMs = cooldownMs,
            guardManager = guardManager,
            ctx = ctx
        )

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
            cooldownMs = cooldownMs,
            guardManager = guardManager,
            ctx = ctx
        )

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
            cooldownMs = cooldownMs,
            guardManager = guardManager,
            ctx = ctx
        )

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
            cooldownMs = cooldownMs,
            guardManager = guardManager,
            ctx = ctx
        )

        // 5. Custom Monitored Apps Card
        updateCustomAppsCard(guardManager, ctx)
    }

    private fun updateFocusStreakAndDisciplineScore(guardManager: GuardManager, totalScrolls: Int) {
        val ctx = context ?: return
        val streak = guardManager.prefs.focusStreakDays
        binding.tvFocusStreak.text = "${streak}-Day Active Streak"

        val lockedCount = AppBlockerService.APP_KEYS.count { AppBlockerService.isAppLocked(it, ctx) }
        val scrollPenalty = if (totalScrolls > 50) (totalScrolls - 50) / 10 else 0
        val disciplineScore = (100 - (lockedCount * 15) - scrollPenalty).coerceIn(10, 100)

        binding.tvDisciplineScore.text = "Discipline Score: $disciplineScore%"
        binding.progressDisciplineScore.progress = disciplineScore

        val (rankText, rankColor) = when {
            disciplineScore >= 90 -> "Grandmaster Focus" to Color.parseColor("#10B981")
            disciplineScore >= 75 -> "Focus Monk" to Color.parseColor("#00E5FF")
            disciplineScore >= 50 -> "Focus Warrior" to Color.parseColor("#FFD600")
            else -> "Reel Rookie" to Color.parseColor("#FF1744")
        }

        binding.tvDisciplineRank.text = rankText
        binding.tvDisciplineRank.setTextColor(rankColor)
        val rankBg = GradientDrawable().apply {
            cornerRadius = dpToPx(6f).toFloat()
            setColor(Color.parseColor("#161B22"))
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
        cooldownMs: Long,
        guardManager: GuardManager,
        ctx: Context
    ) {
        val lockRemaining = AppBlockerService.getRemainingLockMs(appKey, ctx)
        val usedMs = AppBlockerService.getLiveUsageMs(appKey, ctx).coerceAtMost(limitMs)
        val isForegroundActive = AppBlockerService.currentForegroundAppKey == appKey

        val todayScrolls = AppBlockerService.getScrollToday(appKey, ctx)
        val totalScrolls = AppBlockerService.getScrollTotal(appKey, ctx)
        val blockedToday = AppBlockerService.getBlockedAttemptsToday(appKey, ctx)
        tvScrolls.text = "Today: $todayScrolls scrolls • Blocked: $blockedToday • Total: $totalScrolls"

        if (lockRemaining > 0L) {
            // LOCKED STATE (Crisp Red Border)
            card.strokeColor = Color.parseColor("#FF1744")
            card.strokeWidth = dpToPx(1.5f)

            tvBadge.text = "LOCKED (5H COOLDOWN)"
            tvBadge.setTextColor(Color.parseColor("#FF1744"))
            val badgeBg = GradientDrawable().apply {
                cornerRadius = dpToPx(6f).toFloat()
                setColor(Color.parseColor("#3F1212"))
            }
            tvBadge.background = badgeBg

            tvTimer.text = guardManager.formatWithMillis(limitMs)
            tvTimer.setTextColor(Color.parseColor("#FF1744"))

            tvLimit.text = "/ " + guardManager.formatWithMillis(limitMs)
            tvStatus.text = "Unlocks in: " + guardManager.formatWithMillis(lockRemaining)
            tvStatus.setTextColor(Color.parseColor("#FF8A80"))

            val drainProgress = if (cooldownMs > 0L) {
                ((lockRemaining.toFloat() / cooldownMs) * 1000).toInt().coerceIn(0, 1000)
            } else 0
            progressIndicator.setIndicatorColor(Color.parseColor("#FF1744"))
            progressIndicator.progress = drainProgress
        } else {
            // ALLOWED STATE
            if (isForegroundActive) {
                card.strokeColor = Color.parseColor("#10B981")
                card.strokeWidth = dpToPx(1.5f)
            } else {
                card.strokeColor = Color.parseColor("#21262D")
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
            tvTimer.setTextColor(Color.parseColor("#00E5FF")) // Neon Cyan

            tvLimit.text = "/ " + guardManager.formatWithMillis(limitMs)

            val remainingAllowance = maxOf(0L, limitMs - usedMs)
            tvStatus.text = guardManager.formatWithMillis(remainingAllowance) + " left"
            tvStatus.setTextColor(Color.parseColor("#8B949E"))

            val progress = if (limitMs > 0L) {
                ((usedMs.toFloat() / limitMs) * 1000).toInt().coerceIn(0, 1000)
            } else 0
            progressIndicator.progress = progress

            when {
                progress >= 950 -> progressIndicator.setIndicatorColor(Color.parseColor("#FF1744"))
                progress >= 800 -> progressIndicator.setIndicatorColor(Color.parseColor("#FFD600"))
                else -> progressIndicator.setIndicatorColor(Color.parseColor("#10B981"))
            }
        }
    }

    private fun updateCustomAppsCard(guardManager: GuardManager, ctx: Context) {
        val customPackages = guardManager.prefs.getCustomTrackedPackages()
        binding.tvCustomAppsCountBadge.text = "${customPackages.size} APPS"

        if (customPackages.isEmpty()) {
            binding.tvCustomAppsSummary.text = "No custom apps added yet. Go to Settings tab to monitor any installed app."
        } else {
            val pm = ctx.packageManager
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

    private fun formatAllowanceTime(ms: Long): String {
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

    private fun dpToPx(dp: Float): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
