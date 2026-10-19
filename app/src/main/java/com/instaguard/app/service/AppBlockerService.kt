package com.instaguard.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Display
import java.util.ArrayDeque
import java.util.concurrent.Executor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.FaceDetector
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
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
import com.instaguard.app.InstaGuardWidgetProvider
import com.instaguard.app.R
import com.instaguard.app.data.GuardPreferences
import com.instaguard.app.model.TrackedApp
import com.instaguard.app.ui.LockScreenActivity
import com.instaguard.app.ui.MainActivity
import com.instaguard.app.util.UsageStatsHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance AccessibilityService that monitors Instagram, YouTube, Facebook,
 * and TikTok independently in real-time with millisecond precision.
 * v8.0 Master Edition Features:
 * - Monotonic 50ms Delta Stopwatch Engine (SystemClock.elapsedRealtime) with zero timer drift.
 * - Strict 30-minute usage limit and hardened 5-hour cooldown lock per app.
 * - AI Voice Warnings (TextToSpeech) with Custom Voice Alert Messages & Notification-First rule.
 * - Only Reels & Shorts Blocker Mode, Night Sleep Guard (11:30 PM - 6:00 AM), and Deep Work Mode with Cancel support.
 * - Last 5-Minute Dopamine Killer Dark/Red Warning Tint Overlay (non-touchable, zero flicker).
 * - Weakness & Blocked Attempts Log with 5-second debounce.
 * - Midnight Auto-Reset (12:00 AM) & Daily History Archiving.
 * - Emergency 5-Minute Master PIN Bypass support.
 * - Layer 1: OS-level UsageEvents reconstruction when data is empty.
 * - Layer 2: Anti-Uninstall, Anti-Force-Stop, and Anti-Clear-Data Tamper Shield.
 * - 3-Layer YouTube/Shorts Picture-in-Picture (PiP) Killer Logic.
 */
class AppBlockerService : AccessibilityService(), TextToSpeech.OnInitListener {

    private lateinit var prefs: GuardPreferences
    private val tickerHandler = Handler(Looper.getMainLooper())
    private var isTickerRunning = false
    private var tickCounter = 0
    private var widgetTickCounter = 0
    private var lastTamperBlockMs = 0L
    private var lastPillText = ""
    private var lastScrollsText = ""
    private var lastTimerText = ""

    // v7.0 / v8.0 TextToSpeech, Dopamine Dimmer & Blocked Attempt Debounce State
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var lastBlockedVoiceAlertElapsedMs = 0L
    private var lastReelsBlockAlertElapsedMs = 0L
    private var lastBlockedAttemptLogElapsedMs = 0L
    private var lastDopamineTintColor = 0
    private var lastDopamineTintUpdateElapsedMs = 0L

    // v9.4 Real-Time 4-Stage AI Vision & Face Detector State
    private val visualAnalyzerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile
    private var isScreenshotInProgress = false
    private var lastScreenshotElapsedMs = 0L
    private var lastAutoSkipElapsedMs = 0L
    private var lastBrowserBlockElapsedMs = 0L
    private var previousSkinGrid: BooleanArray? = null
    private var lastAnalyzedAppKey: String? = null
    private var faceDetector: FaceDetector? = null
    private var analyzerAppKey: String? = null
    private var consecutivePositiveFrames = 0

    // Protected Foreground App State (Zero-Flicker & Resilience)
    @Volatile
    private var lastValidTrackedAppKey: String? = null
    @Volatile
    private var lastValidAppSeenElapsedMs: Long = 0L

    // v6.3 Post-Scroll Settled Verification State
    private var lastScrollCountElapsedMs = 0L
    private var lastCountedScreenSignature = ""
    private var lastCountedFromIndex = -1
    private var lastCountedToIndex = -1
    private var pendingScrollFromIndex = -1
    private var pendingScrollToIndex = -1
    private var hadVideoVisibleBeforeScroll = false

    // v6.5 YouTube Long Video 10-Second Watch & Feed State Machine
    @Volatile
    private var isYoutubeLongVideoTimerRunning = false
    @Volatile
    private var youtubeLongVideoStartElapsedMs = 0L
    private var isYoutubeOnHomeFeed = true
    private var isYoutubeWatchPlayerOpen = false
    private var hasNotifiedForCurrentWatchSession = false
    private var lastYoutubeHomeClickElapsedMs = 0L
    private var lastYoutubeNotificationElapsedMs = 0L

