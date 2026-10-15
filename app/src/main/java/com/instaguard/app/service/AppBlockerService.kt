package com.instaguard.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.instaguard.app.data.GuardPreferences
import com.instaguard.app.ui.LockScreenActivity
import com.instaguard.app.util.GuardManager

/**
 * AccessibilityService that intercepts Instagram launches in real-time,
 * maintains high-precision foreground tracking, and continuously enforces
 * the allowance limit every 100ms while Instagram is open.
 */
class AppBlockerService : AccessibilityService() {

    private lateinit var prefs: GuardPreferences
    private lateinit var guardManager: GuardManager
    private val enforcementHandler = Handler(Looper.getMainLooper())
    private var isLoopRunning = false

    private val enforcementRunnable = object : Runnable {
        override fun run() {
            if (prefs.isInstaInForeground) {
                val startMs = prefs.instaForegroundStartMs
                val liveDelta = if (startMs > 0L) (System.currentTimeMillis() - startMs) else 0L
                val currentTotalMs = prefs.accumulatedUsageMs + liveDelta
                val limitMs = prefs.getUsageLimitMs()

                if (currentTotalMs >= limitMs) {
                    Log.w(TAG, "Limit reached ($currentTotalMs ms >= $limitMs ms). Triggering cooldown lock.")
                    val cooldownMs = prefs.getCooldownDurationMs()
                    prefs.unlockTimestampMs = System.currentTimeMillis() + cooldownMs
                    prefs.accumulatedUsageMs = 0L
                    prefs.isInstaInForeground = false
                    prefs.instaForegroundStartMs = 0L
                    stopEnforcementLoop()
                    enforceLockScreen()
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
        guardManager = GuardManager(applicationContext)
        guardManager.syncWithUsageStatsIfEmpty()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // Ignore system overlays/popups (volume panel, notification shade, power menu)
        if (packageName == "android" || packageName == "com.android.systemui") {
            return
        }

        // Ignore events caused by our own LockScreenActivity to prevent self-interception
        if (packageName == applicationContext.packageName) {
            if (className.contains("LockScreenActivity")) {
                return
            }
        }

        if (packageName == GuardPreferences.TARGET_PACKAGE) {
            handleInstagramWindowOpened()
        } else {
            handleNonInstagramWindowOpened()
        }
    }

    private fun handleInstagramWindowOpened() {
        val now = System.currentTimeMillis()
        val unlockTime = prefs.unlockTimestampMs

        // 1. Check if cooldown is active
        if (unlockTime > 0L) {
            if (now < unlockTime) {
                Log.w(TAG, "Instagram opened during active cooldown. Blocking immediately.")
                enforceLockScreen()
                return
            } else {
                // Cooldown expired
                prefs.resetSession()
            }
        }

        // 2. Mark in foreground if not already marked
        if (!prefs.isInstaInForeground) {
            prefs.isInstaInForeground = true
            prefs.instaForegroundStartMs = now
        }

        // 3. Start 100ms background enforcement loop
        startEnforcementLoop()
    }

    private fun handleNonInstagramWindowOpened() {
        if (prefs.isInstaInForeground) {
            val now = System.currentTimeMillis()
            val startMs = prefs.instaForegroundStartMs
            if (startMs > 0L) {
                val delta = now - startMs
                if (delta > 0L) {
                    prefs.accumulatedUsageMs += delta
                }
            }
            prefs.isInstaInForeground = false
            prefs.instaForegroundStartMs = 0L
            stopEnforcementLoop()
            Log.d(TAG, "Exited Instagram. Saved accumulated usage: ${prefs.accumulatedUsageMs} ms")
        }
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

    private fun enforceLockScreen() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val lockIntent = Intent(applicationContext, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(lockIntent)
    }

    override fun onInterrupt() {
        stopEnforcementLoop()
        handleNonInstagramWindowOpened()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEnforcementLoop()
        handleNonInstagramWindowOpened()
    }

    companion object {
        private const val TAG = "AppBlockerService"
    }
}
