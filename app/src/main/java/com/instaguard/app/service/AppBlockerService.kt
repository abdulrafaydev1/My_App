package com.instaguard.app.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.instaguard.app.R
import com.instaguard.app.data.GuardPreferences
import com.instaguard.app.model.TrackedApp
import com.instaguard.app.ui.LockScreenActivity
import com.instaguard.app.ui.MainActivity
import com.instaguard.app.util.UsageStatsHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance AccessibilityService that monitors Instagram, YouTube, Facebook,
 * and TikTok independently in real-time with millisecond precision.
 * v5.0 Master Edition Features:
 * - Monotonic 50ms Delta Stopwatch Engine (SystemClock.elapsedRealtime) with zero timer drift.
 * - Strict 1-hour usage limit and hardened 5-hour cooldown lock per app.
 * - Feature 1: Midnight Auto-Reset (12:00 AM) & Daily History Archiving.
 * - Feature 2: Smart Warning Notifications at 5 Minutes & 1 Minute remaining.
 * - Feature 3: Emergency 5-Minute Master PIN Bypass support.
 * - Layer 1: OS-level UsageEvents reconstruction when data is empty.
 * - Layer 2: Anti-Uninstall, Anti-Force-Stop, and Anti-Clear-Data Tamper Shield.
 * - 3-Layer YouTube/Shorts Picture-in-Picture (PiP) Killer Logic.
 */
class AppBlockerService : AccessibilityService() {

    private lateinit var prefs: GuardPreferences
    private val tickerHandler = Handler(Looper.getMainLooper())
    private var isTickerRunning = false
    private var tickCounter = 0
    private var lastTamperBlockMs = 0L
    private var lastPillText = ""
    private var lastScrollsText = ""
    private var lastTimerText = ""

    // v6.3 Post-Scroll Settled Verification State
    private var lastScrollCountElapsedMs = 0L
    private var lastCountedScreenSignature = ""
    private var lastCountedFromIndex = -1
    private var lastCountedToIndex = -1
    private var pendingScrollFromIndex = -1
    private var pendingScrollToIndex = -1
    private var hadVideoVisibleBeforeScroll = false

    // v6.3 YouTube Long Video 10-Second Watch State
    @Volatile
    private var isYoutubeLongVideoTimerRunning = false
    @Volatile
    private var youtubeLongVideoStartElapsedMs = 0L
    private var wasInYoutubeWatchMode = false

    private val verifyScrollRunnable = Runnable {
        val activeKey = currentForegroundAppKey ?: return@Runnable
        val now = SystemClock.elapsedRealtime()
        if (now - lastScrollCountElapsedMs < 900L) return@Runnable

        val root = try { rootInActiveWindow } catch (e: Exception) { null }
        val currentSig = getScreenContentSignature(root)
        val indexChanged = (pendingScrollFromIndex >= 0 && pendingScrollFromIndex != lastCountedFromIndex) ||
            (pendingScrollToIndex >= 0 && pendingScrollToIndex != lastCountedToIndex)
        val signatureChanged = currentSig.isNotEmpty() && currentSig != lastCountedScreenSignature
        val confirmRealFullScroll = indexChanged || signatureChanged ||
            (currentSig.isEmpty() && pendingScrollFromIndex == -1 && activeKey == "TIKTOK")

        if (!confirmRealFullScroll) return@Runnable

        var shouldIncrement = false
        when (activeKey) {
            "TIKTOK" -> {
                shouldIncrement = true
            }
            "INSTAGRAM", "FACEBOOK" -> {
                val inReels = isReelsOrWatchTabActive(activeKey, root)
                val hasVideoNow = hasVideoElementOnScreen(root)
                if (inReels || hadVideoVisibleBeforeScroll || hasVideoNow) {
                    shouldIncrement = true
                }
            }
            "YOUTUBE" -> {
                if (isYoutubeShortsMode(root)) {
                    shouldIncrement = true
                }
            }
            else -> {
                shouldIncrement = true
            }
        }

        if (shouldIncrement) {
            lastScrollCountElapsedMs = now
            if (currentSig.isNotEmpty()) {
                lastCountedScreenSignature = currentSig
            }
            lastCountedFromIndex = pendingScrollFromIndex
            lastCountedToIndex = pendingScrollToIndex
            hadVideoVisibleBeforeScroll = false

            incrementScrollCount(activeKey)
            pulseFloatingPill(activeKey)
        }
    }

