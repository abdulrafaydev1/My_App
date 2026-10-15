package com.instaguard.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import com.instaguard.app.R
import com.instaguard.app.data.GuardPreferences
import com.instaguard.app.model.TrackedApp
import com.instaguard.app.ui.LockScreenActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance AccessibilityService that tracks Instagram, YouTube, Facebook,
 * and TikTok independently in real-time with millisecond precision.
 * Features aggressive Picture-in-Picture (PiP) suppression, audio focus seizure,
 * and full-screen accessibility overlay defense.
 */
class AppBlockerService : AccessibilityService() {

    private lateinit var prefs: GuardPreferences
    private val enforcementHandler = Handler(Looper.getMainLooper())
    private val overlayTickerHandler = Handler(Looper.getMainLooper())
    private var isLoopRunning = false

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var activeOverlayAppKey: String? = null

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

                    val app = TrackedApp.fromKey(activeKey) ?: TrackedApp.YOUTUBE
                    enforceLockScreen(app)
                    return
                }

                enforcementHandler.postDelayed(this, 100L)
            } else {
                stopEnforcementLoop()
            }
        }
    }

    private val overlayTickerRunnable = object : Runnable {
        override fun run() {
            val key = activeOverlayAppKey
            val view = overlayView
            if (key != null && view != null) {
                val remainingMs = getRemainingCooldownMs(key, prefs)
                val tvCountdown = view.findViewById<TextView>(R.id.tvOverlayCountdown)
                if (remainingMs <= 0L) {
                    tvCountdown?.text = "00:00:00.000"
                    unlockApp(key, prefs)
                    hideSystemOverlay()
                } else {
                    tvCountdown?.text = formatWithMillis(remainingMs)
                    overlayTickerHandler.postDelayed(this, 33L)
                }
            } else {
                overlayTickerHandler.removeCallbacks(this)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = GuardPreferences(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        syncFromPreferences(prefs)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        val packageName = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""

        // Whenever any window changes, check and suppress any lingering blocked PiP windows
        if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            dismissPipWindowIfPresent()
        }

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

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
                hideSystemOverlay()
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

    /**
     * Complete PiP and background suppression defense:
     * 1. Seizes audio focus to force-pause YouTube video and audio playback.
     * 2. Inspects interactive windows and closes any Picture-in-Picture window.
     * 3. Draws full-screen system accessibility overlay above all windows.
     * 4. Dispatches multiple Home actions.
     * 5. Launches LockScreenActivity over everything.
     */
    private fun enforceLockScreen(app: TrackedApp) {
        // 1. Force-pause media playback immediately
        seizeAudioFocus()

        // 2. Dismiss any floating PiP window
        dismissPipWindowIfPresent()

        // 3. Show full-screen overlay defense (renders above PiP)
        showSystemOverlay(app)

        // 4. Send Home command to collapse underlying task
        performGlobalAction(GLOBAL_ACTION_HOME)
        Handler(Looper.getMainLooper()).postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
        }, 150L)

        // 5. Launch LockScreenActivity with full task-clearing flags
        val lockIntent = Intent(applicationContext, LockScreenActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra(LockScreenActivity.EXTRA_APP_KEY, app.key)
            putExtra(LockScreenActivity.EXTRA_APP_NAME, app.displayName)
        }
        startActivity(lockIntent)
    }

    /**
     * Requests exclusive audio focus from Android OS, forcing YouTube to stop audio/video immediately.
     */
    private fun seizeAudioFocus() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(true)
                    .build()
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
            Log.d(TAG, "Exclusive audio focus requested: media playback paused.")
        } catch (e: Exception) {
            Log.e(TAG, "Error seizing audio focus", e)
        }
    }

    /**
     * Inspects active interactive windows and closes any Picture-in-Picture window belonging to a blocked app.
     */
    private fun dismissPipWindowIfPresent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val windowList = windows ?: return
                for (w in windowList) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && w.isInPictureInPicture) {
                        val root = w.root ?: continue
                        val pkg = root.packageName?.toString() ?: ""
                        val tracked = TrackedApp.fromPackageName(pkg)
                        if (tracked != null && isAppLocked(tracked.key, prefs)) {
                            Log.w(TAG, "Blocked PiP window detected for ${tracked.displayName}. Dismissing...")
                            seizeAudioFocus()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                root.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
                            }
                            findAndClickCloseAction(root)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking/dismissing PiP window", e)
            }
        }
    }

    private fun findAndClickCloseAction(node: AccessibilityNodeInfo?) {
        if (node == null) return
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        if (desc.contains("close") || desc.contains("dismiss") ||
            text.contains("close") || text.contains("dismiss") ||
            viewId.contains("close") || viewId.contains("dismiss")
        ) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }

        for (i in 0 until node.childCount) {
            findAndClickCloseAction(node.getChild(i))
        }
    }

    /**
     * Displays a full-screen system overlay window above all application windows and PiP.
     * Uses TYPE_ACCESSIBILITY_OVERLAY which is guaranteed to render above Picture-in-Picture.
     */
    private fun showSystemOverlay(app: TrackedApp) {
        if (overlayView != null) {
            // Already showing, update active key
            activeOverlayAppKey = app.key
            return
        }

        try {
            val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
                windowManager = it
            }

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.layout_pip_blocking_overlay, null)

            val tvTitle = view.findViewById<TextView>(R.id.tvOverlayTitle)
            val tvCountdown = view.findViewById<TextView>(R.id.tvOverlayCountdown)
            val btnHome = view.findViewById<Button>(R.id.btnOverlayHome)

            tvTitle.text = "${app.displayName} is Locked for Focus"
            tvCountdown.typeface = Typeface.MONOSPACE

            btnHome.setOnClickListener {
                performGlobalAction(GLOBAL_ACTION_HOME)
                hideSystemOverlay()
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(homeIntent)
            }

            wm.addView(view, params)
            overlayView = view
            activeOverlayAppKey = app.key

            overlayTickerHandler.removeCallbacks(overlayTickerRunnable)
            overlayTickerHandler.post(overlayTickerRunnable)

            Log.d(TAG, "Full-screen system overlay successfully deployed above PiP.")
        } catch (e: Exception) {
            Log.e(TAG, "Could not deploy system overlay", e)
        }
    }

    private fun hideSystemOverlay() {
        try {
            overlayTickerHandler.removeCallbacks(overlayTickerRunnable)
            overlayView?.let {
                windowManager?.removeView(it)
                overlayView = null
                activeOverlayAppKey = null
                Log.d(TAG, "System overlay removed.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing system overlay", e)
        }
    }

    override fun onInterrupt() {
        stopEnforcementLoop()
        handleNonTrackedAppWindow()
        hideSystemOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEnforcementLoop()
        handleNonTrackedAppWindow()
        hideSystemOverlay()
    }

    companion object {
        private const val TAG = "AppBlockerService"

        val accumulatedUsageMs = ConcurrentHashMap<String, Long>()
        val unlockTimestampMs = ConcurrentHashMap<String, Long>()

        @Volatile
        var currentForegroundAppKey: String? = null

        @Volatile
        var foregroundStartMs: Long = 0L

        fun syncFromPreferences(prefs: GuardPreferences) {
            for (app in TrackedApp.entries) {
                accumulatedUsageMs[app.key] = prefs.getAccumulatedUsage(app.key)
                unlockTimestampMs[app.key] = prefs.getUnlockTimestamp(app.key)
            }
        }

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

        fun isAppLocked(appKey: String, prefs: GuardPreferences): Boolean {
            val unlockTime = unlockTimestampMs[appKey] ?: prefs.getUnlockTimestamp(appKey)
            if (unlockTime <= 0L) return false

            val now = System.currentTimeMillis()
            if (now >= unlockTime) {
                unlockTimestampMs[appKey] = 0L
                accumulatedUsageMs[appKey] = 0L
                prefs.resetApp(appKey)
                return false
            }
            return true
        }

        fun getRemainingCooldownMs(appKey: String, prefs: GuardPreferences): Long {
            if (!isAppLocked(appKey, prefs)) return 0L
            val unlockTime = unlockTimestampMs[appKey] ?: prefs.getUnlockTimestamp(appKey)
            return maxOf(0L, unlockTime - System.currentTimeMillis())
        }

        fun unlockApp(appKey: String, prefs: GuardPreferences) {
            unlockTimestampMs[appKey] = 0L
            accumulatedUsageMs[appKey] = 0L
            prefs.resetApp(appKey)
        }

        fun resetAll(prefs: GuardPreferences) {
            currentForegroundAppKey = null
            foregroundStartMs = 0L
            for (app in TrackedApp.entries) {
                accumulatedUsageMs[app.key] = 0L
                unlockTimestampMs[app.key] = 0L
            }
            prefs.resetAllApps()
        }

        private fun formatWithMillis(ms: Long): String {
            val safeMs = maxOf(0L, ms)
            val hours = safeMs / 3_600_000
            val minutes = (safeMs % 3_600_000) / 60_000
            val seconds = (safeMs % 60_000) / 1_000
            val millis = safeMs % 1_000
            return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
        }
    }
}
