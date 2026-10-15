package com.instaguard.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Thread-safe wrapper for SharedPreferences to persist real-time session usage,
 * foreground start timestamps, cooldown lock states, and test mode preferences.
 */
class GuardPreferences(context: Context) {

    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        const val PREFS_NAME = "InstaGuardPrefs"

        const val KEY_ACCUMULATED_USAGE_MS = "accumulated_usage_ms"
        const val KEY_INSTA_FOREGROUND_START_MS = "insta_foreground_start_ms"
        const val KEY_IS_INSTA_IN_FOREGROUND = "is_insta_in_foreground"
        const val KEY_UNLOCK_TIMESTAMP_MS = "unlock_timestamp_ms"
        const val KEY_IS_TEST_MODE = "is_test_mode"

        const val TARGET_PACKAGE = "com.instagram.android"

        // Normal Mode: 1 Hour Limit (3,600,000 ms), 3 Hours Cooldown (10,800,000 ms)
        const val USAGE_LIMIT_MS = 60 * 60 * 1000L // 3,600,000 ms
        const val COOLDOWN_DURATION_MS = 3 * 60 * 60 * 1000L // 10,800,000 ms

        // Test Mode: 1 Minute Limit (60,000 ms), 2 Minutes Cooldown (120,000 ms)
        const val TEST_USAGE_LIMIT_MS = 60 * 1000L // 60,000 ms
        const val TEST_COOLDOWN_DURATION_MS = 2 * 60 * 1000L // 120,000 ms
    }

    /**
     * Total milliseconds Instagram has been used in current allowance cycle.
     */
    var accumulatedUsageMs: Long
        get() = prefs.getLong(KEY_ACCUMULATED_USAGE_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_ACCUMULATED_USAGE_MS, value).apply()

    /**
     * Timestamp (epoch ms) when Instagram entered the foreground.
     * 0L if Instagram is not currently in the foreground.
     */
    var instaForegroundStartMs: Long
        get() = prefs.getLong(KEY_INSTA_FOREGROUND_START_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_INSTA_FOREGROUND_START_MS, value).apply()

    /**
     * Whether com.instagram.android is currently the active window.
     */
    var isInstaInForeground: Boolean
        get() = prefs.getBoolean(KEY_IS_INSTA_IN_FOREGROUND, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_INSTA_IN_FOREGROUND, value).apply()

    /**
     * Timestamp when the cooldown ends (0L if not locked).
     */
    var unlockTimestampMs: Long
        get() = prefs.getLong(KEY_UNLOCK_TIMESTAMP_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_UNLOCK_TIMESTAMP_MS, value).apply()

    /**
     * Whether Test Mode (1 Min Limit / 2 Min Cooldown) is enabled.
     */
    var isTestMode: Boolean
        get() = prefs.getBoolean(KEY_IS_TEST_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_TEST_MODE, value).apply()

    /**
     * Compatibility aliases
     */
    var unlockTimestamp: Long
        get() = unlockTimestampMs
        set(value) { unlockTimestampMs = value }

    var isInstagramForeground: Boolean
        get() = isInstaInForeground
        set(value) { isInstaInForeground = value }

    var lastForegroundStart: Long
        get() = instaForegroundStartMs
        set(value) { instaForegroundStartMs = value }

    fun getUsageLimitMs(): Long {
        return if (isTestMode) TEST_USAGE_LIMIT_MS else USAGE_LIMIT_MS
    }

    fun getCooldownDurationMs(): Long {
        return if (isTestMode) TEST_COOLDOWN_DURATION_MS else COOLDOWN_DURATION_MS
    }

    /**
     * Resets session usage, foreground timestamps, and lock status.
     */
    fun resetSession() {
        prefs.edit()
            .putLong(KEY_ACCUMULATED_USAGE_MS, 0L)
            .putLong(KEY_INSTA_FOREGROUND_START_MS, 0L)
            .putBoolean(KEY_IS_INSTA_IN_FOREGROUND, false)
            .putLong(KEY_UNLOCK_TIMESTAMP_MS, 0L)
            .apply()
    }
}
