package com.instaguard.app.data

import android.content.Context
import android.content.SharedPreferences
import com.instaguard.app.model.TrackedApp

/**
 * Thread-safe wrapper for SharedPreferences to persist strictly isolated per-app
 * real-time usage, hardened 5-hour cooldown timestamps, daily history archives,
 * Master PIN, and emergency bypass status for Instagram, YouTube, Facebook, and TikTok.
 */
class GuardPreferences(context: Context) {

    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        const val PREFS_NAME = "InstaGuardPrefs"

        const val KEY_IS_TEST_MODE = "is_test_mode"
        const val KEY_LAST_ACTIVE_DATE = "last_active_date"
        const val KEY_MASTER_PIN = "master_pin"
        const val DEFAULT_MASTER_PIN = "1234"

        // Normal Mode: 30 Minutes Limit (1,800,000 ms), 5 Hours Cooldown (18,000,000 ms)
        const val USAGE_LIMIT_MS = 1_800_000L // 30 * 60 * 1000L = 1,800,000 ms (30 minutes)
        const val COOLDOWN_DURATION_MS = 5 * 60 * 60 * 1000L // 18,000,000 ms (5 hours)

        // Test Mode: 1 Minute Limit (60,000 ms), 2 Minutes Cooldown (120,000 ms)
        const val TEST_USAGE_LIMIT_MS = 60 * 1000L // 60,000 ms
        const val TEST_COOLDOWN_DURATION_MS = 2 * 60 * 1000L // 120,000 ms

        const val KEY_FOCUS_STREAK_DAYS = "focus_streak_days"
        const val KEY_LAST_STREAK_DATE = "last_streak_date"
        const val KEY_CUSTOM_TRACKED_PACKAGES = "custom_tracked_packages"
        const val KEY_MAINTENANCE_UNTIL_MS = "maintenance_until_ms"
        const val KEY_ONLY_REELS_SHORTS_MODE = "only_reels_shorts_mode"
        const val KEY_NIGHT_SLEEP_GUARD_ENABLED = "night_sleep_guard_enabled"
        const val KEY_DEEP_WORK_UNLOCK_MS = "deep_work_unlock_ms"

        // v8.0 Keys & Defaults
        const val KEY_CUSTOM_VOICE_BLOCKED_MSG = "custom_voice_blocked_msg"
        const val DEFAULT_CUSTOM_VOICE_BLOCKED_MSG =
            "Access denied! This app is locked for focus. Stop wasting time and get back to your goals!"

        const val KEY_CUSTOM_VOICE_WARNING_MSG = "custom_voice_warning_msg"
        const val DEFAULT_CUSTOM_VOICE_WARNING_MSG =
            "Please wrap up now and focus on your goals!"

        const val KEY_DOPAMINE_DIMMER_ENABLED = "dopamine_dimmer_enabled"

        fun usageKey(appKey: String): String = "usage_ms_${appKey.uppercase()}"
        fun unlockKey(appKey: String): String = "unlock_ms_${appKey.uppercase()}"
        fun dailyTotalKey(dateStr: String, appKey: String): String = "daily_total_ms_${dateStr}_${appKey.uppercase()}"
        fun emergencyKey(appKey: String): String = "emergency_used_date_${appKey.uppercase()}"
        fun scrollTodayKey(dateStr: String, appKey: String): String = "scroll_today_${dateStr}_${appKey.uppercase()}"
        fun scrollTotalKey(appKey: String): String = "scroll_total_${appKey.uppercase()}"
        fun blockedAttemptsTodayKey(dateStr: String, appKey: String): String = "blocked_attempts_today_${dateStr}_${appKey.uppercase()}"
        fun blockedAttemptsTotalKey(appKey: String): String = "blocked_attempts_total_${appKey.uppercase()}"
        fun blockedAttemptsLogKey(dateStr: String): String = "blocked_attempts_log_${dateStr}"

        // v9.0 Keys
        const val KEY_AI_VISUAL_SHIELD_ENABLED = "ai_visual_shield_enabled"
        fun autoSkippedTodayKey(dateStr: String): String = "nsfw_autoskipped_today_${dateStr}"
        fun browserBlockedTodayKey(dateStr: String): String = "nsfw_browser_blocked_today_${dateStr}"

