package com.instaguard.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.instaguard.app.data.GuardPreferences
import com.instaguard.app.model.TrackedApp
import com.instaguard.app.ui.LockScreenActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance AccessibilityService that tracks Instagram, YouTube, Facebook,
 * and TikTok independently in real-time with millisecond precision.
 */
class AppBlockerService : AccessibilityService() {

    private lateinit var prefs: GuardPreferences
    private val enforcementHandler = Handler(Looper.getMainLooper())
    private var isLoopRunning = false

    private val enforcementRunnable = object : Runnable {
        override fun run() {
            val activeKey = currentForegroundAppKey
            if (activeKey != null) {
                val now = System.currentTimeMillis()
                val start = foregroundStartMs
                val liveDelta = if (start > 0L) now - start else 0L
                val totalMs = (accumulatedUsageMs[activeKey] ?: 0L) + liveDelta
                val limitMs = prefs.getUsageLimitMs()

                if (totalMs >= limitMs) {
                    Log.w(TAG, "[$activeKey] Limit reached ($totalMs ms >= $limitMs ms). Triggering cooldown lock.")
                    val cooldownMs = prefs.getCooldownDurationMs()
                    val unlockTime = now + cooldownMs

                    unlockTimestampMs[activeKey] = unlockTime
                    accumulatedUsageMs[activeKey] = 0L
                    prefs.setUnlockTimestamp(activeKey, unlockTime)
                    prefs.setAccumulatedUsage(activeKey, 0L)

                    currentForegroundAppKey = null
                    foregroundStartMs = 0L
                    stopEnforcementLoop()

                    val app = TrackedApp.fromKey(activeKey) ?: TrackedApp.INSTAGRAM
                    enforceLockScreen(app)
                    return
                }

                enforcementHandler.postDelayed(this, 100L)
            } else {
                stopEnforcementLoop()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = GuardPreferences(applicationContext)
        syncFromPreferences(prefs)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // Ignore transient system overlays (volume bar, notifications, power menu)
        if (packageName == "android" || packageName == "com.android.systemui") {
            return
        }

        // Ignore virtual keyboard packages
        if (packageName.startsWith("com.google.android.inputmethod") ||
            packageName.contains("keyboard") ||
            packageName.contains("ime")
        ) {
            return
        }

        // Ignore our own LockScreenActivity to prevent self-interception loops
        if (packageName == applicationContext.packageName && className.contains("LockScreenActivity")) {
            return
        }

        val trackedApp = TrackedApp.fromPackageName(packageName)
        if (trackedApp != null) {
            handleTrackedAppWindow(trackedApp)
        } else {
            handleNonTrackedAppWindow()
        }
    }

    private fun handleTrackedAppWindow(app: TrackedApp) {
        val now = System.currentTimeMillis()
        val appKey = app.key

        // 1. Check if the app is currently locked in cooldown
        val unlockTime = unlockTimestampMs[appKey] ?: prefs.getUnlockTimestamp(appKey)
        if (unlockTime > 0L) {
            if (now < unlockTime) {
                Log.w(TAG, "[${app.displayName}] Opened during active cooldown. Blocking immediately.")
                commitPreviousForegroundApp(now)
                enforceLockScreen(app)
                return
            } else {
                // Cooldown has expired, auto-unlock
                unlockTimestampMs[appKey] = 0L
                accumulatedUsageMs[appKey] = 0L
                prefs.resetApp(appKey)
            }
        }

        // 2. If transitioning from another tracked app, commit its usage first
        if (currentForegroundAppKey != null && currentForegroundAppKey != appKey) {
            commitPreviousForegroundApp(now)
        }

        // 3. Begin tracking this app
        if (currentForegroundAppKey != appKey) {
            currentForegroundAppKey = appKey
            foregroundStartMs = now
            startEnforcementLoop()
            Log.d(TAG, "[${app.displayName}] Foreground tracking started at $now")
        }
    }

    private fun handleNonTrackedAppWindow() {
        if (currentForegroundAppKey != null) {
            val now = System.currentTimeMillis()
            commitPreviousForegroundApp(now)
            stopEnforcementLoop()
        }
    }

    private fun commitPreviousForegroundApp(now: Long) {
        val prevKey = currentForegroundAppKey ?: return
        val start = foregroundStartMs
        if (start > 0L) {
            val delta = now - start
            if (delta > 0L) {
                val current = accumulatedUsageMs[prevKey] ?: prefs.getAccumulatedUsage(prevKey)
                val updated = current + delta
                accumulatedUsageMs[prevKey] = updated
                prefs.setAccumulatedUsage(prevKey, updated)
                Log.d(TAG, "[$prevKey] Committed $delta ms. Total accumulated: $updated ms")
            }
        }
        currentForegroundAppKey = null
        foregroundStartMs = 0L
    }

    private fun startEnforcementLoop() {
        if (!isLoopRunning) {
            isLoopRunning = true
            enforcementHandler.removeCallbacks(enforcementRunnable)
            enforcementHandler.post(enforcementRunnable)
        }
    }

    private fun stopEnforcementLoop() {
        isLoopRunning = false
        enforcementHandler.removeCallbacks(enforcementRunnable)
    }

    private fun enforceLockScreen(app: TrackedApp) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val lockIntent = Intent(applicationContext, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(LockScreenActivity.EXTRA_APP_KEY, app.key)
            putExtra(LockScreenActivity.EXTRA_APP_NAME, app.displayName)
        }
        startActivity(lockIntent)
    }

    override fun onInterrupt() {
        stopEnforcementLoop()
        handleNonTrackedAppWindow()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEnforcementLoop()
        handleNonTrackedAppWindow()
    }

    companion object {
        private const val TAG = "AppBlockerService"

        // Thread-safe in-memory state shared with UI for zero-lag 30 FPS updates
        val accumulatedUsageMs = ConcurrentHashMap<String, Long>()
        val unlockTimestampMs = ConcurrentHashMap<String, Long>()

        @Volatile
        var currentForegroundAppKey: String? = null

        @Volatile
        var foregroundStartMs: Long = 0L

        /**
         * Initializes or reloads state from SharedPreferences.
         */
        fun syncFromPreferences(prefs: GuardPreferences) {
            for (app in TrackedApp.entries) {
                accumulatedUsageMs[app.key] = prefs.getAccumulatedUsage(app.key)
                unlockTimestampMs[app.key] = prefs.getUnlockTimestamp(app.key)
            }
        }

        /**
         * Calculates instantaneous live usage time (in ms) for an app.
         */
        fun getLiveUsageMs(appKey: String, prefs: GuardPreferences): Long {
            if (isAppLocked(appKey, prefs)) {
                return prefs.getUsageLimitMs()
            }

            var base = accumulatedUsageMs[appKey] ?: prefs.getAccumulatedUsage(appKey)
            if (currentForegroundAppKey == appKey && foregroundStartMs > 0L) {
                val delta = System.currentTimeMillis() - foregroundStartMs
                if (delta > 0L) {
                    base += delta
                }
            }
            return base.coerceAtMost(prefs.getUsageLimitMs())
        }

        /**
         * Checks if an app is currently locked in cooldown.
         */
        fun isAppLocked(appKey: String, prefs: GuardPreferences): Boolean {
            val unlockTime = unlockTimestampMs[appKey] ?: prefs.getUnlockTimestamp(appKey)
            if (unlockTime <= 0L) return false

            val now = System.currentTimeMillis()
            if (now >= unlockTime) {
                // Expired
                unlockTimestampMs[appKey] = 0L
                accumulatedUsageMs[appKey] = 0L
                prefs.resetApp(appKey)
                return false
            }
            return true
        }

        /**
         * Returns remaining cooldown milliseconds for an app.
         */
        fun getRemainingCooldownMs(appKey: String, prefs: GuardPreferences): Long {
            if (!isAppLocked(appKey, prefs)) return 0L
            val unlockTime = unlockTimestampMs[appKey] ?: prefs.getUnlockTimestamp(appKey)
            return maxOf(0L, unlockTime - System.currentTimeMillis())
        }

        /**
         * Unlocks a specific app and resets its cooldown.
         */
        fun unlockApp(appKey: String, prefs: GuardPreferences) {
            unlockTimestampMs[appKey] = 0L
            accumulatedUsageMs[appKey] = 0L
            prefs.resetApp(appKey)
        }

        /**
         * Resets all 4 social apps.
         */
        fun resetAll(prefs: GuardPreferences) {
            currentForegroundAppKey = null
            foregroundStartMs = 0L
            for (app in TrackedApp.entries) {
                accumulatedUsageMs[app.key] = 0L
                unlockTimestampMs[app.key] = 0L
            }
            prefs.resetAllApps()
        }
    }
}
