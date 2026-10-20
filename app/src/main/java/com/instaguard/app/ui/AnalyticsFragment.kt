package com.instaguard.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.instaguard.app.databinding.FragmentAnalyticsBinding
import com.instaguard.app.service.AppBlockerService
import com.instaguard.app.util.GuardManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Tab 2: Analytics Fragment (v12.0)
 * Modern Analytics & Weekly Insights Dashboard:
 * - Today's Total Screen Time & Total Reels/Shorts Scrolled
 * - Per-App Screen Time Breakdown with Dynamic Progress Bars
 * - Impulsive Urges & Blocked Attempts Log
 * - 7-Day Historical Breakdown Table
 */
class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!

    private var last7DayReportRenderMs: Long = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyMonospaceTypefaces()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun applyMonospaceTypefaces() {
        val monospaceViews = listOf(
            binding.tvAnalyticsTodayTotal,
            binding.tvAnalyticsScrollsTotal,
            binding.tvAnalyticsInstagram,
            binding.tvAnalyticsYouTube,
            binding.tvAnalyticsFacebook,
            binding.tvAnalyticsTikTok,
            binding.tvWeaknessTotalToday,
            binding.tvWeaknessAppBreakdown,
            binding.tvWeaknessImpulseLog,
            binding.tvAnalytics7DayReport
        )
        for (v in monospaceViews) {
            v.typeface = Typeface.MONOSPACE
        }
    }

    fun updateAnalytics(guardManager: GuardManager) {
        val ctx = context ?: return
        if (_binding == null) return

        val instaMs = AppBlockerService.getLiveDailyTotalMs("INSTAGRAM", ctx)
        val ytMs = AppBlockerService.getLiveDailyTotalMs("YOUTUBE", ctx)
        val fbMs = AppBlockerService.getLiveDailyTotalMs("FACEBOOK", ctx)
        val ttMs = AppBlockerService.getLiveDailyTotalMs("TIKTOK", ctx)

        val totalTodayMs = instaMs + ytMs + fbMs + ttMs

        binding.tvAnalyticsTodayTotal.text = formatDurationHoursMinutesSeconds(totalTodayMs)

        val totalScrollsToday = AppBlockerService.getTotalScrollsToday(ctx)
        binding.tvAnalyticsScrollsTotal.text = "$totalScrollsToday"

        val instaPct = if (totalTodayMs > 0L) ((instaMs * 100) / totalTodayMs).toInt() else 0
        val ytPct = if (totalTodayMs > 0L) ((ytMs * 100) / totalTodayMs).toInt() else 0
        val fbPct = if (totalTodayMs > 0L) ((fbMs * 100) / totalTodayMs).toInt() else 0
        val ttPct = if (totalTodayMs > 0L) ((ttMs * 100) / totalTodayMs).toInt() else 0

        val instaScrolls = AppBlockerService.getScrollToday("INSTAGRAM", ctx)
        val ytScrolls = AppBlockerService.getScrollToday("YOUTUBE", ctx)
        val fbScrolls = AppBlockerService.getScrollToday("FACEBOOK", ctx)
        val ttScrolls = AppBlockerService.getScrollToday("TIKTOK", ctx)

        binding.tvAnalyticsInstagram.text = "Instagram: ${formatDuration(instaMs)} ($instaPct%) • $instaScrolls scrolls"
        binding.progressAnalyticsInstagram.progress = instaPct

        binding.tvAnalyticsYouTube.text = "YouTube: ${formatDuration(ytMs)} ($ytPct%) • $ytScrolls scrolls"
        binding.progressAnalyticsYouTube.progress = ytPct

        binding.tvAnalyticsFacebook.text = "Facebook: ${formatDuration(fbMs)} ($fbPct%) • $fbScrolls scrolls"
        binding.progressAnalyticsFacebook.progress = fbPct

        binding.tvAnalyticsTikTok.text = "TikTok: ${formatDuration(ttMs)} ($ttPct%) • $ttScrolls scrolls"
        binding.progressAnalyticsTikTok.progress = ttPct

        // Weakness & Impulse Card
        updateWeaknessImpulseCard(ctx)

        // 7-Day History Table (throttled to once every 1.5s for performance)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - last7DayReportRenderMs > 1500L) {
            last7DayReportRenderMs = now
            render7DayHistoryTable(guardManager, ctx)
        }
    }

    private fun updateWeaknessImpulseCard(ctx: android.content.Context) {
        val totalBlockedToday = AppBlockerService.getTotalBlockedAttemptsToday(ctx)
        binding.tvWeaknessTotalToday.text = "Total Blocked Attempts Today: $totalBlockedToday"

        val igBlocked = AppBlockerService.getBlockedAttemptsToday("INSTAGRAM", ctx)
        val ytBlocked = AppBlockerService.getBlockedAttemptsToday("YOUTUBE", ctx)
        val fbBlocked = AppBlockerService.getBlockedAttemptsToday("FACEBOOK", ctx)
        val ttBlocked = AppBlockerService.getBlockedAttemptsToday("TIKTOK", ctx)

        binding.tvWeaknessAppBreakdown.text = "IG: $igBlocked  •  YT: $ytBlocked  •  FB: $fbBlocked  •  TT: $ttBlocked"

        val logText = AppBlockerService.getBlockedAttemptsLogToday(ctx)
        if (logText.isBlank() || totalBlockedToday == 0) {
            binding.tvWeaknessImpulseLog.text = "0 Blocked Attempts Today — 100% Pure Discipline!"
            binding.tvWeaknessImpulseLog.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.tvWeaknessImpulseLog.text = logText
            binding.tvWeaknessImpulseLog.setTextColor(Color.parseColor("#FF8A80"))
        }
    }

    private fun render7DayHistoryTable(guardManager: GuardManager, ctx: android.content.Context) {
        val sdfKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sdfDay = SimpleDateFormat("EEE (dd)", Locale.US)
        val cal = Calendar.getInstance()

        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "%-8s | %-4s | %-4s | %-4s | %-4s | %-6s\n", "DAY", "IG", "YT", "FB", "TT", "TOTAL"))
        sb.append("---------------------------------------\n")

        for (i in 0..6) {
            val dateKey = sdfKey.format(cal.time)
            val dayLabel = if (i == 0) "Today" else sdfDay.format(cal.time)

            val ig = if (i == 0) AppBlockerService.getLiveDailyTotalMs("INSTAGRAM", ctx) else guardManager.prefs.getDailyTotal(dateKey, "INSTAGRAM")
            val yt = if (i == 0) AppBlockerService.getLiveDailyTotalMs("YOUTUBE", ctx) else guardManager.prefs.getDailyTotal(dateKey, "YOUTUBE")
            val fb = if (i == 0) AppBlockerService.getLiveDailyTotalMs("FACEBOOK", ctx) else guardManager.prefs.getDailyTotal(dateKey, "FACEBOOK")
            val tt = if (i == 0) AppBlockerService.getLiveDailyTotalMs("TIKTOK", ctx) else guardManager.prefs.getDailyTotal(dateKey, "TIKTOK")

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
}