        // v9.4 Modular Visual Content Filter & Adult Blocker Keys
        const val KEY_AUTO_SKIP_REELS_ENABLED = "auto_skip_reels_enabled"
        const val KEY_BROWSER_ADULT_BLOCK_ENABLED = "browser_adult_block_enabled"
        const val KEY_STRICT_VISUAL_MODE = "strict_visual_mode"
        const val KEY_RULE_SHORT_CLOTHING_ENABLED = "rule_short_clothing_enabled"
        const val KEY_RULE_CHEST_CLEAVAGE_ENABLED = "rule_chest_cleavage_enabled"
        const val KEY_RULE_REAR_BUTTOCKS_ENABLED = "rule_rear_buttocks_enabled"
        const val KEY_RULE_SUGGESTIVE_MOTION_ENABLED = "rule_suggestive_motion_enabled"
        const val KEY_ADULT_SEARCH_BLOCK_ENABLED = "adult_search_block_enabled"
        const val KEY_ADULT_WEBSITES_BLOCK_ENABLED = "adult_websites_block_enabled"
        const val KEY_DOUBLE_PULSE_VIBRATION_ENABLED = "double_pulse_vibration_enabled"
        const val KEY_VIBRATION_PULSE1_MS = "vibration_pulse1_ms"
        const val KEY_VIBRATION_GAP_MS = "vibration_gap_ms"
        const val KEY_VIBRATION_PULSE2_MS = "vibration_pulse2_ms"
    }

    /**
     * Whether Test Mode (1 Min Limit / 2 Min Cooldown) is active.
     */
    var isTestMode: Boolean
        get() = prefs.getBoolean(KEY_IS_TEST_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_TEST_MODE, value).apply()

    fun getUsageLimitMs(): Long {
        return if (isTestMode) TEST_USAGE_LIMIT_MS else USAGE_LIMIT_MS
    }

    fun getCooldownDurationMs(): Long {
        return if (isTestMode) TEST_COOLDOWN_DURATION_MS else COOLDOWN_DURATION_MS
    }

    /**
     * Reads accumulated foreground usage (in ms) for a specific app key.
     */
    fun getAccumulatedUsage(appKey: String): Long {
        return prefs.getLong(usageKey(appKey), 0L)
    }

    /**
     * Persists accumulated foreground usage (in ms) for a specific app key.
     */
    fun setAccumulatedUsage(appKey: String, value: Long) {
        prefs.edit().putLong(usageKey(appKey), maxOf(0L, value)).apply()
    }

    /**
     * Reads cooldown unlock timestamp (epoch ms) for a specific app key.
     */
    fun getUnlockTimestamp(appKey: String): Long {
        return prefs.getLong(unlockKey(appKey), 0L)
    }

    /**
     * Persists cooldown unlock timestamp (epoch ms) for a specific app key.
     */
    fun setUnlockTimestamp(appKey: String, value: Long) {
        prefs.edit().putLong(unlockKey(appKey), maxOf(0L, value)).apply()
    }

    /**
     * Last active date string ("yyyy-MM-dd") to detect midnight 12:00 AM crossovers.
     */
    var lastActiveDate: String
        get() = prefs.getString(KEY_LAST_ACTIVE_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_ACTIVE_DATE, value).apply()

    /**
     * Master PIN for emergency bypass and settings authorization (defaults to "1234").
     */
    var masterPin: String
        get() = prefs.getString(KEY_MASTER_PIN, DEFAULT_MASTER_PIN) ?: DEFAULT_MASTER_PIN
        set(value) = prefs.edit().putString(KEY_MASTER_PIN, value).apply()

    /**
     * Reads daily total usage (in ms) for a specific date and app.
     */
    fun getDailyTotal(dateStr: String, appKey: String): Long {
        return prefs.getLong(dailyTotalKey(dateStr, appKey), 0L)
    }

    /**
     * Persists daily total usage (in ms) for a specific date and app.
     */
    fun setDailyTotal(dateStr: String, appKey: String, value: Long) {
        prefs.edit().putLong(dailyTotalKey(dateStr, appKey), maxOf(0L, value)).apply()
    }

    /**
     * Reads the date string on which emergency bypass was last used for an app.
     */
    fun getEmergencyUsedDate(appKey: String): String {
        return prefs.getString(emergencyKey(appKey), "") ?: ""
    }

    /**
     * Persists the date string on which emergency bypass was used for an app.
     */
    fun setEmergencyUsedDate(appKey: String, dateStr: String) {
        prefs.edit().putString(emergencyKey(appKey), dateStr).apply()
    }

    /**
     * Resets usage and cooldown state for an individual app when cooldown completes naturally.
     */
    fun resetApp(appKey: String) {
        prefs.edit()
            .putLong(usageKey(appKey), 0L)
            .putLong(unlockKey(appKey), 0L)
            .apply()
    }

    /**
     * Resets usage and cooldown for all apps and scrubs legacy keys.
     */
    fun resetAllApps() {
        val editor = prefs.edit()
        for (app in TrackedApp.entries) {
            editor.putLong(usageKey(app.key), 0L)
            editor.putLong(unlockKey(app.key), 0L)
        }
        editor.remove("accumulated_usage_ms")
        editor.remove("insta_foreground_start_ms")
        editor.remove("is_insta_in_foreground")
        editor.remove("unlock_timestamp_ms")
        editor.remove("unlock_time")
        editor.apply()
    }

    var focusStreakDays: Int
        get() = prefs.getInt(KEY_FOCUS_STREAK_DAYS, 1)
        set(value) = prefs.edit().putInt(KEY_FOCUS_STREAK_DAYS, maxOf(1, value)).apply()

    var lastStreakDate: String
        get() = prefs.getString(KEY_LAST_STREAK_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_STREAK_DATE, value).apply()

    var maintenanceUntilMs: Long
        get() = prefs.getLong(KEY_MAINTENANCE_UNTIL_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_MAINTENANCE_UNTIL_MS, value).apply()

    fun getCustomTrackedPackages(): Set<String> {
        return prefs.getStringSet(KEY_CUSTOM_TRACKED_PACKAGES, emptySet()) ?: emptySet()
    }

    fun setCustomTrackedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_CUSTOM_TRACKED_PACKAGES, packages).apply()
    }

    fun getScrollToday(dateStr: String, appKey: String): Int {
        return prefs.getInt(scrollTodayKey(dateStr, appKey), 0)
    }

    fun setScrollToday(dateStr: String, appKey: String, count: Int) {
        prefs.edit().putInt(scrollTodayKey(dateStr, appKey), count).apply()
    }

    fun getScrollTotal(appKey: String): Int {
        return prefs.getInt(scrollTotalKey(appKey), 0)
    }

    fun setScrollTotal(appKey: String, count: Int) {
        prefs.edit().putInt(scrollTotalKey(appKey), count).apply()
    }

    var isOnlyReelsShortsMode: Boolean
        get() = prefs.getBoolean(KEY_ONLY_REELS_SHORTS_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONLY_REELS_SHORTS_MODE, value).apply()

    var isNightSleepGuardEnabled: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_SLEEP_GUARD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NIGHT_SLEEP_GUARD_ENABLED, value).apply()

    var deepWorkUnlockMs: Long
        get() = prefs.getLong(KEY_DEEP_WORK_UNLOCK_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_DEEP_WORK_UNLOCK_MS, maxOf(0L, value)).apply()

    var customVoiceBlockedMsg: String
        get() = prefs.getString(KEY_CUSTOM_VOICE_BLOCKED_MSG, DEFAULT_CUSTOM_VOICE_BLOCKED_MSG)
            ?: DEFAULT_CUSTOM_VOICE_BLOCKED_MSG
        set(value) = prefs.edit().putString(KEY_CUSTOM_VOICE_BLOCKED_MSG, value).apply()

    var customVoiceWarningMsg: String
        get() = prefs.getString(KEY_CUSTOM_VOICE_WARNING_MSG, DEFAULT_CUSTOM_VOICE_WARNING_MSG)
            ?: DEFAULT_CUSTOM_VOICE_WARNING_MSG
        set(value) = prefs.edit().putString(KEY_CUSTOM_VOICE_WARNING_MSG, value).apply()

    var isDopamineDimmerEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOPAMINE_DIMMER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DOPAMINE_DIMMER_ENABLED, value).apply()

    fun getBlockedAttemptsToday(dateStr: String, appKey: String): Int {
        return prefs.getInt(blockedAttemptsTodayKey(dateStr, appKey), 0)
    }

    fun setBlockedAttemptsToday(dateStr: String, appKey: String, count: Int) {
        prefs.edit().putInt(blockedAttemptsTodayKey(dateStr, appKey), maxOf(0, count)).apply()
    }

    fun getBlockedAttemptsTotal(appKey: String): Int {
        return prefs.getInt(blockedAttemptsTotalKey(appKey), 0)
    }

    fun setBlockedAttemptsTotal(appKey: String, count: Int) {
        prefs.edit().putInt(blockedAttemptsTotalKey(appKey), maxOf(0, count)).apply()
    }

    fun getBlockedAttemptsLog(dateStr: String): String {
        return prefs.getString(blockedAttemptsLogKey(dateStr), "") ?: ""
    }

    fun setBlockedAttemptsLog(dateStr: String, log: String) {
        prefs.edit().putString(blockedAttemptsLogKey(dateStr), log).apply()
    }

    var isAiVisualShieldEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_VISUAL_SHIELD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AI_VISUAL_SHIELD_ENABLED, value).apply()

    fun getAutoSkippedToday(dateStr: String): Int {
        return prefs.getInt(autoSkippedTodayKey(dateStr), 0)
    }

    fun setAutoSkippedToday(dateStr: String, count: Int) {
        prefs.edit().putInt(autoSkippedTodayKey(dateStr), maxOf(0, count)).apply()
    }

    fun incrementAutoSkippedToday(dateStr: String): Int {
        val next = getAutoSkippedToday(dateStr) + 1
        setAutoSkippedToday(dateStr, next)
        return next
    }

    fun getBrowserBlockedToday(dateStr: String): Int {
        return prefs.getInt(browserBlockedTodayKey(dateStr), 0)
    }

    fun setBrowserBlockedToday(dateStr: String, count: Int) {
        prefs.edit().putInt(browserBlockedTodayKey(dateStr), maxOf(0, count)).apply()
    }

    fun incrementBrowserBlockedToday(dateStr: String): Int {
        val next = getBrowserBlockedToday(dateStr) + 1
        setBrowserBlockedToday(dateStr, next)
        return next
    }

    // v9.4 Granular Visual Content Filtering & Adult Protection Controls
    var isAutoSkipReelsEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SKIP_REELS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SKIP_REELS_ENABLED, value).apply()

    var isBrowserAdultBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_BROWSER_ADULT_BLOCK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BROWSER_ADULT_BLOCK_ENABLED, value).apply()

    var isStrictVisualMode: Boolean
        get() = prefs.getBoolean(KEY_STRICT_VISUAL_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_STRICT_VISUAL_MODE, value).apply()

    var isRuleShortClothingEnabled: Boolean
        get() = prefs.getBoolean(KEY_RULE_SHORT_CLOTHING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_RULE_SHORT_CLOTHING_ENABLED, value).apply()

    var isRuleChestCleavageEnabled: Boolean
        get() = prefs.getBoolean(KEY_RULE_CHEST_CLEAVAGE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_RULE_CHEST_CLEAVAGE_ENABLED, value).apply()

    var isRuleRearButtocksEnabled: Boolean
        get() = prefs.getBoolean(KEY_RULE_REAR_BUTTOCKS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_RULE_REAR_BUTTOCKS_ENABLED, value).apply()

    var isRuleSuggestiveMotionEnabled: Boolean
        get() = prefs.getBoolean(KEY_RULE_SUGGESTIVE_MOTION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_RULE_SUGGESTIVE_MOTION_ENABLED, value).apply()

    var isAdultSearchBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADULT_SEARCH_BLOCK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ADULT_SEARCH_BLOCK_ENABLED, value).apply()

    var isAdultWebsitesBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADULT_WEBSITES_BLOCK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ADULT_WEBSITES_BLOCK_ENABLED, value).apply()

    var isDoublePulseVibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_PULSE_VIBRATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DOUBLE_PULSE_VIBRATION_ENABLED, value).apply()

    var vibrationPulse1Ms: Long
        get() = prefs.getLong(KEY_VIBRATION_PULSE1_MS, 220L)
        set(value) = prefs.edit().putLong(KEY_VIBRATION_PULSE1_MS, value).apply()

    var vibrationGapMs: Long
        get() = prefs.getLong(KEY_VIBRATION_GAP_MS, 120L)
        set(value) = prefs.edit().putLong(KEY_VIBRATION_GAP_MS, value).apply()

    var vibrationPulse2Ms: Long
        get() = prefs.getLong(KEY_VIBRATION_PULSE2_MS, 280L)
        set(value) = prefs.edit().putLong(KEY_VIBRATION_PULSE2_MS, value).apply()
}
