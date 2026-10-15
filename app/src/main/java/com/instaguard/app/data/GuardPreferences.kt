package com.instaguard.app.data

import android.content.Context
import android.content.SharedPreferences
import com.instaguard.app.model.TrackedApp

/**
 * Thread-safe wrapper for SharedPreferences to persist strictly isolated per-app
 * real-time usage and hardened 5-hour cooldown timestamps for Instagram, YouTube,
 * Facebook, and TikTok.
 */
class GuardPreferences(context: Context) {

    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        const val PREFS_NAME = "InstaGuardPrefs"

        const val KEY_IS_TEST_MODE = "is_test_mode"

        // Normal Mode: 1 Hour Limit (3,600,000 ms), 5 Hours Cooldown (18,000,000 ms)
        const val USAGE_LIMIT_MS = 60 * 60 * 1000L // 3,600,000 ms (1 hour)
        const val COOLDOWN_DURATION_MS = 5 * 60 * 60 * 1000L // 18,000,000 ms (5 hours)

        // Test Mode: 1 Minute Limit (60,000 ms), 2 Minutes Cooldown (120,000 ms)
        const val TEST_USAGE_LIMIT_MS = 60 * 1000L // 60,000 ms
        const val TEST_COOLDOWN_DURATION_MS = 2 * 60 * 1000L // 120,000 ms

        fun usageKey(appKey: String): String = "usage_ms_${appKey.uppercase()}"
        fun unlockKey(appKey: String): String = "unlock_ms_${appKey.uppercase()}"
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
}