    /**
     * Monotonic 50ms Delta-Tick Stopwatch Ticker.
     * Continuously computes elapsed time delta using SystemClock.elapsedRealtime()
     * and adds delta directly to active app's accumulated usage and daily totals.
     */
    private val tickerRunnable = object : Runnable {
        override fun run() {
            val nowElapsed = SystemClock.elapsedRealtime()
            val deltaMs = if (lastTickElapsedRealtime > 0L) {
                (nowElapsed - lastTickElapsedRealtime).coerceIn(0L, 500L)
            } else {
                0L
            }
            lastTickElapsedRealtime = nowElapsed

            // 1. Verify screen is interactive (ON)
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isInteractive) {
                currentForegroundAppKey = null
                removeFloatingPill()
                tickerHandler.postDelayed(this, 50L)
                return
            }

            // 2. Active PiP Hunter whenever any app is in 5-hour cooldown lock
            if (isAnyAppLocked()) {
                killPipWindowsIfAny()
            }

            // 3. Fallback active window inspection from rootInActiveWindow
            val rootPkg = try {
                rootInActiveWindow?.packageName?.toString()
            } catch (e: Exception) {
                null
            }
            if (rootPkg != null && !IGNORED_SYSTEM_PACKAGES.contains(rootPkg)) {
                val mapped = mapPackageToAppKey(rootPkg)
                if (mapped != null) {
                    currentForegroundAppKey = mapped
                } else if (rootPkg == applicationContext.packageName) {
                    if (isOurAppScreenOpen) {
                        currentForegroundAppKey = null
                    }
                }
            }

            // 4. Increment usage counter and daily total for active tracked app
            val activeKey = currentForegroundAppKey
            if (activeKey != null) {
                // Step 4: YouTube Long Video 10-Second Watch tracking
                if (activeKey == "YOUTUBE") {
                    // Trigger B: Check watch mode transition every 10 ticks (~500ms)
                    if (tickCounter % 10 == 0) {
                        val root = try { rootInActiveWindow } catch (e: Exception) { null }
                        val inWatchMode = isYoutubeLongVideoWatchMode(root)
                        if (inWatchMode && !wasInYoutubeWatchMode && !isYoutubeLongVideoTimerRunning) {
                            startYoutubeLongVideoCountdown()
                        }
                        wasInYoutubeWatchMode = inWatchMode
                    }

                    // Handle running countdown
                    if (isYoutubeLongVideoTimerRunning) {
                        val elapsedMs = SystemClock.elapsedRealtime() - youtubeLongVideoStartElapsedMs
                        if (elapsedMs >= 10_000L) {
                            isYoutubeLongVideoTimerRunning = false
                            incrementScrollCount("YOUTUBE")
                            pulseFloatingPill("YOUTUBE")
                            tickerHandler.post {
                                try {
                                    Toast.makeText(
                                        applicationContext,
                                        "✅ 10 Seconds Completed! YouTube Counter +1",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                } else {
                    isYoutubeLongVideoTimerRunning = false
                    wasInYoutubeWatchMode = false
                }

                if (isAppLocked(activeKey, applicationContext)) {
                    currentForegroundAppKey = null
                    removeFloatingPill()
                    stopAllMediaPlayback()
                    launchLockScreen(activeKey)
                } else {
                    val updatedUsage = (accumulatedUsageMap[activeKey] ?: 0L) + deltaMs
                    accumulatedUsageMap[activeKey] = updatedUsage

                    // Update today's daily archive counter in memory
                    val todayStr = getTodayDateStr()
                    val currentDaily = dailyTotalMap[activeKey] ?: prefs.getDailyTotal(todayStr, activeKey)
                    dailyTotalMap[activeKey] = currentDaily + deltaMs

                    val limitMs = prefs.getUsageLimitMs()
                    val remainingAllowance = maxOf(0L, limitMs - updatedUsage)
                    updateFloatingPill(activeKey, remainingAllowance)

                    // Smart Warning Notifications (5-Min & 1-Min)
                    checkAndTriggerWarningNotifications(activeKey, updatedUsage, limitMs)

                    if (updatedUsage >= limitMs) {
                        Log.w(TAG, "[$activeKey] Limit reached ($updatedUsage >= $limitMs). Locking for 5 hours.")
                        removeFloatingPill()
                        val cooldownMs = prefs.getCooldownDurationMs()
                        unlockTimestampMap[activeKey] = System.currentTimeMillis() + cooldownMs
                        accumulatedUsageMap[activeKey] = 0L
                        currentForegroundAppKey = null

                        // Reset warning flags on lock
                        warned5MinMap[activeKey] = false
                        warned1MinMap[activeKey] = false

                        saveStateToPrefs(activeKey)
                        stopAllMediaPlayback()
                        launchLockScreen(activeKey)
                    }
                }
            } else {
                isYoutubeLongVideoTimerRunning = false
                wasInYoutubeWatchMode = false
                if (isPillAttached) {
                    removeFloatingPill()
                }
            }

            // 5. Periodic auto-checkpoint persistence every 40 ticks (~2000ms)
            tickCounter++
            if (tickCounter >= 40) {
                tickCounter = 0
                checkMidnightCrossover()
                if (activeKey != null) {
                    saveStateToPrefs(activeKey)
                }
            }

            tickerHandler.postDelayed(this, 50L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        prefs = GuardPreferences(applicationContext)
        initNotificationChannel()
        syncFromPrefs(applicationContext)
        checkMidnightCrossover()
        startStopwatchTicker()
        Log.i(TAG, "AppBlockerService created; Monotonic 50ms Stopwatch Engine started.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        appContext = applicationContext
        prefs = GuardPreferences(applicationContext)
        initNotificationChannel()
        syncFromPrefs(applicationContext)
        checkMidnightCrossover()
        startStopwatchTicker()
        Log.i(TAG, "AppBlockerService connected; Monotonic 50ms Stopwatch Engine running.")
    }

    private fun initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERTS_CHANNEL_ID,
                "Focus Limit Warnings",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when your daily focus quota is almost up"
                enableVibration(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)

            val ytChannel = NotificationChannel(
                YT_LONG_CHANNEL_ID,
                "YouTube Video Timer Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a long video watch timer starts"
                enableVibration(true)
            }
            nm.createNotificationChannel(ytChannel)
        }
    }

    private fun startStopwatchTicker() {
        if (!isTickerRunning) {
            isTickerRunning = true
            lastTickElapsedRealtime = SystemClock.elapsedRealtime()
            tickerHandler.removeCallbacks(tickerRunnable)
            tickerHandler.post(tickerRunnable)
        }
    }

    private fun stopStopwatchTicker() {
        isTickerRunning = false
        tickerHandler.removeCallbacks(tickerRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. Interactive window changes -> PiP hunt
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (isAnyAppLocked()) {
                killPipWindowsIfAny()
            }
            return
        }

        val packageName = event.packageName?.toString() ?: ""

        // 2. LAYER 2: Anti-Uninstall / Anti-Force-Stop / Anti-Clear-Data Shield
        if (packageName in SYSTEM_TAMPER_PACKAGES) {
            if (isAnyAppLocked() || hasAnyActiveSession()) {
                if (inspectAndBlockTamperAttempt(packageName)) {
                    return
                }
            }
        }

        // v6.3 Trigger A: YouTube Video Item Click Detection (Instant 10s Timer Kickoff)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val activeKey = currentForegroundAppKey ?: return
            if (activeKey == "YOUTUBE") {
                val root = try { rootInActiveWindow } catch (e: Exception) { null }
                if (!isYoutubeShortsMode(root) && !isYoutubeLongVideoTimerRunning) {
                    val screenHeight = resources.displayMetrics.heightPixels
                    val source = event.source
                    val rect = Rect()
                    source?.getBoundsInScreen(rect)

                    val clickedText = "${source?.text ?: ""} ${source?.contentDescription ?: ""}".trim()
                    val parentText = "${source?.parent?.text ?: ""} ${source?.parent?.contentDescription ?: ""}".trim()
                    val combined = "$clickedText $parentText".lowercase(Locale.ROOT)

                    val isNavOrFilter = combined.contains("home") || combined.contains("shorts") ||
                        combined.contains("subscriptions") || combined.contains("you") ||
                        combined.contains("search") || combined.contains("all") ||
                        combined.contains("notifications")

                    val inMainFeedArea = source == null || (rect.centerY() > (screenHeight * 0.14).toInt() && rect.centerY() < (screenHeight * 0.88).toInt())

                    if (inMainFeedArea && !isNavOrFilter) {
                        startYoutubeLongVideoCountdown()
                    }
                }
            }
            return
        }

        // v6.3 Step 3: 300ms Post-Scroll Settled Verification Engine
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val activeKey = currentForegroundAppKey ?: return
            val now = SystemClock.elapsedRealtime()

            // 1. Debounce between counted gestures (900ms minimum between counts)
            if (now - lastScrollCountElapsedMs < 900L) {
                return
            }

            pendingScrollFromIndex = event.fromIndex
            pendingScrollToIndex = event.toIndex

            val root = try { rootInActiveWindow } catch (e: Exception) { null }
            if (hasVideoElementOnScreen(root)) {
                hadVideoVisibleBeforeScroll = true
            }

            // Schedule (or reset) delayed 300ms verification after finger swipe settles
            tickerHandler.removeCallbacks(verifyScrollRunnable)
            tickerHandler.postDelayed(verifyScrollRunnable, 300L)
            return
        }

        // CRITICAL: TYPE_WINDOW_CONTENT_CHANGED must NEVER update or reset currentForegroundAppKey!
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        // 3. Strict Package Filtering: Ignore status bar, notifications, and soft keyboards
        if (IGNORED_SYSTEM_PACKAGES.contains(packageName) ||
            packageName.startsWith("com.google.android.inputmethod") ||
            packageName.contains("keyboard") ||
            packageName.contains("ime")
        ) {
            return
        }

        // 4. Handle InstaGuard's own Activities (MainActivity / LockScreenActivity)
        if (packageName == applicationContext.packageName) {
            if (isOurAppScreenOpen) {
                currentForegroundAppKey?.let { saveStateToPrefs(it) }
                currentForegroundAppKey = null
                removeFloatingPill()
            }
            return
        }

        // 5. Check if foreground window matches one of our tracked apps
        val detectedKey = mapPackageToAppKey(packageName)
        if (detectedKey != null) {
            if (isAppLocked(detectedKey, applicationContext)) {
                Log.w(TAG, "[$detectedKey] Opened during active cooldown lock. Enforcing LockScreen.")
                currentForegroundAppKey = null
                removeFloatingPill()
                stopAllMediaPlayback()
                launchLockScreen(detectedKey)
            } else {
                if (currentForegroundAppKey != null && currentForegroundAppKey != detectedKey) {
                    saveStateToPrefs(currentForegroundAppKey!!)
                }
                currentForegroundAppKey = detectedKey
                Log.d(TAG, "[$detectedKey] Foreground active via TYPE_WINDOW_STATE_CHANGED")
                val limitMs = prefs.getUsageLimitMs()
                val usedMs = accumulatedUsageMap[detectedKey] ?: 0L
                val remainingAllowance = maxOf(0L, limitMs - usedMs)
                showFloatingScrollCounterPill(detectedKey, remainingAllowance)
            }
        } else {
            // User switched to another real application or Home Launcher
            currentForegroundAppKey?.let { saveStateToPrefs(it) }
            currentForegroundAppKey = null
            removeFloatingPill()
        }
    }

    /**
     * Feature 2: Fires high-importance heads-up notifications when 5 minutes
     * or 1 minute of active allowance remains.
     */
    private fun checkAndTriggerWarningNotifications(appKey: String, usedMs: Long, limitMs: Long) {
        val remainingMs = limitMs - usedMs
        val isTestMode = prefs.isTestMode
        val threshold5Min = if (isTestMode) 15_000L else 300_000L // 5 min or 15 sec
        val threshold1Min = if (isTestMode) 5_000L else 60_000L   // 1 min or 5 sec

        val trackedApp = TrackedApp.fromKey(appKey)
        val appDisplayName = trackedApp?.displayName ?: appKey

        // 5-Minute Warning Alert
        if (remainingMs in 1..threshold5Min && warned5MinMap[appKey] != true) {
            warned5MinMap[appKey] = true
            sendWarningNotification(
                notificationId = NOTIF_ID_5MIN + appKey.hashCode(),
                title = "⚠️ 5 Minutes Left on $appDisplayName!",
                message = "Your 1-hour limit is almost up. Wrap up now before the 5-hour lock starts."
            )
        }

        // 1-Minute Warning Alert
        if (remainingMs in 1..threshold1Min && warned1MinMap[appKey] != true) {
            warned1MinMap[appKey] = true
            sendWarningNotification(
                notificationId = NOTIF_ID_1MIN + appKey.hashCode(),
                title = "🚨 Final 60 Seconds on $appDisplayName!",
                message = "Locking $appDisplayName for 5 hours in less than 1 minute!"
            )
        }
    }

    private fun sendWarningNotification(notificationId: Int, title: String, message: String) {
        try {
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(applicationContext, ALERTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notificationId, builder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Feature 1: Detects midnight crossover (12:00 AM) and resets usage for unlocked apps.
     */
    private fun checkMidnightCrossover() {
        val todayStr = getTodayDateStr()
        val lastDate = prefs.lastActiveDate

        if (lastDate.isNotEmpty() && lastDate != todayStr) {
            Log.i(TAG, "Midnight crossover detected! Transitioning from $lastDate to $todayStr.")
            for (key in getAllTrackedKeys()) {
                // If not currently in 5-hour cooldown lock, reset quota for fresh day
                if (!isAppLocked(key, applicationContext)) {
                    accumulatedUsageMap[key] = 0L
                    prefs.setAccumulatedUsage(key, 0L)
                }
            }
            // Feature 1: Reset today's scroll count on midnight
            scrollCountTodayMap.clear()

            // Reset warning notification flags
            warned5MinMap.clear()
            warned1MinMap.clear()

            // Feature 3: Focus Streak tracking
            val hadAnyLock = getAllTrackedKeys().any { prefs.getUnlockTimestamp(it) > 0L }
            if (!hadAnyLock) {
                prefs.focusStreakDays += 1
            }
            prefs.lastStreakDate = todayStr
        }
        prefs.lastActiveDate = todayStr
    }

    /**
     * LAYER 2: Inspects Android Settings / PackageInstaller / SecurityCenter windows
     * for attempts to Uninstall, Force Stop, or Clear Data/Storage of InstaGuard
     * while any app is locked or has active session usage.
     * Explicitly allows Accessibility Settings and Usage Access permission screens.
     */
    private fun inspectAndBlockTamperAttempt(packageName: String): Boolean {
        // Feature 4 D: Maintenance Mode (5-min PIN bypass window)
        if (System.currentTimeMillis() < maintenanceUntilMs) {
            return false
        }

        val now = System.currentTimeMillis()
        if (now - lastTamperBlockMs < 600L) {
            return true
        }

        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return false

        val screenTextBuilder = StringBuilder()
        collectWindowTextRecursively(root, screenTextBuilder)
        val fullText = screenTextBuilder.toString().lowercase()

        // Must reference our app ("instaguard")
        if (!fullText.contains("instaguard")) {
            return false
        }

        val isPackageInstaller = packageName.contains("packageinstaller")

        // IMPORTANT EXCEPTION: Do NOT block when user is granting permissions!
        val isPermissionScreen = fullText.contains("accessibility") ||
            fullText.contains("usage access") ||
            fullText.contains("usage data") ||
            fullText.contains("display over other apps") ||
            fullText.contains("appear on top")

        val isExplicitUninstallDialog = isPackageInstaller ||
            fullText.contains("do you want to uninstall") ||
            fullText.contains("delete app")

        if (isPermissionScreen && !isExplicitUninstallDialog) {
            return false
        }

        val hasTamperKeyword = fullText.contains("uninstall") ||
            fullText.contains("force stop") ||
            fullText.contains("storage & cache") ||
            fullText.contains("clear storage") ||
            fullText.contains("clear data") ||
            fullText.contains("do you want to uninstall") ||
            fullText.contains("delete app")

        if (hasTamperKeyword) {
            lastTamperBlockMs = now
            Log.w(TAG, "Tamper attempt detected in $packageName! Blocking immediately.")
            currentForegroundAppKey?.let { saveStateToPrefs(it) }
            currentForegroundAppKey = null

            // 1. Immediately navigate Back and Home
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)

            // 2. Launch LockScreen with Tamper Warning
            val targetKey = APP_KEYS.firstOrNull { isAppLocked(it, applicationContext) }
                ?: APP_KEYS.firstOrNull { getLiveUsageMs(it, applicationContext) > 0L }
                ?: "INSTAGRAM"
            val targetApp = TrackedApp.fromKey(targetKey) ?: TrackedApp.INSTAGRAM

            val lockIntent = Intent(applicationContext, LockScreenActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(LockScreenActivity.EXTRA_APP_KEY, targetApp.key)
                putExtra(LockScreenActivity.EXTRA_APP_NAME, targetApp.displayName)
                putExtra("EXTRA_APP_KEY", targetApp.key)
                putExtra("EXTRA_APP_NAME", targetApp.displayName)
                putExtra(LockScreenActivity.EXTRA_TAMPER_WARNING, TAMPER_WARNING_MESSAGE)
            }
            startActivity(lockIntent)
            return true
        }

        return false
    }

    private fun collectWindowTextRecursively(node: AccessibilityNodeInfo?, out: StringBuilder) {
        if (node == null) return
        node.text?.let {
            if (it.isNotEmpty()) {
                out.append(it).append(' ')
            }
        }
        node.contentDescription?.let {
            if (it.isNotEmpty()) {
                out.append(it).append(' ')
            }
        }
        val count = node.childCount
        for (i in 0 until count) {
            collectWindowTextRecursively(node.getChild(i), out)
        }
    }

    private fun launchLockScreen(appKey: String) {
        removeFloatingPill()
        val tracked = TrackedApp.fromKey(appKey)
        val appDisplayName = tracked?.displayName ?: run {
            try {
                val pm = packageManager
                val info = pm.getApplicationInfo(appKey, 0)
                pm.getApplicationLabel(info).toString()
            } catch (e: Exception) {
                appKey
            }
        }

        // If YOUTUBE, exit Shorts/fullscreen player and kill background process
        if (appKey == "YOUTUBE") {
            performGlobalAction(GLOBAL_ACTION_BACK)
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.killBackgroundProcesses("com.google.android.youtube")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val lockIntent = Intent(applicationContext, LockScreenActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(LockScreenActivity.EXTRA_APP_KEY, appKey)
            putExtra(LockScreenActivity.EXTRA_APP_NAME, appDisplayName)
            putExtra("EXTRA_APP_KEY", appKey)
            putExtra("EXTRA_APP_NAME", appDisplayName)
        }
        startActivity(lockIntent)

        // Delayed sweeps to crush PiP transition
        Handler(Looper.getMainLooper()).postDelayed({
            stopAllMediaPlayback()
            killPipWindowsIfAny()
        }, 200L)

        Handler(Looper.getMainLooper()).postDelayed({
            stopAllMediaPlayback()
            killPipWindowsIfAny()
        }, 500L)
    }

    private fun saveStateToPrefs(appKey: String) {
        val usage = accumulatedUsageMap[appKey] ?: 0L
        val unlock = unlockTimestampMap[appKey] ?: 0L
        val todayStr = getTodayDateStr()
        val daily = dailyTotalMap[appKey] ?: prefs.getDailyTotal(todayStr, appKey)
        val scrollToday = scrollCountTodayMap[appKey] ?: prefs.getScrollToday(todayStr, appKey)
        val scrollTotal = scrollCountTotalMap[appKey] ?: prefs.getScrollTotal(appKey)

        prefs.setAccumulatedUsage(appKey, usage)
        prefs.setUnlockTimestamp(appKey, unlock)
        prefs.setDailyTotal(todayStr, appKey, daily)
        prefs.setScrollToday(todayStr, appKey, scrollToday)
        prefs.setScrollTotal(appKey, scrollTotal)
    }

    private fun isAnyAppLocked(): Boolean {
        return getAllTrackedKeys().any { isAppLocked(it, applicationContext) }
    }

    private fun hasAnyActiveSession(): Boolean {
        return getAllTrackedKeys().any { getLiveUsageMs(it, applicationContext) > 0L }
    }

    private fun createPillLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(36f)
        }
    }

    private fun showFloatingScrollCounterPill(activeKey: String, remainingMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            hideFloatingPill()
            return
        }

        val timeStr = formatPillTime(remainingMs)
        val scrolls = scrollCountTodayMap[activeKey] ?: 0
        val targetScrollsText = "Scrolls: $scrolls"
        val targetTimerText = if (activeKey == "YOUTUBE" && isYoutubeLongVideoTimerRunning) {
            val elapsedSec = ((SystemClock.elapsedRealtime() - youtubeLongVideoStartElapsedMs) / 1000L).toInt()
            val remainingSec = maxOf(0, 10 - elapsedSec)
            "⏳ ${remainingSec}s | $timeStr left"
        } else {
            "$timeStr left"
        }

        // 1. Single Persistent Instance: Never re-add or re-create if already attached
        val currentView = floatingPillView
        if (currentView != null && isPillAttached) {
            currentView.visibility = View.VISIBLE
            val tvS = tvPillScrolls
            if (tvS != null && tvS.text != targetScrollsText) {
                tvS.text = targetScrollsText
                lastScrollsText = targetScrollsText
            }
            val tvT = tvPillTimer
            if (tvT != null && tvT.text != targetTimerText) {
                tvT.text = targetTimerText
                lastTimerText = targetTimerText
            }
            return
        }

        // 2. Build the pill container view once (v6.4 Dark Capsule Layout)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16f), dpToPx(8f), dpToPx(16f), dpToPx(8f))
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#EB18191C"))
                cornerRadius = 100f * resources.displayMetrics.density
                setStroke(dpToPx(1f), Color.parseColor("#38FFFFFF"))
            }
            background = bg
            elevation = dpToPx(6f).toFloat()
            visibility = View.VISIBLE
        }

        // Left Section: Reels Clapperboard Icon (18dp x 18dp)
        val ivClapper = ImageView(this).apply {
            setImageResource(R.drawable.ic_reels_clapper)
            val lp = LinearLayout.LayoutParams(dpToPx(18f), dpToPx(18f)).apply {
                marginEnd = dpToPx(6f)
                gravity = Gravity.CENTER_VERTICAL
            }
            layoutParams = lp
        }
        container.addView(ivClapper)

        // Left Section: Scrolls Text (Bright Golden Yellow)
        val tvScrolls = TextView(this).apply {
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFD600"))
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            text = targetScrollsText
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            layoutParams = lp
        }
        container.addView(tvScrolls)

        // Right Section: Alarm Clock Icon (18dp x 18dp, 14dp start margin)
        val ivAlarm = ImageView(this).apply {
            setImageResource(R.drawable.ic_alarm_clock)
            val lp = LinearLayout.LayoutParams(dpToPx(18f), dpToPx(18f)).apply {
                marginStart = dpToPx(14f)
                marginEnd = dpToPx(6f)
                gravity = Gravity.CENTER_VERTICAL
            }
            layoutParams = lp
        }
        container.addView(ivAlarm)

        // Right Section: Timer Text (Pure Crisp White)
        val tvTimer = TextView(this).apply {
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            text = targetTimerText
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            layoutParams = lp
        }
        container.addView(tvTimer)

        floatingPillView = container
        tvPillScrolls = tvScrolls
        tvPillTimer = tvTimer
        pillTextView = tvTimer
        lastScrollsText = targetScrollsText
        lastTimerText = targetTimerText

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                wm.addView(container, createPillLayoutParams())
                isPillAttached = true
                Log.d(TAG, "Floating pill attached to WindowManager.")
            } else {
                isPillAttached = false
                floatingPillView = null
                tvPillScrolls = null
                tvPillTimer = null
                pillTextView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach floating pill: ${e.message}")
            isPillAttached = false
            floatingPillView = null
            tvPillScrolls = null
            tvPillTimer = null
            pillTextView = null
        }
    }