    private val verifyScrollRunnable = Runnable {
        val activeKey = currentForegroundAppKey ?: return@Runnable
        val now = SystemClock.elapsedRealtime()
        if (now - lastScrollCountElapsedMs < 750L) return@Runnable

        val root = try { rootInActiveWindow } catch (e: Exception) { null }
        val currentSig = getScreenContentSignature(root)
        val indexChanged = (pendingScrollFromIndex >= 0 && pendingScrollFromIndex != lastCountedFromIndex) ||
            (pendingScrollToIndex >= 0 && pendingScrollToIndex != lastCountedToIndex)
        val signatureChanged = currentSig.isNotEmpty() && currentSig != lastCountedScreenSignature
        val confirmRealFullScroll = indexChanged || signatureChanged || currentSig.isEmpty()

        if (!confirmRealFullScroll) return@Runnable

        var shouldIncrement = false
        when (activeKey) {
            "TIKTOK" -> {
                shouldIncrement = true
            }
            "INSTAGRAM", "FACEBOOK" -> {
                val inReels = isReelsOrWatchTabActive(activeKey, root)
                val hasVideoNow = hasVideoElementOnScreen(root)
                if (inReels || hadVideoVisibleBeforeScroll || hasVideoNow || currentSig.isEmpty()) {
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
                lastValidTrackedAppKey = null
                removeFloatingPill()
                hideDopamineTintOverlay()
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
            if (rootPkg != null) {
                if (!IGNORED_SYSTEM_PACKAGES.contains(rootPkg)) {
                    val mapped = mapPackageToAppKey(rootPkg)
                    if (mapped != null) {
                        currentForegroundAppKey = mapped
                        lastValidTrackedAppKey = mapped
                        lastValidAppSeenElapsedMs = nowElapsed
                    } else if (rootPkg == applicationContext.packageName) {
                        if (isOurAppScreenOpen) {
                            currentForegroundAppKey = null
                            lastValidTrackedAppKey = null
                        }
                    } else if (isLauncherPackage(rootPkg)) {
                        currentForegroundAppKey = null
                        lastValidTrackedAppKey = null
                    }
                }
            } else {
                // rootPkg is null (transient during screenshot capture or window transition)
                // Retain currentForegroundAppKey if lastValidTrackedAppKey was seen within last 2000ms
                if (lastValidTrackedAppKey != null && (nowElapsed - lastValidAppSeenElapsedMs < 2000L)) {
                    currentForegroundAppKey = lastValidTrackedAppKey
                }
            }

            // 4. Increment usage counter and daily total for active tracked app
            val activeKey = currentForegroundAppKey
            if (activeKey != null) {
                // Step 4: YouTube Long Video 10-Second Watch tracking
                if (activeKey == "YOUTUBE") {
                    // Check watch player state every 10 ticks (~500ms)
                    if (tickCounter % 10 == 0) {
                        val root = try { rootInActiveWindow } catch (e: Exception) { null }
                        val isHome = isYoutubeHomePageActive(root)
                        val isShorts = isYoutubeShortsMode(root)
                        val isPlayerExpanded = isYoutubeLongVideoPlayerExpanded(root)

                        if (isHome || isShorts) {
                            // User returned to Home feed or opened Shorts
                            isYoutubeOnHomeFeed = isHome
                            isYoutubeWatchPlayerOpen = false
                            hasNotifiedForCurrentWatchSession = false
                            lastYoutubeHomeClickElapsedMs = 0L
                            if (isYoutubeLongVideoTimerRunning) {
                                // Exited video before 10 seconds finished
                                isYoutubeLongVideoTimerRunning = false
                            }
                        } else if (isPlayerExpanded) {
                            isYoutubeWatchPlayerOpen = true
                            isYoutubeOnHomeFeed = false
                            val now = SystemClock.elapsedRealtime()
                            // Trigger if user opened long video via Home click in the last 4 seconds
                            if (!hasNotifiedForCurrentWatchSession && !isYoutubeLongVideoTimerRunning &&
                                (now - lastYoutubeNotificationElapsedMs >= 4000L) &&
                                (lastYoutubeHomeClickElapsedMs > 0L && now - lastYoutubeHomeClickElapsedMs < 4000L)
                            ) {
                                hasNotifiedForCurrentWatchSession = true
                                lastYoutubeNotificationElapsedMs = now
                                startYoutubeLongVideoCountdown()
                            }
                        }
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
                    isYoutubeWatchPlayerOpen = false
                    hasNotifiedForCurrentWatchSession = false
                    isYoutubeOnHomeFeed = true
                    lastYoutubeHomeClickElapsedMs = 0L
                }

                if (isAppLocked(activeKey, applicationContext)) {
                    currentForegroundAppKey = null
                    removeFloatingPill()
                    hideDopamineTintOverlay()
                    recordBlockedOpenAttempt(activeKey)
                    stopAllMediaPlayback()
                    launchLockScreen(activeKey)
                } else {
                    // v7.0 Feature 2: Only Reels & Shorts Blocker Mode
                    if (prefs.isOnlyReelsShortsMode) {
                        if (activeKey == "TIKTOK") {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            if (nowElapsed - lastReelsBlockAlertElapsedMs >= 4000L) {
                                lastReelsBlockAlertElapsedMs = nowElapsed
                                fireNotificationThenVoice(
                                    3088,
                                    "🚫 Reels & Shorts Blocked!",
                                    "Only Reels & Shorts Blocker Mode is active.",
                                    "Reels and Shorts are blocked! Stick to messages or study videos."
                                )
                            }
                        } else if ((activeKey == "INSTAGRAM" || activeKey == "YOUTUBE" || activeKey == "FACEBOOK") && tickCounter % 6 == 0) {
                            val root = try { rootInActiveWindow } catch (e: Exception) { null }
                            val isReelsOrShorts = if (activeKey == "YOUTUBE") {
                                isYoutubeShortsMode(root)
                            } else {
                                isReelsOrWatchTabActive(activeKey, root)
                            }
                            if (isReelsOrShorts) {
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                if (nowElapsed - lastReelsBlockAlertElapsedMs >= 4000L) {
                                    lastReelsBlockAlertElapsedMs = nowElapsed
                                    fireNotificationThenVoice(
                                        3088,
                                        "🚫 Reels & Shorts Blocked!",
                                        "Only Reels & Shorts Blocker Mode is active.",
                                        "Reels and Shorts are blocked! Stick to messages or study videos."
                                    )
                                }
                            }
                        }
                    }

                    val updatedUsage = (accumulatedUsageMap[activeKey] ?: 0L) + deltaMs
                    accumulatedUsageMap[activeKey] = updatedUsage

                    // Update today's daily archive counter in memory
                    val todayStr = getTodayDateStr()
                    val currentDaily = dailyTotalMap[activeKey] ?: prefs.getDailyTotal(todayStr, activeKey)
                    dailyTotalMap[activeKey] = currentDaily + deltaMs

                    val limitMs = prefs.getUsageLimitMs()
                    val remainingAllowance = maxOf(0L, limitMs - updatedUsage)
                    updateFloatingPill(activeKey, remainingAllowance)
                    updateDopamineTintOverlay(activeKey, remainingAllowance)

                    // Smart Warning Notifications + Voice Alerts (10-Min, 5-Min & 1-Min)
                    checkAndTriggerWarningNotifications(activeKey, updatedUsage, limitMs)

                    if (updatedUsage >= limitMs) {
                        Log.w(TAG, "[$activeKey] Limit reached ($updatedUsage >= $limitMs). Locking for 5 hours.")
                        removeFloatingPill()
                        hideDopamineTintOverlay()
                        val cooldownMs = prefs.getCooldownDurationMs()
                        unlockTimestampMap[activeKey] = System.currentTimeMillis() + cooldownMs
                        accumulatedUsageMap[activeKey] = 0L
                        currentForegroundAppKey = null

                        // Reset warning flags on lock
                        warned10MinMap[activeKey] = false
                        warned5MinMap[activeKey] = false
                        warned1MinMap[activeKey] = false

                        saveStateToPrefs(activeKey)
                        stopAllMediaPlayback()
                        launchLockScreen(activeKey)
                    }
                }
            } else {
                isYoutubeLongVideoTimerRunning = false
                isYoutubeWatchPlayerOpen = false
                hasNotifiedForCurrentWatchSession = false
                isYoutubeOnHomeFeed = true
                lastYoutubeHomeClickElapsedMs = 0L
                if (isPillAttached) {
                    removeFloatingPill()
                }
                hideDopamineTintOverlay()
            }

            // 4a. Periodic Browser Explicit Search & Video Inspection (every 5 ticks = 250ms)
            if (tickCounter % 5 == 0 && prefs.isAiVisualShieldEnabled) {
                val rootPkg = try { rootInActiveWindow?.packageName?.toString() } catch (e: Exception) { null }
                if (rootPkg != null && rootPkg in BROWSER_PACKAGES) {
                    checkBrowserForExplicitContent(rootPkg)
                }
            }

            // 4b. Real-Time 4-Stage AI Vision Scanner & Auto-Skip on active social media apps (every 8 ticks = ~400ms)
            if (activeKey != null && tickCounter % 8 == 0 && prefs.isAiVisualShieldEnabled) {
                val root = try { rootInActiveWindow } catch (e: Exception) { null }
                val isYtHome = activeKey == "YOUTUBE" && isYoutubeHomePageActive(root)
                if (!isYtHome) {
                    if (hasStrictExplicitTags(root)) {
                        triggerAutoSkipIfCooldownElapsed()
                    } else if (hasVideoElementOnScreen(root)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isScreenshotInProgress) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastScreenshotElapsedMs >= 400L) {
                                captureAndAnalyzeSocialFrame(activeKey)
                            }
                        }
                    }
                }
            }

            // 5. Periodic auto-checkpoint persistence every 40 ticks (~2000ms)
            tickCounter++
            if (tickCounter >= 120) {
                tickCounter = 0
            }
            if (tickCounter % 40 == 0) {
                checkMidnightCrossover()
                if (activeKey != null) {
                    saveStateToPrefs(activeKey)
                }
            }

            // 6. Periodic Home Screen Widget refresh every 1200 ticks (~60 seconds)
            widgetTickCounter++
            if (widgetTickCounter >= 1200) {
                widgetTickCounter = 0
                InstaGuardWidgetProvider.updateAllWidgets(applicationContext)
            }

            tickerHandler.postDelayed(this, 50L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        activeServiceInstance = this
        prefs = GuardPreferences(applicationContext)
        initNotificationChannel()
        initTextToSpeech()
        syncFromPrefs(applicationContext)
        checkMidnightCrossover()
        startStopwatchTicker()
        Log.i(TAG, "AppBlockerService created; Monotonic 50ms Stopwatch Engine started.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        appContext = applicationContext
        activeServiceInstance = this
        prefs = GuardPreferences(applicationContext)
        initNotificationChannel()
        initTextToSpeech()
        syncFromPrefs(applicationContext)
        checkMidnightCrossover()
        startStopwatchTicker()
        Log.i(TAG, "AppBlockerService connected; Monotonic 50ms Stopwatch Engine running.")
    }

    private fun initTextToSpeech() {
        if (tts == null) {
            try {
                tts = TextToSpeech(applicationContext, this)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.95f)
                tts?.setPitch(1.0f)
                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttrs)
                isTtsReady = true
                Log.i(TAG, "TextToSpeech initialized successfully.")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel = NotificationChannel(
                ALERTS_CHANNEL_ID,
                "Focus Limit Warnings",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when your daily focus quota is almost up"
                enableVibration(true)
            }
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

            val voiceChannel = NotificationChannel(
                VOICE_ALERTS_CHANNEL_ID,
                "InstaGuard Voice & Focus Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority focus warnings paired with AI voice alerts"
                enableVibration(true)
            }
            nm.createNotificationChannel(voiceChannel)
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

        // v9.0 Feature 3: Real-Time Browser Search & Explicit Video Inspector
        if (packageName in BROWSER_PACKAGES) {
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
            ) {
                checkBrowserForExplicitContent(packageName, event)
            }
        }

        // v9.4 Strict UI Hashtag/Caption metadata check on content change
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val activeKey = currentForegroundAppKey
            if (activeKey != null && prefs.isAiVisualShieldEnabled) {
                val root = try { rootInActiveWindow } catch (e: Exception) { null }
                val isYtHome = activeKey == "YOUTUBE" && isYoutubeHomePageActive(root)
                if (!isYtHome && hasStrictExplicitTags(root)) {
                    triggerAutoSkipIfCooldownElapsed()
                }
            }
        }

        // v6.5 Trigger A: YouTube Video Item Click Detection (Delayed Watch Verification)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val activeKey = currentForegroundAppKey ?: return
            if (activeKey == "YOUTUBE") {
                val root = try { rootInActiveWindow } catch (e: Exception) { null }
                if (!isYoutubeShortsMode(root)) {
                    val screenHeight = resources.displayMetrics.heightPixels
                    val source = event.source
                    val rect = Rect()
                    source?.getBoundsInScreen(rect)

                    val clickedText = "${source?.text ?: ""} ${source?.contentDescription ?: ""}".trim()
                    val parentText = "${source?.parent?.text ?: ""} ${source?.parent?.contentDescription ?: ""}".trim()
                    val combined = "$clickedText $parentText".lowercase(Locale.ROOT)

                    val isNavOrFilter = combined.contains("home") || combined.contains("shorts") ||
                        combined.contains("subscriptions") || combined.contains("you") ||
                        combined.contains("library") || combined.contains("search") ||
                        combined.contains("notifications") || combined.contains("cast")

                    val inMainFeedArea = source == null || (rect.centerY() > (screenHeight * 0.12).toInt() && rect.centerY() < (screenHeight * 0.90).toInt())

                    // Only track click if we are currently on Home feed and watch player is NOT already open
                    if (inMainFeedArea && !isNavOrFilter && !isYoutubeWatchPlayerOpen) {
                        lastYoutubeHomeClickElapsedMs = SystemClock.elapsedRealtime()

                        // Post verification checks at 500ms and 1000ms after the click to verify if watch player opened
                        tickerHandler.postDelayed({
                            verifyAndTriggerYoutubeLongVideo()
                        }, 500L)
                        tickerHandler.postDelayed({
                            verifyAndTriggerYoutubeLongVideo()
                        }, 1000L)
                    }
                }
            }
            return
        }

        // v9.3 Step 3: 250ms Post-Scroll Settled Verification Engine
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val activeKey = currentForegroundAppKey ?: return
            val now = SystemClock.elapsedRealtime()

            // 1. Debounce between counted gestures (750ms minimum between counts)
            if (now - lastScrollCountElapsedMs < 750L) {
                return
            }

            pendingScrollFromIndex = event.fromIndex
            pendingScrollToIndex = event.toIndex

            val root = try { rootInActiveWindow } catch (e: Exception) { null }
            if (hasVideoElementOnScreen(root)) {
                hadVideoVisibleBeforeScroll = true
            }

            // Schedule (or reset) delayed 250ms verification after finger swipe settles
            tickerHandler.removeCallbacks(verifyScrollRunnable)
            tickerHandler.postDelayed(verifyScrollRunnable, 250L)
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
                lastValidTrackedAppKey = null
                removeFloatingPill()
                hideDopamineTintOverlay()
            }
            return
        }

        // 5. Check if foreground window matches one of our tracked apps
        val detectedKey = mapPackageToAppKey(packageName)
        val nowState = SystemClock.elapsedRealtime()
        if (detectedKey != null) {
            lastValidTrackedAppKey = detectedKey
            lastValidAppSeenElapsedMs = nowState
            if (isAppLocked(detectedKey, applicationContext)) {
                Log.w(TAG, "[$detectedKey] Opened during active cooldown lock. Enforcing LockScreen.")
                currentForegroundAppKey = null
                removeFloatingPill()
                hideDopamineTintOverlay()
                recordBlockedOpenAttempt(detectedKey)
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
                updateDopamineTintOverlay(detectedKey, remainingAllowance)
            }
        } else {
            // User switched to another real application or Home Launcher
            if (isLauncherPackage(packageName) || !IGNORED_SYSTEM_PACKAGES.contains(packageName)) {
                currentForegroundAppKey?.let { saveStateToPrefs(it) }
                currentForegroundAppKey = null
                lastValidTrackedAppKey = null
                removeFloatingPill()
                hideDopamineTintOverlay()
            }
        }
    }

    fun speakImmediate(voiceText: String) {
        tickerHandler.post {
            try {
                if (tts == null) {
                    initTextToSpeech()
                }
                tts?.speak(voiceText, TextToSpeech.QUEUE_FLUSH, null, "tts_preview_${System.currentTimeMillis()}")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * v7.0 Feature 1: Fires High-Importance Notification FIRST, followed 350ms later by TextToSpeech voice alert.
     */
    private fun fireNotificationThenVoice(
        notificationId: Int,
        title: String,
        message: String,
        voiceText: String
    ) {
        // STEP 1 (Fire Notification FIRST)
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    VOICE_ALERTS_CHANNEL_ID,
                    "InstaGuard Voice & Focus Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "High-priority focus warnings paired with AI voice alerts"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }

            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(applicationContext, VOICE_ALERTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            nm.notify(notificationId, builder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // STEP 2 (Play Voice Alert SECOND after 350ms so notification arrives first & media focus settles)
        tickerHandler.postDelayed({
            try {
                if (tts == null) {
                    initTextToSpeech()
                }
                tts?.speak(voiceText, TextToSpeech.QUEUE_FLUSH, null, "tts_alert_$notificationId")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 350L)
    }

    /**
     * v7.0 / v8.0: Fires Notification + Custom AI Voice Warning at 10 Minutes, 5 Minutes, and 1 Minute remaining.
     */
    private fun checkAndTriggerWarningNotifications(appKey: String, usedMs: Long, limitMs: Long) {
        val remainingMs = limitMs - usedMs
        val isTestMode = prefs.isTestMode
        val threshold10Min = if (isTestMode) 30_000L else 600_000L // 10 min or 30 sec
        val threshold5Min = if (isTestMode) 15_000L else 300_000L  // 5 min or 15 sec
        val threshold1Min = if (isTestMode) 5_000L else 60_000L    // 1 min or 5 sec

        val trackedApp = TrackedApp.fromKey(appKey)
        val appName = trackedApp?.displayName ?: appKey
        val customWarnSuffix = prefs.customVoiceWarningMsg.trim()
            .ifEmpty { GuardPreferences.DEFAULT_CUSTOM_VOICE_WARNING_MSG }

        // 1. 10-Minute Warning Alert
        if (remainingMs in (threshold5Min + 1)..threshold10Min && warned10MinMap[appKey] != true) {
            warned10MinMap[appKey] = true
            fireNotificationThenVoice(
                notificationId = 3010,
                title = "⏳ 10 Minutes Left on $appName!",
                message = "Only 10 minutes remaining before your 5-hour focus lock. $customWarnSuffix",
                voiceText = "Attention! Only 10 minutes left on $appName. $customWarnSuffix"
            )
        }

        // 2. 5-Minute Warning Alert
        if (remainingMs in (threshold1Min + 1)..threshold5Min && warned5MinMap[appKey] != true) {
            warned10MinMap[appKey] = true
            warned5MinMap[appKey] = true
            fireNotificationThenVoice(
                notificationId = 3005,
                title = "⚠️ 5 Minutes Left on $appName!",
                message = "Your 30-minute limit is almost up. $customWarnSuffix",
                voiceText = "Attention! Only 5 minutes left on $appName. $customWarnSuffix"
            )
        }

        // 3. 1-Minute Warning Alert
        if (remainingMs in 1..threshold1Min && warned1MinMap[appKey] != true) {
            warned10MinMap[appKey] = true
            warned5MinMap[appKey] = true
            warned1MinMap[appKey] = true
            fireNotificationThenVoice(
                notificationId = 3001,
                title = "🚨 Final 1 Minute on $appName!",
                message = "Locking $appName for 5 hours in 60 seconds! $customWarnSuffix",
                voiceText = "Attention! Only 1 minute left on $appName. $customWarnSuffix"
            )
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
            // Feature 1: Reset today's scroll count and blocked attempts on midnight
            scrollCountTodayMap.clear()
            blockedAttemptsTodayMap.clear()

            // Reset warning notification flags
            warned10MinMap.clear()
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

    /**
     * v8.0 Feature 4: Records every attempt to open a locked app with a strict 5-second debounce.
     */
    private fun recordBlockedOpenAttempt(appKey: String) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastBlockedAttemptLogElapsedMs < 5000L) return
        lastBlockedAttemptLogElapsedMs = nowElapsed

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

        val todayStr = getTodayDateStr()
        val todayCount = (blockedAttemptsTodayMap[appKey] ?: prefs.getBlockedAttemptsToday(todayStr, appKey)) + 1
        val totalCount = (blockedAttemptsTotalMap[appKey] ?: prefs.getBlockedAttemptsTotal(appKey)) + 1
        blockedAttemptsTodayMap[appKey] = todayCount
        blockedAttemptsTotalMap[appKey] = totalCount

        prefs.setBlockedAttemptsToday(todayStr, appKey, todayCount)
        prefs.setBlockedAttemptsTotal(appKey, totalCount)

        val timeStr = SimpleDateFormat("hh:mm a", Locale.US).format(Date())
        val newEntry = "$timeStr • Tried opening $appDisplayName (Locked)"
        val existingLog = prefs.getBlockedAttemptsLog(todayStr)
        val updatedLines = mutableListOf(newEntry)
        if (existingLog.isNotBlank()) {
            existingLog.lines().filter { it.isNotBlank() }.take(7).forEach { updatedLines.add(it) }
        }
        prefs.setBlockedAttemptsLog(todayStr, updatedLines.joinToString("\n"))
    }

    private fun launchLockScreen(appKey: String) {
        removeFloatingPill()
        hideDopamineTintOverlay()
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

        // Delayed sweeps to crush PiP transition (before 350ms TTS voice alert starts)
        Handler(Looper.getMainLooper()).postDelayed({
            stopAllMediaPlayback()
            killPipWindowsIfAny()
        }, 120L)

        Handler(Looper.getMainLooper()).postDelayed({
            stopAllMediaPlayback()
            killPipWindowsIfAny()
        }, 240L)

        // v7.0 / v8.0 Feature 2: Notification-First + Custom AI Voice Warning on Blocked App Open / Lock Trigger
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastBlockedVoiceAlertElapsedMs >= 4000L) {
            lastBlockedVoiceAlertElapsedMs = nowElapsed
            val customBlockedMsg = prefs.customVoiceBlockedMsg.trim()
                .ifEmpty { GuardPreferences.DEFAULT_CUSTOM_VOICE_BLOCKED_MSG }
                .replace("{app}", appDisplayName, ignoreCase = true)
            fireNotificationThenVoice(
                notificationId = 3099,
                title = "🔒 $appDisplayName is Blocked!",
                message = customBlockedMsg,
                voiceText = customBlockedMsg
            )
        }

        InstaGuardWidgetProvider.updateAllWidgets(applicationContext)
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

    /**
     * v8.0 Feature 3: Non-Touchable Full-Screen Dopamine Dimmer Tint Overlay.
     * Added to WindowManager ONCE below the Top-Center Floating Pill so the pill stays bright on top
     * and never flickers.
     */
    private fun createDopamineTintLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun ensureDopamineTintOverlayAttached() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        if (isDopamineTintAttached && dopamineTintOverlayView != null) return

        val tintView = View(this).apply {
            setBackgroundColor(Color.parseColor("#55280505"))
            visibility = View.GONE
        }
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                wm.addView(tintView, createDopamineTintLayoutParams())
                dopamineTintOverlayView = tintView
                isDopamineTintAttached = true
                lastDopamineTintColor = Color.parseColor("#55280505")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach dopamine tint overlay: ${e.message}")
            dopamineTintOverlayView = null
            isDopamineTintAttached = false
        }
    }

    private fun updateDopamineTintOverlay(activeKey: String, remainingMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            hideDopamineTintOverlay()
            return
        }
        if (!prefs.isDopamineDimmerEnabled || isOurAppScreenOpen || activeKey.isEmpty()) {
            hideDopamineTintOverlay()
            return
        }

        val isTestMode = prefs.isTestMode
        val warningWindowMs = if (isTestMode) 20_000L else 300_000L // Last 5 Minutes (or 20s in Test Mode)
        val finalMinuteThresholdMs = if (isTestMode) 10_000L else 60_000L // Final 1 Minute (or 10s in Test Mode)

        if (!isAppLocked(activeKey, applicationContext) && remainingMs in 1L..warningWindowMs) {
            if (!isDopamineTintAttached || dopamineTintOverlayView == null) {
                ensureDopamineTintOverlayAttached()
            }
            val view = dopamineTintOverlayView ?: return
            val targetColor = if (remainingMs <= finalMinuteThresholdMs) {
                Color.parseColor("#773B0707") // Deeper Warning Red Dimmer (Final 1 Minute)
            } else {
                Color.parseColor("#55280505") // Subtle Dark Crimson/Amber Dopamine Dimmer (5m down to 1m)
            }

            val nowElapsed = SystemClock.elapsedRealtime()
            if (view.visibility != View.VISIBLE) {
                view.setBackgroundColor(targetColor)
                lastDopamineTintColor = targetColor
                lastDopamineTintUpdateElapsedMs = nowElapsed
                view.visibility = View.VISIBLE
            } else if (targetColor != lastDopamineTintColor && nowElapsed - lastDopamineTintUpdateElapsedMs >= 1000L) {
                view.setBackgroundColor(targetColor)
                lastDopamineTintColor = targetColor
                lastDopamineTintUpdateElapsedMs = nowElapsed
            }
        } else {
            hideDopamineTintOverlay()
        }
    }

    private fun hideDopamineTintOverlay() {
        val view = dopamineTintOverlayView
        if (view != null && view.visibility == View.VISIBLE) {
            view.visibility = View.GONE
        }
    }

    private fun removeDopamineTintOverlayCompletely() {
        val view = dopamineTintOverlayView
        if (isDopamineTintAttached && view != null) {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing dopamine tint overlay: ${e.message}")
            }
            isDopamineTintAttached = false
            dopamineTintOverlayView = null
            lastDopamineTintColor = 0
        }
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

        // Attach Dopamine Tint Overlay FIRST (once) so the Top-Center Floating Pill is always above it in Z-order
        ensureDopamineTintOverlayAttached()

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

    /**
     * v8.2 Feature: Crisp Double-Pulse Vibration pattern:
     * Pulse 1 (220ms) -> gap (120ms) -> Pulse 2 (280ms)
     */
    private fun triggerDoublePulseVibration() {
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                val timings = longArrayOf(0L, 220L, 120L, 280L)
                val amplitudes = intArrayOf(0, 255, 0, 255)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(timings, -1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun incrementScrollCount(appKey: String) {
        val todayCount = (scrollCountTodayMap[appKey] ?: 0) + 1
        val totalCount = (scrollCountTotalMap[appKey] ?: 0) + 1
        scrollCountTodayMap[appKey] = todayCount
        scrollCountTotalMap[appKey] = totalCount

        val todayDateStr = getTodayDateStr()
        prefs.setScrollToday(todayDateStr, appKey, todayCount)
        prefs.setScrollTotal(appKey, totalCount)
        InstaGuardWidgetProvider.updateAllWidgets(applicationContext)

        // v8.2: Every 15 Scrolls Double-Pulse Vibration ONLY (no voice, no notification)
        if (todayCount > 0 && todayCount % 15 == 0) {
            triggerDoublePulseVibration()

            tickerHandler.post {
                try {
                    (floatingPillView?.background as? GradientDrawable)?.setStroke(dpToPx(1.5f), Color.parseColor("#EF4444"))
                    tickerHandler.postDelayed({
                        try {
                            (floatingPillView?.background as? GradientDrawable)?.setStroke(dpToPx(1f), Color.parseColor("#38FFFFFF"))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }, 1200L)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // =========================================================================
    // v9.4 FEATURE 2: Strict 4-Stage Frame-by-Frame Female & Body-Zone Analyzer
    // ALL bitmap copying, scaling, face detection and pixel loops run on
    // visualAnalyzerExecutor. The Main Thread only receives the final verdict.
    // =========================================================================

    private class DetectedFace(val x: Float, val y: Float, val eyeDist: Float)

    private class FrameAnalysis(
        val w: Int,
        val h: Int,
        val skin: BooleanArray,
        val hair: BooleanArray,
        val subject: BooleanArray,
        val lum: FloatArray,
        val faces: List<DetectedFace>
    )

    private fun captureAndAnalyzeSocialFrame(appKey: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || isScreenshotInProgress) return
        isScreenshotInProgress = true
        lastScreenshotElapsedMs = SystemClock.elapsedRealtime()

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                visualAnalyzerExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val hwBuffer = screenshot.hardwareBuffer
                        try {
                            val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                            val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBuffer.close()
                            hwBitmap?.recycle()
                            if (swBitmap != null) {
                                val shouldSkip = runFourStagePipeline(swBitmap, appKey)
                                swBitmap.recycle()
                                if (shouldSkip) {
                                    tickerHandler.post { triggerAutoSkipIfCooldownElapsed() }
                                }
                            }
                        } catch (e: Exception) {
                            try { hwBuffer.close() } catch (_: Exception) {}
                            e.printStackTrace()
                        } finally {
                            isScreenshotInProgress = false
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        isScreenshotInProgress = false
                    }
                }
            )
        } catch (e: Exception) {
            isScreenshotInProgress = false
            e.printStackTrace()
        }
    }

    /**
     * STAGE 5 orchestrator (runs on visualAnalyzerExecutor).
     * Stage 1 gate first; Stages 2/3/4 only when a female is confirmed.
     * Returns true ONLY after REQUIRED_POSITIVE_FRAMES consecutive positive frames.
     */
    private fun runFourStagePipeline(full: Bitmap, appKey: String): Boolean {
        if (analyzerAppKey != appKey) {
            analyzerAppKey = appKey
            previousSkinGrid = null
            consecutivePositiveFrames = 0
        }

        val faces = detectFaces(full)
        val scaled = Bitmap.createScaledBitmap(full, ANALYZER_W, ANALYZER_H, true)
        val frame = buildFrameAnalysis(scaled, faces)
        if (scaled != full) scaled.recycle()

        val gluteMotionDelta = computeGluteMotionDelta(frame)
        previousSkinGrid = frame.skin

        // STAGE 1: No female in frame -> never skip.
        if (!isFemaleHumanPresentInFrame(frame)) {
            consecutivePositiveFrames = 0
            return false
        }

        val stage2 = isWearingShortRevealingDress(frame)
        val stage3 = isFemaleBreastOrCleavageVisible(frame)
        val stage4 = isFemaleBackOrButtocksAssVisible(frame, gluteMotionDelta)

        if (!(stage2 || stage3 || stage4)) {
            consecutivePositiveFrames = 0
            return false
        }

        consecutivePositiveFrames++
        Log.d(TAG, "4-Stage positive frame ($consecutivePositiveFrames/$REQUIRED_POSITIVE_FRAMES) " +
            "dress=$stage2 bust=$stage3 back=$stage4")
        if (consecutivePositiveFrames >= REQUIRED_POSITIVE_FRAMES) {
            consecutivePositiveFrames = 0
            return true
        }
        return false
    }

    /** Android SDK face detector on an RGB_565 copy; coordinates mapped to analyzer scale. */
    private fun detectFaces(source: Bitmap): List<DetectedFace> {
        var argb: Bitmap? = null
        var rgb565: Bitmap? = null
        return try {
            val scaledFace = Bitmap.createScaledBitmap(source, FACE_W, FACE_H, true)
            argb = scaledFace
            val face565 = scaledFace.copy(Bitmap.Config.RGB_565, false) ?: return emptyList()
            rgb565 = face565
            val detector = faceDetector ?: FaceDetector(FACE_W, FACE_H, MAX_FACES).also { faceDetector = it }
            val found = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val count = detector.findFaces(face565, found)
            val sx = ANALYZER_W.toFloat() / FACE_W
            val sy = ANALYZER_H.toFloat() / FACE_H
            val mid = PointF()
            val result = ArrayList<DetectedFace>(maxOf(0, count))
            for (i in 0 until count) {
                val face = found[i] ?: continue
                if (face.confidence() < FaceDetector.Face.CONFIDENCE_THRESHOLD) continue
                face.getMidPoint(mid)
                result.add(DetectedFace(mid.x * sx, mid.y * sy, face.eyesDistance() * sx))
            }
            result
        } catch (e: Exception) {
            emptyList()
        } finally {
            val a = argb
            if (a != null && a != source) a.recycle()
            rgb565?.recycle()
        }
    }

    private fun buildFrameAnalysis(bmp: Bitmap, faces: List<DetectedFace>): FrameAnalysis {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // Mean background colour from the outer border (left/right 10%, top 8%).
        val bxL = (w * 0.10f).toInt()
        val bxR = (w * 0.90f).toInt()
        val byT = (h * 0.08f).toInt()
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var n = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (x < bxL || x >= bxR || y < byT) {
                    val p = px[row + x]
                    sr += Color.red(p)
                    sg += Color.green(p)
                    sb += Color.blue(p)
                    n++
                }
            }
        }
        val mr = (sr / maxOf(1, n)).toInt()
        val mg = (sg / maxOf(1, n)).toInt()
        val mb = (sb / maxOf(1, n)).toInt()

        val skin = BooleanArray(px.size)
        val hair = BooleanArray(px.size)
        val subject = BooleanArray(px.size)
        val lum = FloatArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val isSkin = isSkinPixel(p)
            val isHair = !isSkin && isHairPixel(r, g, b)
            val bgDist = Math.abs(r - mr) + Math.abs(g - mg) + Math.abs(b - mb)
            skin[i] = isSkin
            hair[i] = isHair
            subject[i] = isSkin || isHair || bgDist > 90
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return FrameAnalysis(w, h, skin, hair, subject, lum, faces)
    }

    /**
     * Calibrated Skin Pixel Classifier (Combined RGB + YCbCr).
     * Also used by the browser explicit-video frame check.
     */
    private fun isSkinPixel(p: Int): Boolean {
        val r = Color.red(p)
        val g = Color.green(p)
        val b = Color.blue(p)
        val y = 0.299 * r + 0.587 * g + 0.114 * b
        val cb = 128.0 - 0.1687 * r - 0.3313 * g + 0.5 * b
        val cr = 128.0 + 0.5 * r - 0.4187 * g - 0.0813 * b
        return (r > 80 && g > 45 && b > 30 && r > g && r > b &&
                (r - minOf(g, b)) > 12 &&
                cb in 80.0..124.0 && cr in 135.0..172.0 && y in 60.0..240.0)
    }

    /** Dark / brown low-saturation pixels typical of hair (pure-black letterbox bars excluded). */
    private fun isHairPixel(r: Int, g: Int, b: Int): Boolean {
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val spread = maxOf(r, maxOf(g, b)) - minOf(r, minOf(g, b))
        return y >= 14f && y <= 85f && spread < 70
    }

    // ---- Zone measurement helpers (fractions of frame width/height) ----

    private fun zoneRatio(map: BooleanArray, f: FrameAnalysis, x0: Float, x1: Float, y0: Float, y1: Float): Float =
        zoneRatioPx(map, f, (x0 * f.w).toInt(), (x1 * f.w).toInt(), (y0 * f.h).toInt(), (y1 * f.h).toInt())

    private fun zoneRatioPx(map: BooleanArray, f: FrameAnalysis, x0: Int, x1: Int, y0: Int, y1: Int): Float {
        val ax = x0.coerceIn(0, f.w - 1)
        val bx = x1.coerceIn(ax + 1, f.w)
        val ay = y0.coerceIn(0, f.h - 1)
        val by = y1.coerceIn(ay + 1, f.h)
        var hit = 0
        var total = 0
        for (y in ay until by) {
            val row = y * f.w
            for (x in ax until bx) {
                total++
                if (map[row + x]) hit++
            }
        }
        return hit.toFloat() / maxOf(1, total)
    }

    private fun zoneLumPx(f: FrameAnalysis, x0: Int, x1: Int, y0: Int, y1: Int): Float {
        val ax = x0.coerceIn(0, f.w - 1)
        val bx = x1.coerceIn(ax + 1, f.w)
        val ay = y0.coerceIn(0, f.h - 1)
        val by = y1.coerceIn(ay + 1, f.h)
        var sum = 0f
        var total = 0
        for (y in ay until by) {
            val row = y * f.w
            for (x in ax until bx) {
                sum += f.lum[row + x]
                total++
            }
        }
        return sum / maxOf(1, total)
    }

    private fun zoneLum(f: FrameAnalysis, x0: Float, x1: Float, y0: Float, y1: Float): Float =
        zoneLumPx(f, (x0 * f.w).toInt(), (x1 * f.w).toInt(), (y0 * f.h).toInt(), (y1 * f.h).toInt())

    /** Garment fabric = foreground subject pixels that are not bare skin. */
    private fun zoneFabric(f: FrameAnalysis, x0: Float, x1: Float, y0: Float, y1: Float): Float {
        val ax = (x0 * f.w).toInt().coerceIn(0, f.w - 1)
        val bx = (x1 * f.w).toInt().coerceIn(ax + 1, f.w)
        val ay = (y0 * f.h).toInt().coerceIn(0, f.h - 1)
        val by = (y1 * f.h).toInt().coerceIn(ay + 1, f.h)
        var hit = 0
        var total = 0
        for (y in ay until by) {
            val row = y * f.w
            for (x in ax until bx) {
                total++
                if (f.subject[row + x] && !f.skin[row + x]) hit++
            }
        }
        return hit.toFloat() / maxOf(1, total)
    }

    /** Average foreground-subject width (fraction of frame) across a horizontal band. */
    private fun subjectBandWidth(f: FrameAnalysis, y0: Float, y1: Float): Float =
        zoneRatio(f.subject, f, 0.10f, 0.90f, y0, y1) * 0.80f

    private fun outerBorderSkinRatio(f: FrameAnalysis): Float {
        val bxL = (f.w * 0.10f).toInt()
        val bxR = (f.w * 0.90f).toInt()
        val byT = (f.h * 0.08f).toInt()
        var hit = 0
        var total = 0
        for (y in 0 until f.h) {
            val row = y * f.w
            for (x in 0 until f.w) {
                if (x < bxL || x >= bxR || y < byT) {
                    total++
                    if (f.skin[row + x]) hit++
                }
            }
        }
        return hit.toFloat() / maxOf(1, total)
    }

    /** First row (fraction of height) below the waist where bare legs begin; 1f if none. */
    private fun findHemlineY(f: FrameAnalysis): Float {
        val startY = (f.h * 0.53f).toInt()
        val endY = (f.h * 0.92f).toInt()
        val x0 = (f.w * 0.24f).toInt()
        val x1 = (f.w * 0.76f).toInt()
        for (y in startY until endY - 3) {
            if (zoneRatioPx(f.skin, f, x0, x1, y, y + 1) >= 0.30f &&
                zoneRatioPx(f.skin, f, x0, x1, y + 1, y + 4) >= 0.25f
            ) {
                return y.toFloat() / f.h
            }
        }
        return 1f
    }

    private fun computeGluteMotionDelta(f: FrameAnalysis): Float {
        val prev = previousSkinGrid ?: return 0f
        if (prev.size != f.skin.size) return 0f
        val ax = (f.w * 0.25f).toInt()
        val bx = (f.w * 0.75f).toInt()
        val ay = (f.h * 0.54f).toInt()
        val by = (f.h * 0.84f).toInt()
        var diff = 0
        var total = 0
        for (y in ay until by) {
            val row = y * f.w
            for (x in ax until bx) {
                total++
                if (prev[row + x] != f.skin[row + x]) diff++
            }
        }
        return diff.toFloat() / maxOf(1, total)
    }

    /** Dark beard/stubble band on chin vs. bare cheeks (face coords in analyzer pixels). */
    private fun hasBeardBand(f: FrameAnalysis, face: DetectedFace): Boolean {
        val ed = maxOf(2f, face.eyeDist)
        val mx = face.x
        val my = face.y
        val chinX0 = (mx - 0.7f * ed).toInt()
        val chinX1 = (mx + 0.7f * ed).toInt()
        val chinY0 = (my + 0.9f * ed).toInt()
        val chinY1 = (my + 1.6f * ed).toInt()
        val cheekY0 = (my + 0.2f * ed).toInt()
        val cheekY1 = (my + 0.7f * ed).toInt()
        val lCheekX0 = (mx - 1.0f * ed).toInt()
        val lCheekX1 = (mx - 0.4f * ed).toInt()
        val rCheekX0 = (mx + 0.4f * ed).toInt()
        val rCheekX1 = (mx + 1.0f * ed).toInt()

        val cheekSkin = (zoneRatioPx(f.skin, f, lCheekX0, lCheekX1, cheekY0, cheekY1) +
            zoneRatioPx(f.skin, f, rCheekX0, rCheekX1, cheekY0, cheekY1)) / 2f
        if (cheekSkin < 0.35f) return false
        val cheekLum = (zoneLumPx(f, lCheekX0, lCheekX1, cheekY0, cheekY1) +
            zoneLumPx(f, rCheekX0, rCheekX1, cheekY0, cheekY1)) / 2f
        val chinSkin = zoneRatioPx(f.skin, f, chinX0, chinX1, chinY0, chinY1)
        val chinLum = zoneLumPx(f, chinX0, chinX1, chinY0, chinY1)
        return chinLum < cheekLum - 28f && chinSkin < cheekSkin - 0.25f
    }

    /** Hair on top of the head AND hair falling past the neck towards the shoulders. */
    private fun hasLongHairFraming(f: FrameAnalysis, face: DetectedFace): Boolean {
        val ed = maxOf(2f, face.eyeDist)
        val mx = face.x
        val my = face.y
        val topHair = zoneRatioPx(
            f.hair, f,
            (mx - ed).toInt(), (mx + ed).toInt(), (my - 1.6f * ed).toInt(), (my - 0.6f * ed).toInt()
        )
        val y0 = maxOf(my + 0.8f * ed, f.h * 0.22f).toInt()
        val y1 = maxOf(f.h * 0.42f, my + 3.0f * ed).toInt()
        val leftHair = zoneRatioPx(f.hair, f, (mx - 2.0f * ed).toInt(), (mx - 0.8f * ed).toInt(), y0, y1)
        val rightHair = zoneRatioPx(f.hair, f, (mx + 0.8f * ed).toInt(), (mx + 2.0f * ed).toInt(), y0, y1)
        return topHair >= 0.25f && (leftHair >= 0.30f || rightHair >= 0.30f) && (leftHair + rightHair) / 2f >= 0.22f
    }

    /**
     * STAGE 1: Female Presence Gate. Returns false (NEVER skip) for walls, objects,
     * text screens, male / bearded talking heads, or frames with no confirmed female.
     */
    private fun isFemaleHumanPresentInFrame(f: FrameAnalysis): Boolean {
        // 1. Non-human background / warm wall / full-screen lighting rejection
        val border = outerBorderSkinRatio(f)
        val totalSkin = zoneRatio(f.skin, f, 0f, 1f, 0f, 1f)
        if (border >= 0.16f || totalSkin > 0.52f || totalSkin < 0.05f) return false

        val chestSkin = zoneRatio(f.skin, f, 0.18f, 0.82f, 0.36f, 0.55f)
        val legSkin = zoneRatio(f.skin, f, 0.18f, 0.82f, 0.68f, 0.90f)
        val waistW = subjectBandWidth(f, 0.52f, 0.62f)
        val hipW = subjectBandWidth(f, 0.64f, 0.78f)
        val curvedHip = waistW > 0.05f && hipW > waistW * 1.12f

        if (f.faces.isNotEmpty()) {
            val upperFaces = f.faces.filter { it.y >= f.h * 0.10f && it.y <= f.h * 0.36f }
            // A face outside the upper body-pose zone = close-up / talking head -> do not skip.
            if (upperFaces.isEmpty()) return false
            val face = upperFaces.maxByOrNull { it.eyeDist } ?: return false
            val beard = hasBeardBand(f, face)
            val longHair = hasLongHairFraming(f, face)

            // Check A: male / bearded / normally clothed talking head
            if (beard && !longHair && chestSkin < 0.15f && legSkin < 0.15f) return false

            // Check B (front/side): long hair, or beardless face with a curved female silhouette
            return longHair || (!beard && curvedHip)
        }

        // Check B (close-up / rear body, no face visible)
        val boundedTorso = border < 0.12f && zoneRatio(f.skin, f, 0.22f, 0.78f, 0.28f, 0.88f) >= 0.22f
        val chestL = zoneRatio(f.skin, f, 0.28f, 0.46f, 0.35f, 0.56f)
        val chestR = zoneRatio(f.skin, f, 0.54f, 0.72f, 0.35f, 0.56f)
        val gluteL = zoneRatio(f.skin, f, 0.28f, 0.47f, 0.58f, 0.84f)
        val gluteR = zoneRatio(f.skin, f, 0.53f, 0.72f, 0.58f, 0.84f)
        val symChest = chestL >= 0.30f && chestR >= 0.30f && Math.abs(chestL - chestR) < 0.15f
        val symGlute = gluteL >= 0.30f && gluteR >= 0.30f && Math.abs(gluteL - gluteR) < 0.15f
        return boundedTorso && (curvedHip || symChest || symGlute)
    }

    /** STAGE 2: Dress starts below the shoulders AND ends above the knees with bare legs/feet. */
    private fun isWearingShortRevealingDress(f: FrameAnalysis): Boolean {
        val shoulderBare = zoneRatio(f.skin, f, 0.24f, 0.76f, 0.26f, 0.37f)
        val bustBare = zoneRatio(f.skin, f, 0.24f, 0.76f, 0.37f, 0.53f)
        val bustFabric = zoneFabric(f, 0.24f, 0.76f, 0.37f, 0.53f)
        val waistFabric = zoneFabric(f, 0.24f, 0.76f, 0.53f, 0.67f)
        val upperLegBare = zoneRatio(f.skin, f, 0.24f, 0.76f, 0.67f, 0.79f)
        val lowerLegBare = zoneRatio(f.skin, f, 0.24f, 0.76f, 0.79f, 0.92f)

        // Normal modest dress protection
        if (shoulderBare < 0.18f && bustBare < 0.20f && upperLegBare < 0.20f) return false

        val shoulderSeNiche = shoulderBare >= 0.30f
        val ghutneSeUpar = (bustFabric >= 0.30f || waistFabric >= 0.35f) &&
            findHemlineY(f) < 0.70f &&
            upperLegBare >= 0.30f &&
            lowerLegBare >= 0.24f
        return shoulderSeNiche && ghutneSeUpar
    }

    /** STAGE 3: Visible breasts / cleavage (partial, half or more) framed by dress fabric. */
    private fun isFemaleBreastOrCleavageVisible(f: FrameAnalysis): Boolean {
        // If a face/chin reaches into the bust box this is a close-up; bust analysis is unreliable.
        if (f.faces.any { it.y + 1.6f * it.eyeDist > f.h * 0.35f }) return false

        val leftBreast = zoneRatio(f.skin, f, 0.28f, 0.46f, 0.35f, 0.56f)
        val rightBreast = zoneRatio(f.skin, f, 0.54f, 0.72f, 0.35f, 0.56f)
        val waistFabric = zoneFabric(f, 0.24f, 0.76f, 0.53f, 0.67f)
        val strapFabric = (zoneFabric(f, 0.22f, 0.28f, 0.35f, 0.56f) + zoneFabric(f, 0.72f, 0.78f, 0.35f, 0.56f)) / 2f

        val check1 = leftBreast >= 0.32f && rightBreast >= 0.32f && (waistFabric >= 0.32f || strapFabric >= 0.35f)
        if (!check1) return false

        val leftLum = zoneLum(f, 0.28f, 0.46f, 0.35f, 0.56f)
        val centerLum = zoneLum(f, 0.46f, 0.54f, 0.35f, 0.56f)
        val rightLum = zoneLum(f, 0.54f, 0.72f, 0.35f, 0.56f)
        val cleftShadow = centerLum < ((leftLum + rightLum) / 2f) - 8f

        val upperChestSkin = zoneRatio(f.skin, f, 0.28f, 0.72f, 0.36f, 0.55f)
        val neckSides = (zoneRatio(f.skin, f, 0.24f, 0.40f, 0.28f, 0.34f) + zoneRatio(f.skin, f, 0.60f, 0.76f, 0.28f, 0.34f)) / 2f
        val dressBelow = zoneFabric(f, 0.28f, 0.72f, 0.55f, 0.68f)
        val boundedContour = upperChestSkin >= 0.36f && neckSides < 0.25f && dressBelow >= 0.30f

        return cleftShadow || boundedContour
    }

    /** STAGE 4: Bare back / exposed hips / buttocks, or curved hips with shaking motion. */
    private fun isFemaleBackOrButtocksAssVisible(f: FrameAnalysis, gluteMotionDelta: Float): Boolean {
        val frontFaceCount = f.faces.size
        val rearHairMass = zoneRatio(f.hair, f, 0.35f, 0.65f, 0.12f, 0.34f) >= 0.45f

        // Case 1: exposed buttocks / micro shorts / bikini / high-cut dress (rear view)
        val leftGlute = zoneRatio(f.skin, f, 0.28f, 0.47f, 0.58f, 0.84f)
        val rightGlute = zoneRatio(f.skin, f, 0.53f, 0.72f, 0.58f, 0.84f)
        if (leftGlute >= 0.34f && rightGlute >= 0.34f && (frontFaceCount == 0 || rearHairMass)) {
            val lLum = zoneLum(f, 0.28f, 0.47f, 0.58f, 0.84f)
            val cLum = zoneLum(f, 0.47f, 0.53f, 0.58f, 0.84f)
            val rLum = zoneLum(f, 0.53f, 0.72f, 0.58f, 0.84f)
            val gluteCleft = cLum < ((lLum + rLum) / 2f) - 8f
            if (gluteCleft || findHemlineY(f) < 0.66f) return true
        }

        // Case 2a: bare back with rear hair above and tight dress/hips below
        val backSkin = zoneRatio(f.skin, f, 0.30f, 0.70f, 0.32f, 0.56f)
        val coveredBelow = zoneFabric(f, 0.25f, 0.75f, 0.56f, 0.84f) >= 0.30f
        if (backSkin >= 0.40f && rearHairMass && frontFaceCount == 0 && coveredBelow) return true

        // Case 2b: curved hip silhouette + shaking/twerking motion (with real skin exposure)
        val waistW = subjectBandWidth(f, 0.52f, 0.62f)
        val hipW = subjectBandWidth(f, 0.64f, 0.78f)
        val hipZoneSkin = zoneRatio(f.skin, f, 0.25f, 0.75f, 0.54f, 0.84f)
        return waistW > 0.05f && hipW > waistW * 1.15f && gluteMotionDelta >= 0.12f && hipZoneSkin >= 0.20f
    }

    /**
     * STAGE 0: Metadata trigger limited to strictly explicit hashtags only.
     * Whole-tag match so e.g. "#nudemakeup" / "#nudelipstick" never trigger.
     */
    private fun hasStrictExplicitTags(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        try {
            val textBuilder = StringBuilder()
            collectNodeTexts(root, textBuilder, depth = 0, maxDepth = 40)
            val match = STRICT_EXPLICIT_TAG_REGEX.find(textBuilder.toString().lowercase(Locale.ROOT))
            if (match != null) {
                Log.i(TAG, "Strict explicit tag detected in UI metadata: '${match.value}'")
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun collectNodeTexts(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int, maxDepth: Int) {
        if (node == null || depth > maxDepth) return
        val t = node.text
        if (!t.isNullOrEmpty()) {
            sb.append(t).append(" ")
        }
        val cd = node.contentDescription
        if (!cd.isNullOrEmpty()) {
            sb.append(cd).append(" ")
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                collectNodeTexts(child, sb, depth + 1, maxDepth)
                try { child.recycle() } catch (e: Exception) {}
            }
        }
    }

    private fun triggerAutoSkipIfCooldownElapsed() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoSkipElapsedMs < 2200L) return
        lastAutoSkipElapsedMs = now

        autoSkipCurrentReelOrVideo()
    }

    private fun autoSkipCurrentReelOrVideo() {
        try {
            val metrics = resources.displayMetrics
            val centerX = metrics.widthPixels * 0.5f
            val startY = metrics.heightPixels * 0.82f
            val endY = metrics.heightPixels * 0.15f
            val path = Path().apply {
                moveTo(centerX, startY)
                lineTo(centerX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 130L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch auto-skip gesture: ${e.message}")
        }

        triggerDoublePulseVibration()

        val todayStr = getTodayDateStr()
        prefs.incrementAutoSkippedToday(todayStr)
        InstaGuardWidgetProvider.updateAllWidgets(applicationContext)

        tickerHandler.post {
            try {
                Toast.makeText(
                    applicationContext,
                    "🛡️ Suggestive Video Detected — Auto-Skipped!",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        if (pkg.contains("launcher") || pkg.contains("home") || pkg.contains("trebuchet") || pkg.contains("quickstep")) {
            return true
        }
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val resolveInfo = packageManager.resolveActivity(intent, 0)
            resolveInfo?.activityInfo?.packageName == pkg
        } catch (e: Exception) {
            false
        }
    }

    // =========================================================================
    // v9.0 FEATURE 3: Browser Monitoring & Explicit Content Enforcement
    // =========================================================================

    private fun checkBrowserForExplicitContent(browserPkg: String, event: AccessibilityEvent? = null) {
        if (!prefs.isAiVisualShieldEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBrowserBlockElapsedMs < 2500L) return

        try {
            val textBuilder = StringBuilder()

            // 1. Gather text from accessibility event if available
            if (event != null) {
                val evText = event.text
                if (!evText.isNullOrEmpty()) {
                    for (cs in evText) {
                        if (!cs.isNullOrEmpty()) textBuilder.append(cs).append(" ")
                    }
                }
                event.source?.let { src ->
                    if (!src.text.isNullOrEmpty()) textBuilder.append(src.text).append(" ")
                    if (!src.contentDescription.isNullOrEmpty()) textBuilder.append(src.contentDescription).append(" ")
                }
            }

            // 2. Gather text and URL bar from rootInActiveWindow
            val root = try { rootInActiveWindow } catch (e: Exception) { null }
            if (root != null) {
                collectBrowserTexts(root, textBuilder, depth = 0, maxDepth = 40)
            }

            val combinedText = textBuilder.toString().lowercase(Locale.ROOT)
            if (combinedText.isNotEmpty()) {
                var matchedKeyword: String? = null
                for (kw in EXPLICIT_BROWSER_KEYWORDS) {
                    if (containsExplicitKeyword(combinedText, kw)) {
                        matchedKeyword = kw
                        break
                    }
                }

                if (matchedKeyword != null) {
                    Log.w(TAG, "Explicit content detected on browser ($browserPkg): '$matchedKeyword'")
                    enforceBrowserBlock(browserPkg, matchedKeyword)
                    return
                }
            }

            // 3. Active Explicit Video Playback Check
            if (root != null && hasActiveVideoPlayer(root)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isScreenshotInProgress) {
                    if (now - lastScreenshotElapsedMs >= 1000L) {
                        captureAndCheckBrowserFrame(browserPkg)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun collectBrowserTexts(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int, maxDepth: Int) {
        if (node == null || depth > maxDepth) return
        val t = node.text
        if (!t.isNullOrEmpty()) {
            sb.append(t).append(" ")
        }
        val cd = node.contentDescription
        if (!cd.isNullOrEmpty()) {
            sb.append(cd).append(" ")
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                collectBrowserTexts(child, sb, depth + 1, maxDepth)
                try { child.recycle() } catch (e: Exception) {}
            }
        }
    }

    private fun containsExplicitKeyword(content: String, kw: String): Boolean {
        return content.contains(kw)
    }

    private fun hasActiveVideoPlayer(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 60) {
            val node = queue.removeFirst()
            count++
            val cls = node.className?.toString()?.lowercase(Locale.ROOT) ?: ""
            if (cls.contains("surfaceview") || cls.contains("textureview") || cls.contains("videoview")) {
                return true
            }
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            if (desc.contains("video player") || desc.contains("html5 video")) {
                return true
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) queue.add(child)
            }
        }
        return false
    }

    private fun captureAndCheckBrowserFrame(browserPkg: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || isScreenshotInProgress) return
        isScreenshotInProgress = true
        lastScreenshotElapsedMs = SystemClock.elapsedRealtime()

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                visualAnalyzerExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val hwBuffer = screenshot.hardwareBuffer
                        try {
                            val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                            val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBuffer.close()
                            hwBitmap?.recycle()
                            if (swBitmap != null) {
                                val scaled = Bitmap.createScaledBitmap(swBitmap, 80, 140, false)
                                if (scaled != swBitmap) swBitmap.recycle()

                                val skinRatio = computeOverallSkinRatio(scaled)
                                scaled.recycle()

                                if (skinRatio >= 0.32f) {
                                    tickerHandler.post {
                                        enforceBrowserBlock(browserPkg, "High Skin Video ($skinRatio)")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            try { hwBuffer.close() } catch (_: Exception) {}
                            e.printStackTrace()
                        } finally {
                            isScreenshotInProgress = false
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        isScreenshotInProgress = false
                    }
                }
            )
        } catch (e: Exception) {
            isScreenshotInProgress = false
            e.printStackTrace()
        }
    }

    private fun computeOverallSkinRatio(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0f

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var skinCount = 0
        val minY = (height * 0.12f).toInt()
        val maxY = (height * 0.88f).toInt()
        var inspectedTotal = 0

        for (y in minY until maxY) {
            val rowOffset = y * width
            for (x in 0 until width) {
                inspectedTotal++
                if (isSkinPixel(pixels[rowOffset + x])) {
                    skinCount++
                }
            }
        }
        return skinCount.toFloat() / maxOf(1, inspectedTotal)
    }

    private fun enforceBrowserBlock(browserPkg: String, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBrowserBlockElapsedMs < 2500L) return
        lastBrowserBlockElapsedMs = now

        // Step 1: Double-Pulse Haptic Vibration
        triggerDoublePulseVibration()

        // Step 2: Immediately Close / Exit Chrome or Google App
        performGlobalAction(GLOBAL_ACTION_BACK)
        tickerHandler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
        }, 80L)

        // Step 3: Increment today's count and record in Blocked Attempts Log
        val todayDateStr = getTodayDateStr()
        prefs.incrementBrowserBlockedToday(todayDateStr)

        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        val currentLog = prefs.getBlockedAttemptsLog(todayDateStr)
        val newEntry = "$timeStr • Blocked NSFW Search/Video on Browser ($reason)"
        val updatedLog = if (currentLog.isEmpty()) newEntry else "$newEntry\n$currentLog"
        prefs.setBlockedAttemptsLog(todayDateStr, updatedLog)
        InstaGuardWidgetProvider.updateAllWidgets(applicationContext)

        // Step 4: High-priority notification + custom AI voice alert
        fireNotificationThenVoice(
            notificationId = 3095,
            title = "🚫 Explicit Content Blocked!",
            message = "Browser closed for your focus and discipline.",
            voiceText = "Explicit content blocked! Browser closed for your focus and discipline."
        )

        tickerHandler.post {
            try {
                Toast.makeText(
                    applicationContext,
                    "🚫 Explicit Search/Video Blocked! Returning Home...",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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

    private fun verifyAndTriggerYoutubeLongVideo() {
        if (currentForegroundAppKey != "YOUTUBE") return
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        if (isYoutubeShortsMode(root)) return

        val isPlayerExpanded = isYoutubeLongVideoPlayerExpanded(root)
        val now = SystemClock.elapsedRealtime()

        if (isPlayerExpanded) {
            isYoutubeWatchPlayerOpen = true
            isYoutubeOnHomeFeed = false

            if (!hasNotifiedForCurrentWatchSession && !isYoutubeLongVideoTimerRunning &&
                (now - lastYoutubeNotificationElapsedMs >= 4000L) &&
                (lastYoutubeHomeClickElapsedMs > 0L && now - lastYoutubeHomeClickElapsedMs < 4000L)
            ) {
                hasNotifiedForCurrentWatchSession = true
                lastYoutubeNotificationElapsedMs = now
                startYoutubeLongVideoCountdown()
            }
        }
    }

    private fun isYoutubeHomePageActive(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        if (isYoutubeShortsMode(root)) return false

        var hasHomeTab = false
        var hasHomeChips = false
        var hasWatchCollapse = false

        val screenHeight = resources.displayMetrics.heightPixels
        val tempRect = Rect()

        scanNodes(root, maxDepth = 12) { node ->
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            val combined = "$text $desc $id"

            if (id.contains("collapse") || id.contains("minimize") ||
                desc == "collapse" || desc == "collapse player" || desc == "minimize"
            ) {
                hasWatchCollapse = true
            }

            if (text == "all" || desc == "all" || combined.contains("chip_cloud") ||
                combined.contains("feed_filter") || text == "new to you" || desc == "new to you"
            ) {
                node.getBoundsInScreen(tempRect)
                if (tempRect.centerY() < screenHeight * 0.25) {
                    hasHomeChips = true
                }
            }

            if ((text == "home" || desc == "home") && (node.isSelected || node.isClickable)) {
                hasHomeTab = true
            }
        }

        if (hasWatchCollapse) return false
        return hasHomeChips || hasHomeTab
    }

    private fun isYoutubeLongVideoPlayerExpanded(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        if (isYoutubeShortsMode(root)) return false

        var hasCollapseOrMinimize = false
        var hasPlayerElement = false
        var hasWatchDetails = false
        var hasHomeChips = false

        val screenHeight = resources.displayMetrics.heightPixels
        val tempRect = Rect()

        scanNodes(root, maxDepth = 14) { node ->
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""
            val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
            val combined = "$text $desc $id"

            // 1. Collapse / Minimize button (top-left of watch player)
            if (id.contains("collapse") || id.contains("minimize") ||
                desc == "collapse" || desc == "collapse player" || desc == "minimize" ||
                desc.contains("collapse")
            ) {
                hasCollapseOrMinimize = true
            }

            // 2. Video Player controls / time bar
            if (id.contains("watch_player") || id.contains("player_view") ||
                id.contains("player_control") || id.contains("time_bar") ||
                desc.contains("enter fullscreen") || desc.contains("seek slider") ||
                desc.contains("fast forward") || desc.contains("rewind 10")
            ) {
                hasPlayerElement = true
            }

            // 3. Watch page engagement elements below player
            if (desc.contains("like this video") || desc.contains("dislike this video") ||
                desc.contains("save to playlist") || combined.contains("save to playlist") ||
                (desc.contains("subscribe") && desc.contains("channel")) ||
                (id.contains("comment") && !combined.contains("post"))
            ) {
                hasWatchDetails = true
            }

            // 4. Home page top category chips (never present on expanded watch player)
            if (text == "all" || desc == "all" || combined.contains("chip_cloud") ||
                combined.contains("feed_filter") || text == "new to you" || desc == "new to you"
            ) {
                node.getBoundsInScreen(tempRect)
                if (tempRect.centerY() < screenHeight * 0.25) {
                    hasHomeChips = true
                }
            }
        }

        if (hasHomeChips && !hasCollapseOrMinimize) {
            return false
        }

        return hasCollapseOrMinimize || (hasPlayerElement && hasWatchDetails) || (hasPlayerElement && !hasHomeChips)
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
        hideDopamineTintOverlay()
        currentForegroundAppKey?.let { saveStateToPrefs(it) }
        currentForegroundAppKey = null
    }

    override fun onDestroy() {
        super.onDestroy()
        activeServiceInstance = null
        try {
            visualAnalyzerExecutor.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isTtsReady = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
        removeFloatingPillCompletely()
        removeDopamineTintOverlayCompletely()
        stopStopwatchTicker()
        currentForegroundAppKey?.let { saveStateToPrefs(it) }
        currentForegroundAppKey = null
        lastValidTrackedAppKey = null
    }

    companion object {
        private const val TAG = "AppBlockerService"
        const val TAMPER_WARNING_MESSAGE =
            "Tamper Protection: Cannot Uninstall or Reset InstaGuard while Focus Timer is active!"

        const val ALERTS_CHANNEL_ID = "instaguard_alerts_channel"
        const val YT_LONG_CHANNEL_ID = "instaguard_yt_longvideo_channel_v63"
        const val VOICE_ALERTS_CHANNEL_ID = "instaguard_voice_alerts_v70"
        const val NOTIF_ID_5MIN = 1000
        const val NOTIF_ID_1MIN = 2000
        const val NOTIF_ID_YOUTUBE_LONG = 3000

        @Volatile
        var activeServiceInstance: AppBlockerService? = null

        @Volatile
        var isOurAppScreenOpen: Boolean = false

        // v9.4 4-Stage AI Vision & Face Detector Constants
        private const val ANALYZER_W = 120
        private const val ANALYZER_H = 214
        private const val FACE_W = 120
        private const val FACE_H = 214
        private const val MAX_FACES = 2
        private const val REQUIRED_POSITIVE_FRAMES = 2
        val STRICT_EXPLICIT_TAG_REGEX = Regex("""(?i)#(nsfw|onlyfans|nude|18plus|uncensored)\b""")

        val APP_KEYS = listOf("INSTAGRAM", "YOUTUBE", "FACEBOOK", "TIKTOK")

        val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.google.android.googlequicksearchbox",
            "com.sec.android.app.sbrowser",
            "org.mozilla.firefox"
        )

        val SUGGESTIVE_REEL_INDICATORS = listOf(
            "cleavage", "bikini", "lingerie", "twerk", "booty", "boobs", "baddie",
            "thirst trap", "thirsttrap", "short dress", "mini dress", "backless",
            "hot girl", "sexy girl", "sexy dress", "sexy video", " sexy ", "#sexy",
            "#hot", "curvy", "thick", "gyatt", "strip", "belly dance", "shaking",
            "onlyfans", "nsfw", "underboob", "sideboob", "bootyshorts", "hot reel", "bold reel"
        )

        val STRICT_NSFW_SOCIAL_TAGS = listOf(
            "#nsfw", "#onlyfans", "#cleavage", "#boobs", "#bigboobs", "#twerk", "#twerking",
            "#bootyshake", "#bigass", "#assshake", "#thirsttrap", "#lingerie", "#bikini",
            "#microbikini", "#stripper", "#striptease", "#sexydress", "#hotcleavage",
            "#backlessdress", "#bootyshorts", "#underboob", "#sideboob", "#18plus", "#uncensored"
        )

        val EXPLICIT_BROWSER_KEYWORDS = arrayOf(
            // Exact user-specified required list:
            "porn video", "xhamster", "sexy video", "sexy female", "female boobs", "female pussy",
            // Plus comprehensive adult keywords & domains:
            "pornhub", "xvideos", "xnxx", "redtube", "youporn", "spankbang", "brazzers",
            "onlyfans", "chaturbate", "stripchat", "sex video", "xxx", "nude", "naked",
            "boobs", "pussy", "tits", "ass video", "twerk video", "hot sexy", "adult video",
            "hentai", "rule34", "erotic", "nsfw", "desipapa", "mms leak", "call girl",
            "camgirl", "bikini hot", "cleavage", "porn"
        )

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

        val warned10MinMap = ConcurrentHashMap<String, Boolean>()
        val warned5MinMap = ConcurrentHashMap<String, Boolean>()
        val warned1MinMap = ConcurrentHashMap<String, Boolean>()

        val customTrackedPackages = ConcurrentHashMap.newKeySet<String>()
        val scrollCountTodayMap = ConcurrentHashMap<String, Int>()
        val scrollCountTotalMap = ConcurrentHashMap<String, Int>()
        val blockedAttemptsTodayMap = ConcurrentHashMap<String, Int>()
        val blockedAttemptsTotalMap = ConcurrentHashMap<String, Int>()

        @Volatile
        var maintenanceUntilMs: Long = 0L

        @Volatile
        var deepWorkUnlockAtMs: Long = 0L

        @Volatile
        var emergencyBypassUntilMs: Long = 0L

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

        @Volatile
        var dopamineTintOverlayView: View? = null

        @Volatile
        var isDopamineTintAttached: Boolean = false

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
            deepWorkUnlockAtMs = prefs.deepWorkUnlockMs
            if (deepWorkUnlockAtMs > 0L && deepWorkUnlockAtMs <= now) {
                deepWorkUnlockAtMs = 0L
                prefs.deepWorkUnlockMs = 0L
            }

            for (key in getAllTrackedKeys()) {
                val usage = prefs.getAccumulatedUsage(key)
                val unlock = prefs.getUnlockTimestamp(key)
                val daily = prefs.getDailyTotal(todayStr, key)
                val scrollToday = prefs.getScrollToday(todayStr, key)
                val scrollTotal = prefs.getScrollTotal(key)
                val blockedToday = prefs.getBlockedAttemptsToday(todayStr, key)
                val blockedTotal = prefs.getBlockedAttemptsTotal(key)

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
                blockedAttemptsTodayMap[key] = blockedToday
                blockedAttemptsTotalMap[key] = blockedTotal
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

        fun getBlockedAttemptsToday(appKey: String, context: Context? = null): Int {
            val inMem = blockedAttemptsTodayMap[appKey]
            if (inMem != null) return inMem
            val ctx = context ?: appContext
            return ctx?.let { GuardPreferences(it).getBlockedAttemptsToday(getTodayDateStr(), appKey) } ?: 0
        }

        fun getBlockedAttemptsTotal(appKey: String, context: Context? = null): Int {
            val inMem = blockedAttemptsTotalMap[appKey]
            if (inMem != null) return inMem
            val ctx = context ?: appContext
            return ctx?.let { GuardPreferences(it).getBlockedAttemptsTotal(appKey) } ?: 0
        }

        fun getTotalBlockedAttemptsToday(context: Context? = null): Int {
            var total = 0
            for (key in getAllTrackedKeys()) {
                total += getBlockedAttemptsToday(key, context)
            }
            return total
        }

        fun getBlockedAttemptsLogToday(context: Context? = null): String {
            val ctx = context ?: appContext ?: return ""
            return GuardPreferences(ctx).getBlockedAttemptsLog(getTodayDateStr())
        }

        fun previewCustomVoice(voiceText: String): Boolean {
            val service = activeServiceInstance ?: return false
            service.speakImmediate(voiceText)
            return true
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
            emergencyBypassUntilMs = System.currentTimeMillis() + emergencyAllowance
            val restoredUsage = maxOf(0L, limitMs - emergencyAllowance)
            accumulatedUsageMap[appKey] = restoredUsage

            prefs.setUnlockTimestamp(appKey, 0L)
            prefs.setAccumulatedUsage(appKey, restoredUsage)
            prefs.setEmergencyUsedDate(appKey, getTodayDateStr())

            // Mark 10-min and 5-min warnings as given so only final 1-min alert triggers
            warned10MinMap[appKey] = true
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
                val windowStart = now - (1_800_000L + 18_000_000L) // Last 5.5 hours (30m limit + 5h cooldown)

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

        fun isNightSleepTimeWindowNow(): Boolean {
            val cal = Calendar.getInstance()
            val mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            return mins >= (23 * 60 + 30) || mins < (6 * 60)
        }

        fun isNightSleepWindowActive(context: Context? = null): Boolean {
            if (System.currentTimeMillis() < emergencyBypassUntilMs) return false
            val ctx = context ?: appContext ?: return false
            val enabled = GuardPreferences(ctx).isNightSleepGuardEnabled
            return enabled && isNightSleepTimeWindowNow()
        }

        fun getNightSleepRemainingMs(context: Context? = null): Long {
            if (!isNightSleepWindowActive(context)) return 0L
            val nowCal = Calendar.getInstance()
            val targetCal = Calendar.getInstance().apply {
                if (nowCal.get(Calendar.HOUR_OF_DAY) >= 23) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                set(Calendar.HOUR_OF_DAY, 6)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return maxOf(0L, targetCal.timeInMillis - nowCal.timeInMillis)
        }

        fun getDeepWorkRemainingMs(context: Context? = null): Long {
            if (System.currentTimeMillis() < emergencyBypassUntilMs) return 0L
            val ctx = context ?: appContext
            if (deepWorkUnlockAtMs == 0L && ctx != null) {
                deepWorkUnlockAtMs = GuardPreferences(ctx).deepWorkUnlockMs
            }
            val now = System.currentTimeMillis()
            val rem = deepWorkUnlockAtMs - now
            if (deepWorkUnlockAtMs > 0L && rem <= 0L) {
                deepWorkUnlockAtMs = 0L
                ctx?.let { GuardPreferences(it).deepWorkUnlockMs = 0L }
                return 0L
            }
            return maxOf(0L, rem)
        }

        fun isDeepWorkModeActive(context: Context? = null): Boolean {
            if (context != null) {
                return getDeepWorkRemainingMs(context) > 0L
            }
            return System.currentTimeMillis() < deepWorkUnlockAtMs || getDeepWorkRemainingMs() > 0L
        }

        fun cancelDeepWorkMode(context: Context) {
            deepWorkUnlockAtMs = 0L
            val prefs = context.getSharedPreferences(GuardPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong("deep_work_unlock_ms", 0L).apply()
            InstaGuardWidgetProvider.updateAllWidgets(context.applicationContext)
        }

        fun activateDeepWorkMode(context: Context, durationMs: Long) {
            val appCtx = context.applicationContext
            appContext = appCtx
            val unlockAt = System.currentTimeMillis() + durationMs
            deepWorkUnlockAtMs = unlockAt
            emergencyBypassUntilMs = 0L
            GuardPreferences(appCtx).deepWorkUnlockMs = unlockAt

            val service = activeServiceInstance
            if (service != null) {
                service.fireNotificationThenVoice(
                    notificationId = 3077,
                    title = "⚡ Deep Work Mode Activated!",
                    message = "All social media apps are now locked for deep focus.",
                    voiceText = "Deep work mode activated! All social media apps are now locked. Good luck with your study session!"
                )
            } else {
                try {
                    val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val channel = NotificationChannel(
                            VOICE_ALERTS_CHANNEL_ID,
                            "InstaGuard Voice & Focus Alerts",
                            NotificationManager.IMPORTANCE_HIGH
                        )
                        nm.createNotificationChannel(channel)
                    }
                    val builder = NotificationCompat.Builder(appCtx, VOICE_ALERTS_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_shield)
                        .setContentTitle("⚡ Deep Work Mode Activated!")
                        .setContentText("All social media apps are now locked for deep focus.")
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setDefaults(NotificationCompat.DEFAULT_ALL)
                        .setAutoCancel(true)
                    nm.notify(3077, builder.build())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            InstaGuardWidgetProvider.updateAllWidgets(appCtx)
        }

        fun getRemainingLockMs(appKey: String, context: Context? = null): Long {
            val unlockAt = unlockTimestampMap[appKey] ?: 0L
            val now = System.currentTimeMillis()
            var perAppCooldownRemaining = unlockAt - now

            if (unlockAt > 0L && perAppCooldownRemaining <= 0L) {
                unlockTimestampMap[appKey] = 0L
                accumulatedUsageMap[appKey] = 0L
                val ctx = context ?: appContext
                ctx?.let { GuardPreferences(it).resetApp(appKey) }
                perAppCooldownRemaining = 0L
            }

            val safePerApp = maxOf(0L, perAppCooldownRemaining)
            val sleepGuardRemaining = getNightSleepRemainingMs(context)
            val deepWorkRemaining = getDeepWorkRemainingMs(context)

            return maxOf(safePerApp, maxOf(sleepGuardRemaining, deepWorkRemaining))
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
