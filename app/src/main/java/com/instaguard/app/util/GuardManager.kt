package com.instaguard.app.util

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.instaguard.app.data.GuardPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Core business logic and state machine for InstaGuard.
 * Manages high-precision millisecond usage calculation, cooldown triggers,
 * auto-reset, fallback sync with UsageStatsManager, and time formatting.
 */
class GuardManager(private val context: Context) {

    val prefs = GuardPreferences(context)

    /**
     * Checks if Instagram is currently in an active cooldown lock.
     * Automatically handles cooldown expiration and session reset.
     */
    @Synchronized
    fun isCooldownActive(): Boolean {
        val unlockTime = prefs.unlockTimestampMs
        if (unlockTime == 0L) {
            return false
        }

        val now = System.currentTimeMillis()
        if (now >= unlockTime) {
            // Cooldown has expired: automatically reset session
            prefs.resetSession()
            return false
        }

        return true
    }

    /**
     * Returns remaining milliseconds in the cooldown period.
     * Returns 0L if not locked.
     */
    fun getRemainingCooldownMs(): Long {
        if (!isCooldownActive()) return 0L
        val remaining = prefs.unlockTimestampMs - System.currentTimeMillis()
        return maxOf(0L, remaining)
    }

    /**
     * Calculates the live active foreground usage time of Instagram
     * in the current tracking session (in milliseconds with real-time precision).
     */
    @Synchronized
    fun getCurrentSessionUsageMs(): Long {
        if (isCooldownActive()) {
            return prefs.getUsageLimitMs()
        }

        var total = prefs.accumulatedUsageMs
        if (prefs.isInstaInForeground && prefs.instaForegroundStartMs > 0L) {
            val liveDelta = System.currentTimeMillis() - prefs.instaForegroundStartMs
            if (liveDelta > 0L) {
                total += liveDelta
            }
        }
        return total.coerceAtMost(prefs.getUsageLimitMs())
    }

    /**
     * Returns remaining Instagram allowance in current session (in milliseconds).
     */
    fun getRemainingAllowanceMs(): Long {
        if (isCooldownActive()) return 0L
        val used = getCurrentSessionUsageMs()
        val limit = prefs.getUsageLimitMs()
        return maxOf(0L, limit - used)
    }

    /**
     * Gracefully fallbacks to querying UsageStatsManager if the session timer is at zero
     * (e.g. before AccessibilityService was turned on or on initial launch).
     */
    @Synchronized
    fun syncWithUsageStatsIfEmpty() {
        if (prefs.accumulatedUsageMs == 0L && !prefs.isInstaInForeground && prefs.unlockTimestampMs == 0L) {
            val pastUsage = UsageStatsHelper.getInstagramUsagePastHour(context)
            if (pastUsage > 0L) {
                val limit = prefs.getUsageLimitMs()
                prefs.accumulatedUsageMs = pastUsage.coerceAtMost(limit)
            }
        }
    }

    /**
     * Formats milliseconds into high-precision HH:MM:SS.mmm format.
     */
    fun formatWithMillis(ms: Long): String {
        val safeMs = maxOf(0L, ms)
        val hours = safeMs / 3_600_000
        val minutes = (safeMs % 3_600_000) / 60_000
        val seconds = (safeMs % 60_000) / 1_000
        val millis = safeMs % 1_000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    /**
     * Formats an epoch timestamp into standard time of day (e.g. "06:45 PM").
     */
    fun formatTimeOfDay(epochMs: Long): String {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return sdf.format(Date(epochMs))
    }

    /**
     * Manually resets the session counter and lock state.
     */
    @Synchronized
    fun resetSession() {
        prefs.resetSession()
    }

    companion object {
        /**
         * Checks whether the specified AccessibilityService is enabled in Android Settings.
         */
        fun isAccessibilityServiceEnabled(
            context: Context,
            serviceClass: Class<out AccessibilityService>
        ): Boolean {
            val expectedComponentName = ComponentName(context, serviceClass)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponentName) {
                    return true
                }
            }
            return false
        }
    }
}
