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
 * tracks active foreground time, and deploys the LockScreenActivity
 * whenever cooldown is active or the 1-hour allowance is depleted.
 */
class AppBlockerService : AccessibilityService() {

    private lateinit var guardManager: GuardManager
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoringInstagram = false

    private val usageTickerRunnable = object : Runnable {
        override fun run() {
            if (isMonitoringInstagram) {
                val shouldBlock = guardManager.checkForegroundProgress()
                if (shouldBlock) {
                    enforceLockScreen()
                    isMonitoringInstagram = false
                } else {
                    handler.postDelayed(this, 1000L)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        guardManager = GuardManager(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // Ignore events caused by our own LockScreenActivity to prevent recursion
        if (packageName == applicationContext.packageName) {
            return
        }

        if (packageName == GuardPreferences.TARGET_PACKAGE) {
            handleInstagramWindowOpened(className)
        } else {
            handleNonInstagramWindowOpened()
        }
    }

    private fun handleInstagramWindowOpened(className: String) {
        Log.d(TAG, "Instagram window detected: $className")

        if (guardManager.isCooldownActive()) {
            Log.w(TAG, "Instagram opened during active 3-hour cooldown. Blocking immediately.")
            enforceLockScreen()
            return
        }

        // Start tracking active foreground usage
        val limitReached = guardManager.onInstagramForeground()
        if (limitReached) {
            Log.w(TAG, "Instagram 1-hour allowance reached. Triggering cooldown lock.")
            enforceLockScreen()
            return
        }

        // Begin active 1-second ticker while Instagram is in foreground
        if (!isMonitoringInstagram) {
            isMonitoringInstagram = true
            handler.removeCallbacks(usageTickerRunnable)
            handler.postDelayed(usageTickerRunnable, 1000L)
        }
    }

    private fun handleNonInstagramWindowOpened() {
        if (isMonitoringInstagram) {
            Log.d(TAG, "User exited Instagram. Pausing usage session counter.")
            isMonitoringInstagram = false
            handler.removeCallbacks(usageTickerRunnable)
            guardManager.onInstagramBackground()
        }
    }

    /**
     * Intercepts Instagram by launching the non-bypassable full-screen LockScreenActivity
     * and redirecting the underlying app to the home screen.
     */
    private fun enforceLockScreen() {
        // Send Instagram to background
        performGlobalAction(GLOBAL_ACTION_HOME)

        // Launch full-screen lock screen over everything
        val lockIntent = Intent(applicationContext, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(lockIntent)
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
        isMonitoringInstagram = false
        handler.removeCallbacks(usageTickerRunnable)
        guardManager.onInstagramBackground()
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoringInstagram = false
        handler.removeCallbacks(usageTickerRunnable)
        guardManager.onInstagramBackground()
    }

    companion object {
        private const val TAG = "AppBlockerService"
    }
}