    private fun updateFloatingPill(activeKey: String, remainingMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            hideFloatingPill()
            return
        }

        val currentView = floatingPillView
        if (currentView == null || !isPillAttached) {
            showFloatingScrollCounterPill(activeKey, remainingMs)
            return
        }

        currentView.visibility = View.VISIBLE

        val timeStr = formatPillTime(remainingMs)
        val scrolls = scrollCountTodayMap[activeKey] ?: 0
        val targetScrollsText = "Scrolls: $scrolls"
        val targetTimerText = if (activeKey == "YOUTUBE" && isYoutubeLongVideoTimerRunning) {
            val elapsedSec = ((SystemClock.elapsedRealtime() - youtubeLongVideoStartElapsedMs) / 1000L).toInt()
            val remainingSec = maxOf(0, 10 - elapsedSec)
            "⏳ ${remainingSec}s | $timeStr left"
        } else {
            "$timeStr left"
        }

        // Zero-Flicker View Mutator Pattern: Only update TextView text if content changed!
        val tvS = tvPillScrolls
        if (tvS != null && tvS.text != targetScrollsText) {
            tvS.text = targetScrollsText
            lastScrollsText = targetScrollsText
        }
        val tvT = tvPillTimer
        if (tvT != null && tvT.text != targetTimerText) {
            tvT.text = targetTimerText
            lastTimerText = targetTimerText
        }
    }

    private fun pulseFloatingPill(activeKey: String) {
        val limitMs = prefs.getUsageLimitMs()
        val usedMs = accumulatedUsageMap[activeKey] ?: 0L
        val remainingMs = maxOf(0L, limitMs - usedMs)

        updateFloatingPill(activeKey, remainingMs)

        val pill = floatingPillView ?: return
        pill.animate()
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(80)
            .withEndAction {
                floatingPillView?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(80)?.start()
            }
            .start()
    }

    private fun hideFloatingPill() {
        floatingPillView?.visibility = View.GONE
    }

    private fun removeFloatingPill() {
        hideFloatingPill()
    }

    private fun removeFloatingPillCompletely() {
        val view = floatingPillView
        if (isPillAttached && view != null) {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating pill: ${e.message}")
            }
            isPillAttached = false
            floatingPillView = null
            tvPillScrolls = null
            tvPillTimer = null
            pillTextView = null
            lastPillText = ""
            lastScrollsText = ""
            lastTimerText = ""
        }
    }

    private fun formatPillTime(ms: Long): String {
        val totalSec = maxOf(0L, ms) / 1000L
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", min, sec)
    }

    private fun dpToPx(dp: Float): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    private fun incrementScrollCount(appKey: String) {
        val todayCount = (scrollCountTodayMap[appKey] ?: 0) + 1
        val totalCount = (scrollCountTotalMap[appKey] ?: 0) + 1
        scrollCountTodayMap[appKey] = todayCount
        scrollCountTotalMap[appKey] = totalCount

        val todayDateStr = getTodayDateStr()
        prefs.setScrollToday(todayDateStr, appKey, todayCount)
        prefs.setScrollTotal(appKey, totalCount)
    }

    private fun startYoutubeLongVideoCountdown() {
        if (isYoutubeLongVideoTimerRunning) return
        isYoutubeLongVideoTimerRunning = true
        youtubeLongVideoStartElapsedMs = SystemClock.elapsedRealtime()

        // 1. Fire High-Priority English Notification
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    YT_LONG_CHANNEL_ID,
                    "YouTube Video Timer Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when a long video watch timer starts"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }

            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                2026,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val message = "You have opened a long video. The counter will increase after 10 seconds."
            val builder = NotificationCompat.Builder(applicationContext, YT_LONG_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("▶️ Long Video Opened")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            nm.notify(2026, builder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Immediate Toast on mainHandler
        tickerHandler.post {
            try {
                Toast.makeText(
                    applicationContext,
                    "▶️ You opened a long video! Counter will increase after 10 seconds.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun scanNodes(
        node: AccessibilityNodeInfo?,
        currentDepth: Int = 0,
        maxDepth: Int = 12,
        block: (AccessibilityNodeInfo) -> Unit
    ) {
        if (node == null || currentDepth > maxDepth) return
        try {
            block(node)
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    scanNodes(child, currentDepth + 1, maxDepth, block)
                }
            }
        } catch (e: Exception) {
            // Safe against invalid or changing nodes
        }
    }

    private fun getScreenContentSignature(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val sb = StringBuilder()
        var nodeCount = 0
        scanNodes(root, maxDepth = 10) { node ->
            if (nodeCount < 40) {
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val str = when {
                    !text.isNullOrEmpty() -> text
                    !desc.isNullOrEmpty() -> desc
                    else -> null
                }
                if (!str.isNullOrBlank()) {
                    val lower = str.lowercase(Locale.ROOT)
                    if (!lower.contains("scrolls:") && !lower.contains("% left") && !lower.contains("video:") && !lower.contains("left")) {
                        sb.append(str.take(25)).append('|')
                        nodeCount++
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun hasVideoElementOnScreen(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        var hasVideo = false
        scanNodes(root, maxDepth = 12) { node ->
            if (!hasVideo) {
                val cls = node.className?.toString() ?: ""
                if (cls.contains("SurfaceView") || cls.contains("TextureView") ||
                    cls.contains("VideoView") || cls.contains("ViewPager") ||
                    cls.contains("RecyclerView")
                ) {
                    hasVideo = true
                    return@scanNodes
                }
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                val combined = "$id $text $desc"
                if (combined.contains("reel") || combined.contains("video") ||
                    combined.contains("clips") || combined.contains("shorts") ||
                    combined.contains("player") || combined.contains("mute") ||
                    combined.contains("unmute") || combined.contains("sound") ||
                    combined.contains("audio") || combined.contains("remix") ||
                    combined.contains("watch") || combined.contains("0:")
                ) {
                    hasVideo = true
                }
            }
        }
        return hasVideo
    }

    private fun isYoutubeShortsMode(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        var hasReelId = false
        var hasRemix = false
        var hasSubscriptions = false
        var hasWatchCommentsPanel = false

        scanNodes(root, maxDepth = 10) { node ->
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            if (id.contains("reel_")) {
                hasReelId = true
            }
            val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            val combined = "$text $desc $id"

            if (combined.contains("remix")) {
                hasRemix = true
            }
            if (combined.contains("subscriptions") || combined.contains("subscribe")) {
                hasSubscriptions = true
            }
            if (id.contains("watch_player") || id.contains("player_view") ||
                id.contains("comment") || combined.contains("comments") ||
                combined.contains("save to playlist") || combined.contains("download")
            ) {
                hasWatchCommentsPanel = true
            }
        }

        if (hasReelId) return true
        if (hasRemix && hasSubscriptions && !hasWatchCommentsPanel) return true
        return false
    }

    private fun isYoutubeLongVideoWatchMode(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        if (isYoutubeShortsMode(root)) return false

        var hasWatchIndicator = false
        val screenHeight = resources.displayMetrics.heightPixels
        val tempRect = Rect()

        scanNodes(root, maxDepth = 12) { node ->
            if (!hasWatchIndicator) {
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
                if (id.contains("watch_player") || id.contains("player_view") ||
                    id.contains("player_control") || id.contains("time_bar") ||
                    id.contains("watch_while") || id.contains("collapse") ||
                    id.contains("minimize") || id.contains("subscribe") ||
                    id.contains("channel_bar") || id.contains("comment")
                ) {
                    hasWatchIndicator = true
                    return@scanNodes
                }

                val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
                val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
                val combined = "$text $desc"

                val isWatchKeyword = combined.contains("minimize") || combined.contains("collapse") ||
                    combined.contains("subtitles") || combined.contains("autoplay") ||
                    combined.contains("save to playlist") || combined.contains("download") ||
                    combined.contains("comments") || combined.contains("subscribe") ||
                    (combined.contains(":") && (combined.contains("/") || combined.length in 4..9))

                if (isWatchKeyword) {
                    node.getBoundsInScreen(tempRect)
                    if (tempRect.centerY() < screenHeight * 0.70) {
                        hasWatchIndicator = true
                    }
                }
            }
        }
        return hasWatchIndicator
    }

    private fun isReelsOrWatchTabActive(appKey: String, root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        if (appKey == "TIKTOK") return true
        var hasReels = false
        var hasStoriesOrHome = false
        scanNodes(root, maxDepth = 10) { node ->
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            val combined = "$desc $text $id"

            if (appKey == "INSTAGRAM") {
                if (combined.contains("reel") || combined.contains("clips") || combined.contains("audio") || id.contains("reel")) {
                    hasReels = true
                }
                if (combined.contains("story") || combined.contains("stories") || combined.contains("your story")) {
                    hasStoriesOrHome = true
                }
            } else if (appKey == "FACEBOOK") {
                if (combined.contains("reel") || combined.contains("watch") || combined.contains("video") || id.contains("reel")) {
                    hasReels = true
                }
                if (combined.contains("marketplace") || combined.contains("friends") || combined.contains("news feed")) {
                    hasStoriesOrHome = true
                }
            }
        }
        return hasReels && !hasStoriesOrHome
    }

    /**
     * Dispatches hardware media pause/stop keys and seizes BOTH transient exclusive
     * and permanent audio focus so YouTube/Shorts believes another media player has permanently taken over.
     */
    private fun stopAllMediaPlayback() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 1. Dispatch Hardware Media PAUSE and STOP key events
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP))

            // 2. Seize Exclusive Transient AND Permanent Audio Focus
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()

            val transientFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(playbackAttributes)
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(transientFocusRequest)

            val permanentFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(permanentFocusRequest)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Active PiP Window Hunter:
     * Inspects all interactive windows for Picture-in-Picture mode, pauses media,
     * clicks Close/Dismiss nodes inside the PiP tree, sends GLOBAL_ACTION_BACK,
     * and kills YouTube's background process whenever YOUTUBE is locked.
     */
    private fun killPipWindowsIfAny() {
        try {
            val windowList = windows
            if (windowList != null) {
                for (window in windowList) {
                    if (window.isInPictureInPictureMode) {
                        stopAllMediaPlayback()
                        val root = window.root
                        if (root != null) {
                            dismissPipNodeRecursively(root)
                        }
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }
            }
            if (isAppLocked("YOUTUBE", applicationContext)) {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.killBackgroundProcesses("com.google.android.youtube")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dismissPipNodeRecursively(node: AccessibilityNodeInfo?) {
        if (node == null) return
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        if (desc.contains("close") || desc.contains("dismiss") || text.contains("close")) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            dismissPipNodeRecursively(node.getChild(i))
        }
    }

    override fun onInterrupt() {
        removeFloatingPill()
        currentForegroundAppKey?.let { saveStateToPrefs(it) }
        currentForegroundAppKey = null
    }

    override fun onDestroy() {
        super.onDestroy()
        removeFloatingPillCompletely()
        stopStopwatchTicker()
        currentForegroundAppKey?.let { saveStateToPrefs(it) }
        currentForegroundAppKey = null
    }

    companion object {
        private const val TAG = "AppBlockerService"
        const val TAMPER_WARNING_MESSAGE =
            "Tamper Protection: Cannot Uninstall or Reset InstaGuard while Focus Timer is active!"

        const val ALERTS_CHANNEL_ID = "instaguard_alerts_channel"
        const val YT_LONG_CHANNEL_ID = "instaguard_yt_longvideo_channel_v63"
        const val NOTIF_ID_5MIN = 1000
        const val NOTIF_ID_1MIN = 2000
        const val NOTIF_ID_YOUTUBE_LONG = 3000

        @Volatile
        var isOurAppScreenOpen: Boolean = false

        val APP_KEYS = listOf("INSTAGRAM", "YOUTUBE", "FACEBOOK", "TIKTOK")

        val IGNORED_SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.google.android.inputmethod.latin",
            "com.touchtype.swiftkey",
            "com.samsung.android.honeyboard",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller"
        )

        private val SYSTEM_TAMPER_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.miui.securitycenter",
            "com.transsion.phonemanager",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            "com.vivo.permissionmanager"
        )

        val accumulatedUsageMap = ConcurrentHashMap<String, Long>()
        val unlockTimestampMap = ConcurrentHashMap<String, Long>()
        val dailyTotalMap = ConcurrentHashMap<String, Long>()

        val warned5MinMap = ConcurrentHashMap<String, Boolean>()
        val warned1MinMap = ConcurrentHashMap<String, Boolean>()

        val customTrackedPackages = ConcurrentHashMap.newKeySet<String>()
        val scrollCountTodayMap = ConcurrentHashMap<String, Int>()
        val scrollCountTotalMap = ConcurrentHashMap<String, Int>()

        @Volatile
        var maintenanceUntilMs: Long = 0L

        @Volatile
        var currentForegroundAppKey: String? = null

        @Volatile
        var lastTickElapsedRealtime: Long = 0L

        @Volatile
        var appContext: Context? = null

        @Volatile
        var floatingPillView: View? = null

        @Volatile
        var pillTextView: TextView? = null

        @Volatile
        var tvPillScrolls: TextView? = null

        @Volatile
        var tvPillTimer: TextView? = null

        @Volatile
        var isPillAttached: Boolean = false

        fun getTodayDateStr(): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        }

        fun getAllTrackedKeys(): List<String> {
            return APP_KEYS + customTrackedPackages.toList()
        }

        fun mapPackageToAppKey(packageName: String): String? {
            val coreKey = TrackedApp.fromPackageName(packageName)?.key
            if (coreKey != null) return coreKey
            if (customTrackedPackages.contains(packageName)) return packageName
            return null
        }

        fun syncFromPrefs(context: Context) {
            appContext = context.applicationContext
            val prefs = GuardPreferences(context)
            val now = System.currentTimeMillis()
            val todayStr = getTodayDateStr()
            var allZero = true

            customTrackedPackages.clear()
            customTrackedPackages.addAll(prefs.getCustomTrackedPackages())
            maintenanceUntilMs = prefs.maintenanceUntilMs

            for (key in getAllTrackedKeys()) {
                val usage = prefs.getAccumulatedUsage(key)
                val unlock = prefs.getUnlockTimestamp(key)
                val daily = prefs.getDailyTotal(todayStr, key)
                val scrollToday = prefs.getScrollToday(todayStr, key)
                val scrollTotal = prefs.getScrollTotal(key)

                if (unlock > 0L && unlock <= now) {
                    accumulatedUsageMap[key] = 0L
                    unlockTimestampMap[key] = 0L
                    prefs.resetApp(key)
                } else {
                    accumulatedUsageMap[key] = usage
                    unlockTimestampMap[key] = unlock
                    if (usage > 0L || unlock > 0L) {
                        allZero = false
                    }
                }
                dailyTotalMap[key] = daily
                scrollCountTodayMap[key] = scrollToday
                scrollCountTotalMap[key] = scrollTotal
            }

            // Only reconstruct from OS system events if SharedPreferences is 100% empty
            if (allZero) {
                syncAndReconstructFromSystemEvents(context)
            }
        }

        fun getScrollToday(appKey: String, context: Context? = null): Int {
            val inMem = scrollCountTodayMap[appKey]
            if (inMem != null) return inMem
            val ctx = context ?: appContext
            return ctx?.let { GuardPreferences(it).getScrollToday(getTodayDateStr(), appKey) } ?: 0
        }

        fun getScrollTotal(appKey: String, context: Context? = null): Int {
            val inMem = scrollCountTotalMap[appKey]
            if (inMem != null) return inMem
            val ctx = context ?: appContext
            return ctx?.let { GuardPreferences(it).getScrollTotal(appKey) } ?: 0
        }

        fun getTotalScrollsToday(context: Context? = null): Int {
            var total = 0
            for (key in getAllTrackedKeys()) {
                total += getScrollToday(key, context)
            }
            return total
        }

        /**
         * Feature 3: Grants exactly 5 minutes of emergency access for an app (1x per day).
         */
        fun grantEmergencyFiveMinutes(context: Context, appKey: String) {
            appContext = context.applicationContext
            val prefs = GuardPreferences(context)
            val limitMs = prefs.getUsageLimitMs()
            val emergencyAllowance = if (prefs.isTestMode) 20_000L else 300_000L // 5 min or 20 sec

            unlockTimestampMap[appKey] = 0L
            val restoredUsage = maxOf(0L, limitMs - emergencyAllowance)
            accumulatedUsageMap[appKey] = restoredUsage

            prefs.setUnlockTimestamp(appKey, 0L)
            prefs.setAccumulatedUsage(appKey, restoredUsage)
            prefs.setEmergencyUsedDate(appKey, getTodayDateStr())

            // Mark 5-min warning as given so only final 1-min alert triggers
            warned5MinMap[appKey] = true
            warned1MinMap[appKey] = false
            Log.i(TAG, "[$appKey] 5-Minute Emergency Bypass granted! Usage set to $restoredUsage ms")
        }

        /**
         * Reads live daily total usage ms for an app for today's analytics.
         */
        fun getLiveDailyTotalMs(appKey: String, context: Context? = null): Long {
            val todayStr = getTodayDateStr()
            val inMem = dailyTotalMap[appKey]
            if (inMem != null && inMem > 0L) return inMem
            val ctx = context ?: appContext
            return ctx?.let { GuardPreferences(it).getDailyTotal(todayStr, appKey) } ?: 0L
        }

        /**
         * LAYER 1: OS-Level Usage & Cooldown Reconstruction via UsageStatsManager.queryEvents.
         * Runs ONLY when SharedPreferences is completely empty (e.g. after reinstall or clear data).
         * Will NEVER overwrite existing in-memory data with lower values.
         */
        fun syncAndReconstructFromSystemEvents(context: Context) {
            try {
                appContext = context.applicationContext
                if (!UsageStatsHelper.hasUsageStatsPermission(context)) {
                    return
                }

                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                    ?: return

                val prefs = GuardPreferences(context)
                val now = System.currentTimeMillis()
                val windowStart = now - 6 * 3_600_000L // Last 6 hours (1h limit + 5h cooldown)

                val limitMs = prefs.getUsageLimitMs()
                val cooldownMs = prefs.getCooldownDurationMs()

                val reconstructedUsage = HashMap<String, Long>()
                val reconstructedUnlockAt = HashMap<String, Long>()
                val lastResumeMap = HashMap<String, Long>()

                for (key in APP_KEYS) {
                    reconstructedUsage[key] = 0L
                    reconstructedUnlockAt[key] = 0L
                    lastResumeMap[key] = 0L
                }

                val usageEvents = usageStatsManager.queryEvents(windowStart, now)
                val event = UsageEvents.Event()

                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    val pkg = event.packageName ?: continue
                    val trackedApp = TrackedApp.fromPackageName(pkg) ?: continue
                    val appKey = trackedApp.key
                    val eventTime = event.timeStamp

                    val activeUnlock = reconstructedUnlockAt[appKey] ?: 0L
                    if (activeUnlock in 1..eventTime) {
                        reconstructedUnlockAt[appKey] = 0L
                        reconstructedUsage[appKey] = 0L
                        lastResumeMap[appKey] = 0L
                    }

                    if ((reconstructedUnlockAt[appKey] ?: 0L) > eventTime) {
                        lastResumeMap[appKey] = 0L
                        continue
                    }

                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> {
                            val prevResume = lastResumeMap[appKey] ?: 0L
                            if (prevResume > 0L && eventTime > prevResume) {
                                val delta = eventTime - prevResume
                                val prevUsage = reconstructedUsage[appKey] ?: 0L
                                val newUsage = prevUsage + delta
                                if (newUsage >= limitMs) {
                                    val needed = maxOf(0L, limitMs - prevUsage)
                                    val lockTriggeredAtMs = prevResume + needed
                                    val unlockAt = lockTriggeredAtMs + cooldownMs
                                    if (unlockAt > eventTime) {
                                        reconstructedUnlockAt[appKey] = unlockAt
                                        reconstructedUsage[appKey] = 0L
                                        lastResumeMap[appKey] = 0L
                                    } else {
                                        reconstructedUnlockAt[appKey] = 0L
                                        reconstructedUsage[appKey] = 0L
                                        lastResumeMap[appKey] = eventTime
                                    }
                                } else {
                                    reconstructedUsage[appKey] = newUsage
                                    lastResumeMap[appKey] = eventTime
                                }
                            } else {
                                lastResumeMap[appKey] = eventTime
                            }
                        }
                        UsageEvents.Event.ACTIVITY_PAUSED,
                        UsageEvents.Event.ACTIVITY_STOPPED -> {
                            val prevResume = lastResumeMap[appKey] ?: 0L
                            if (prevResume > 0L && eventTime >= prevResume) {
                                val delta = eventTime - prevResume
                                lastResumeMap[appKey] = 0L
                                val prevUsage = reconstructedUsage[appKey] ?: 0L
                                val newUsage = prevUsage + delta
                                if (newUsage >= limitMs) {
                                    val needed = maxOf(0L, limitMs - prevUsage)
                                    val lockTriggeredAtMs = prevResume + needed
                                    reconstructedUnlockAt[appKey] = lockTriggeredAtMs + cooldownMs
                                    reconstructedUsage[appKey] = 0L
                                } else {
                                    reconstructedUsage[appKey] = newUsage
                                }
                            }
                        }
                    }
                }

                // Finalize remaining in-progress foreground time up to `now`
                for (appKey in APP_KEYS) {
                    val activeUnlock = reconstructedUnlockAt[appKey] ?: 0L
                    if (activeUnlock in 1..now) {
                        reconstructedUnlockAt[appKey] = 0L
                        reconstructedUsage[appKey] = 0L
                    }

                    val lastResume = lastResumeMap[appKey] ?: 0L
                    if ((reconstructedUnlockAt[appKey] ?: 0L) == 0L && lastResume > 0L && now > lastResume) {
                        val delta = now - lastResume
                        val prevUsage = reconstructedUsage[appKey] ?: 0L
                        val newUsage = prevUsage + delta
                        if (newUsage >= limitMs) {
                            val needed = maxOf(0L, limitMs - prevUsage)
                            val lockTriggeredAtMs = lastResume + needed
                            val unlockAt = lockTriggeredAtMs + cooldownMs
                            if (unlockAt > now) {
                                reconstructedUnlockAt[appKey] = unlockAt
                                reconstructedUsage[appKey] = 0L
                            } else {
                                reconstructedUnlockAt[appKey] = 0L
                                reconstructedUsage[appKey] = 0L
                            }
                        } else {
                            reconstructedUsage[appKey] = newUsage
                        }
                    }

                    val recUnlock = reconstructedUnlockAt[appKey] ?: 0L
                    val recUsage = reconstructedUsage[appKey] ?: 0L
                    val currentUnlock = unlockTimestampMap[appKey] ?: prefs.getUnlockTimestamp(appKey)
                    val currentUsage = accumulatedUsageMap[appKey] ?: prefs.getAccumulatedUsage(appKey)

                    if (recUnlock > now && recUnlock > currentUnlock) {
                        unlockTimestampMap[appKey] = recUnlock
                        accumulatedUsageMap[appKey] = 0L
                        prefs.setUnlockTimestamp(appKey, recUnlock)
                        prefs.setAccumulatedUsage(appKey, 0L)
                        Log.w(TAG, "[$appKey] Restored 5h cooldown from OS UsageEvents: unlocks at $recUnlock")
                    } else if (currentUnlock <= now && recUsage > currentUsage) {
                        accumulatedUsageMap[appKey] = maxOf(currentUsage, recUsage)
                        prefs.setAccumulatedUsage(appKey, accumulatedUsageMap[appKey] ?: 0L)
                        Log.i(TAG, "[$appKey] Restored usage from OS UsageEvents: $recUsage ms")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun getLiveUsageMs(appKey: String, context: Context? = null): Long {
            val unlockAt = unlockTimestampMap[appKey] ?: 0L
            if (unlockAt > 0L && System.currentTimeMillis() >= unlockAt) {
                unlockTimestampMap[appKey] = 0L
                accumulatedUsageMap[appKey] = 0L
                val ctx = context ?: appContext
                ctx?.let { GuardPreferences(it).resetApp(appKey) }
            }
            return maxOf(0L, accumulatedUsageMap[appKey] ?: 0L)
        }

        fun getRemainingLockMs(appKey: String, context: Context? = null): Long {
            val unlockAt = unlockTimestampMap[appKey] ?: 0L
            val now = System.currentTimeMillis()
            val remaining = unlockAt - now

            if (unlockAt > 0L && remaining <= 0L) {
                unlockTimestampMap[appKey] = 0L
                accumulatedUsageMap[appKey] = 0L
                val ctx = context ?: appContext
                ctx?.let { GuardPreferences(it).resetApp(appKey) }
                return 0L
            }

            return maxOf(0L, remaining)
        }

        fun isAppLocked(appKey: String, context: Context? = null): Boolean {
            return getRemainingLockMs(appKey, context) > 0L
        }

        fun unlockApp(appKey: String, context: Context? = null) {
            unlockTimestampMap[appKey] = 0L
            accumulatedUsageMap[appKey] = 0L
            val ctx = context ?: appContext
            ctx?.let { GuardPreferences(it).resetApp(appKey) }
        }
    }
}
