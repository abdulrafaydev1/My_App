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
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import com.instaguard.app.alarm.AlarmManagerHelper
import com.instaguard.app.alarm.HardAlarmSoundEngine
import com.instaguard.app.alarm.SmartAlarm
import com.instaguard.app.service.AlarmRingingService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Display
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import com.instaguard.app.alarm.AlarmRingingActivity
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

    // v13.1 Anti-Cheat Calculator & Browser Lock State
    private var lastCalculatorBlockToastElapsedMs = 0L
    private var lastCalculatorSearchBlockToastElapsedMs = 0L
    private val MATH_EQUATION_REGEX = Regex("""\b\d+\s*[-+*xX/\u00D7\u00F7]\s*\d+\b""")
    private val CALCULATOR_KEYWORDS = listOf(
        "calculator", "calculater", "calc ", "google calculator",
        "online calculator", "scientific calculator", "math solver", "solve math"
    )

    // v10.0 6-Stage Visual Decision Engine & Tactical HUD Scanner State
    private val visualAnalyzerExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val decisionEngine = VisualFilterDecisionEngine()
    @Volatile
    private var isScreenshotInFlight = false
    @Volatile
    private var lastScreenshotCompletedElapsedMs = 0L
    private var lastAutoSkipElapsedMs = 0L
    private var lastBrowserBlockElapsedMs = 0L
    private var previousLumaGrid: IntArray? = null
    @Volatile
    private var currentScrollSeq = 0L
    @Volatile
    private var currentContentItemId = "init_0"
    @Volatile
    private var consecutiveConfirmedMatchFrames = 0

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

            currentScrollSeq++
            val sigHash = if (currentSig.isNotEmpty()) currentSig.hashCode() else currentScrollSeq.toInt()
            val newItemId = "${activeKey}_${sigHash}_${currentScrollSeq}"
            currentContentItemId = newItemId
            decisionEngine.resetForNewItem(newItemId)
            consecutiveConfirmedMatchFrames = 0
            previousLumaGrid = null

            incrementScrollCount(activeKey)
            pulseFloatingPill(activeKey)
        }
    }

    /**
     * Monotonic 50ms Delta-Tick Stopwatch Ticker.
     * Continuously computes elapsed time delta using SystemClock.elapsedRealtime()
     * and adds delta directly to active app's accumulated usage and daily totals.
     */
    
    private var lastFiredAlarmMinuteKey: String = ""
    private var alarmSchedulerWakeLock: PowerManager.WakeLock? = null
    private var alarmWatchdogExecutor: ScheduledExecutorService? = null

    private fun startAlarmWatchdogExecutor() {
        if (alarmWatchdogExecutor == null || alarmWatchdogExecutor?.isShutdown == true) {
            alarmWatchdogExecutor = Executors.newSingleThreadScheduledExecutor()
            alarmWatchdogExecutor?.scheduleAtFixedRate({
                checkHardwareAlarmWatchdog()
            }, 0L, 1L, TimeUnit.SECONDS)
        }
        updateAlarmSchedulerWakeLock()
    }

    private fun stopAlarmWatchdogExecutor() {
        try {
            alarmWatchdogExecutor?.shutdownNow()
            alarmWatchdogExecutor = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            if (alarmSchedulerWakeLock?.isHeld == true) {
                alarmSchedulerWakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        alarmSchedulerWakeLock = null
    }

    fun updateAlarmSchedulerWakeLock() {
        try {
            val context = applicationContext ?: return
            val enabledAlarms = AlarmManagerHelper.loadAlarms(context).filter { it.enabled }
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return

            if (enabledAlarms.isNotEmpty()) {
                if (alarmSchedulerWakeLock == null || alarmSchedulerWakeLock?.isHeld != true) {
                    alarmSchedulerWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InstaGuard:AlarmCpuWatchdog").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            } else {
                if (alarmSchedulerWakeLock?.isHeld == true) {
                    alarmSchedulerWakeLock?.release()
                }
                alarmSchedulerWakeLock = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating alarmSchedulerWakeLock", e)
        }
    }

    private fun checkHardwareAlarmWatchdog() {
        try {
            if (isAlarmCurrentlyRinging) return
            val context = applicationContext ?: return

            val prefs = context.getSharedPreferences("InstaGuardPrefs", Context.MODE_PRIVATE)
            val nowMs = System.currentTimeMillis()
            val lastSolvedMs = prefs.getLong("last_alarm_solved_timestamp_ms", 0L)
            if (nowMs - lastSolvedMs < 65_000L) {
                // Cooldown: do not fire within 65 seconds after an alarm was solved/dismissed
                return
            }

            val enabledAlarms = AlarmManagerHelper.loadAlarms(context).filter { it.enabled }
            if (enabledAlarms.isEmpty()) {
                if (alarmSchedulerWakeLock?.isHeld == true) {
                    alarmSchedulerWakeLock?.release()
                    alarmSchedulerWakeLock = null
                }
                return
            }

            val nowCal = Calendar.getInstance()
            val currentHour = nowCal.get(Calendar.HOUR_OF_DAY)
            val currentMinute = nowCal.get(Calendar.MINUTE)
            val currentSecond = nowCal.get(Calendar.SECOND)
            val currentDayOfWeek = when (nowCal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> 1
                Calendar.TUESDAY -> 2
                Calendar.WEDNESDAY -> 3
                Calendar.THURSDAY -> 4
                Calendar.FRIDAY -> 5
                Calendar.SATURDAY -> 6
                Calendar.SUNDAY -> 7
                else -> 1
            }

            for (alarm in enabledAlarms) {
                val minuteKey = alarm.id + "_" + nowCal.get(Calendar.YEAR) + "_" + nowCal.get(Calendar.DAY_OF_YEAR) + "_" + alarm.hour + "_" + alarm.minute
                val firedKey = prefs.getString("alarm_last_fired_minute_key_${alarm.id}", "")
                if (firedKey == minuteKey) continue

                val targetEpoch = AlarmManagerHelper.getAlarmNextEpoch(context, alarm.id)
                val matchesEpoch = targetEpoch > 0L && nowMs >= targetEpoch && (nowMs - targetEpoch < 60_000L)
                val matchesDay = alarm.repeatDays.isEmpty() || alarm.repeatDays.contains(currentDayOfWeek)
                val matchesTime = (alarm.hour == currentHour && alarm.minute == currentMinute && currentSecond in 0..10 && matchesDay)

                if (matchesEpoch || matchesTime) {
                    prefs.edit().putString("alarm_last_fired_minute_key_${alarm.id}", minuteKey).commit()
                    lastFiredAlarmMinuteKey = minuteKey

                    Log.i(TAG, "⏰ 1s Real-Time Watchdog: Firing alarm " + alarm.id + " (" + alarm.label + ")")
                    AlarmManagerHelper.clearAlarmNextEpoch(context, alarm.id)

                    Handler(Looper.getMainLooper()).post {
                        triggerAlarmFromAccessibilityService(alarm)
                    }
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkHardwareAlarmWatchdog", e)
        }
    }

    fun triggerAlarmFromAccessibilityService(alarm: SmartAlarm) {
        try {
            Log.i(TAG, "⏰ triggerAlarmFromAccessibilityService: waking screen & blasting audio for " + alarm.id)

            // Step A — Physically Turn On the Closed Display Immediately (5 minutes bright lock)
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            try {
                @Suppress("DEPRECATION")
                val screenWakeLock = pm?.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                    "InstaGuard:ForceScreenOnWakeLock"
                )
                screenWakeLock?.acquire(300_000L)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Step B — Start the 3000Hz Piezo Buzzer & Heavy Vibration IMMEDIATELY
            setAlarmRingingState(this, isRinging = true, alarmId = alarm.id)
            val sp = getSharedPreferences("InstaGuardPrefs", Context.MODE_PRIVATE)
            val nowCal = Calendar.getInstance()
            val minuteKey = alarm.id + "_" + nowCal.get(Calendar.YEAR) + "_" + nowCal.get(Calendar.DAY_OF_YEAR) + "_" + alarm.hour + "_" + alarm.minute
            sp.edit()
                .putString("active_ringing_alarm_id", alarm.id)
                .putBoolean("active_ringing_delete_after", alarm.deleteAfterRinging)
                .putBoolean("alarm_currently_ringing", true)
                .putLong("last_actual_alarm_ring_epoch", System.currentTimeMillis())
                .putString("alarm_last_fired_minute_key_${alarm.id}", minuteKey)
                .commit()

            HardAlarmSoundEngine.startPlaying(applicationContext, alarm.soundId, forceMaxVolume = alarm.forceMaxVolume)

            // Step C — Start dedicated foreground AlarmRingingService
            AlarmRingingService.startAlarm(
                context = this,
                alarmId = alarm.id,
                isTest = false,
                isSnooze = false,
                deleteAfter = alarm.deleteAfterRinging
            )

            // Step D — Launch AlarmRingingActivity Directly From AccessibilityService
            val ringIntent = Intent(this, AlarmRingingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(AlarmManagerHelper.EXTRA_ALARM_ID, alarm.id)
                putExtra(AlarmManagerHelper.EXTRA_IS_TEST, false)
                putExtra(AlarmManagerHelper.EXTRA_IS_SNOOZE, false)
            }
            startActivity(ringIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error in triggerAlarmFromAccessibilityService", e)
        }
    }

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
                checkHardwareAlarmWatchdog()
                tickerHandler.postDelayed(this, 1000L)
                return
            }

            // 2. Active PiP Hunter whenever any app is in 5-hour cooldown lock
            if (tickCounter % 20 == 0) {
                checkHardwareAlarmWatchdog()
            }

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
                // v15.6 Alarm Ringing Immortal Lock & Anti-Cheat Protection
                if (isAlarmCurrentlyRinging) {
                    if (rootPkg != applicationContext.packageName && rootPkg !in EMERGENCY_CALL_PACKAGES) {
                        if (tickCounter % 2 == 0 && isPowerOffMenuOpen(null)) {
                            enforcePowerOffMenuBlock()
                        } else if (isCalculatorAppPackage(rootPkg)) {
                            enforceCalculatorAppBlock(rootPkg)
                        } else if (isLauncherPackage(rootPkg) || rootPkg == "com.android.systemui") {
                            bringAlarmActivityToFront()
                        } else if (isAntiCheatBrowserPackage(rootPkg)) {
                            if (tickCounter % 3 == 0) {
                                val root = try { rootInActiveWindow } catch (e: Exception) { null }
                                if (hasCalculatorOrMathBypassInBrowser(root)) {
                                    enforceBrowserCalculatorBlock()
                                }
                            }
                        }
                    }
                } else if (isAlarmAntiCheatGuardActive(this@AppBlockerService)) {
                    if (isCalculatorAppPackage(rootPkg)) {
                        enforceCalculatorAppBlock(rootPkg)
                    } else if (isAntiCheatBrowserPackage(rootPkg)) {
                        if (tickCounter % 6 == 0) {
                            val root = try { rootInActiveWindow } catch (e: Exception) { null }
                            if (hasCalculatorSearchOrWidgetInBrowser(root)) {
                                enforceBrowserCalculatorBlock()
                            }
                        }
                    }
                }
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

            // 4b. Real-Time Visual Scanner & Auto-Skip on active social media apps (every 4 ticks = ~200ms check, 750ms cadence)
            if (activeKey != null && tickCounter % 4 == 0 && prefs.isAiVisualShieldEnabled) {
                val root = try { rootInActiveWindow } catch (e: Exception) { null }
                val isYtHome = activeKey == "YOUTUBE" && isYoutubeHomePageActive(root)
                if (!isYtHome && hasVideoElementOnScreen(root)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isScreenshotInFlight) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastScreenshotCompletedElapsedMs >= 750L) {
                            captureAndAnalyzeSocialFrame(activeKey)
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
        // Clear stale alarm ringing state on service start if not actively ringing
        if (!isAlarmCurrentlyRinging) {
            clearAlarmRingingState(applicationContext)
        }

        checkMidnightCrossover()
        startAlarmWatchdogExecutor()
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
        // Clear stale alarm ringing state on service start if not actively ringing
        if (!isAlarmCurrentlyRinging) {
            clearAlarmRingingState(applicationContext)
        }

        checkMidnightCrossover()
        startAlarmWatchdogExecutor()
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

        // v15.6 Alarm Persistent Lock & Anti-Cheat Guard while Alarm is Ringing
        if (isAlarmCurrentlyRinging) {
            // NEVER fire BACK or HOME when inside our own app package!
            if (packageName == applicationContext.packageName) {
                return
            }

            // System emergency call screens and SOS must NEVER be blocked or interrupted
            if (packageName in EMERGENCY_CALL_PACKAGES) {
                return
            }

            // 1. Power-Off / Restart Menu Block while alarm is ringing
            if (isPowerOffMenuOpen(event)) {
                enforcePowerOffMenuBlock()
                return
            }

            // 2. Calculator Lock while ringing
            if (isCalculatorAppPackage(packageName)) {
                enforceCalculatorAppBlock(packageName)
                return
            }

            // 3. Browser Calculator Search Blocker while ringing
            if (isAntiCheatBrowserPackage(packageName)) {
                val root = try { rootInActiveWindow } catch (e: Exception) { null }
                if (hasCalculatorOrMathBypassInBrowser(root)) {
                    enforceBrowserCalculatorBlock()
                    return
                }
            }

            // 4. If user opens Recents or returns to Home launcher, bring Alarm screen back immediately!
            if (isLauncherPackage(packageName) || packageName == "com.android.systemui") {
                bringAlarmActivityToFront()
                return
            }
        } else if (isAlarmAntiCheatGuardActive(this)) {
            // Post-alarm 3-minute anti-cheat window
            if (isCalculatorAppPackage(packageName)) {
                enforceCalculatorAppBlock(packageName)
                return
            }
            if (isAntiCheatBrowserPackage(packageName)) {
                val root = try { rootInActiveWindow } catch (e: Exception) { null }
                if (hasCalculatorSearchOrWidgetInBrowser(root)) {
                    enforceBrowserCalculatorBlock()
                    return
                }
            }
        }

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

        // v9.3 Step 3: 250ms Post-Scroll Settled Verification Engine + v10.0 Instant HUD Reset
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val activeKey = currentForegroundAppKey ?: return
            val now = SystemClock.elapsedRealtime()

            // Immediately clear HUD boxes and reset item state when user scrolls
            currentScrollSeq++
            val scrollItemId = "${activeKey}_scroll_${currentScrollSeq}_${event.fromIndex}_${event.toIndex}"
            currentContentItemId = scrollItemId
            decisionEngine.resetForNewItem(scrollItemId)
            consecutiveConfirmedMatchFrames = 0
            previousLumaGrid = null

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
    // v11.0 ML Kit 33-Landmark & TFLite Segmentation Locked Visual Auto-Skip Engine
    // =========================================================================

    private fun captureAndAnalyzeSocialFrame(appKey: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || isScreenshotInFlight) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastScreenshotCompletedElapsedMs < 750L) return

        isScreenshotInFlight = true
        currentScannerDiagnosticText = DiagnosticStatus.CAPTURING_FRAME.label
        val itemIdSnapshot = currentContentItemId
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                visualAnalyzerExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        currentScannerDiagnosticText = DiagnosticStatus.FRAME_RECEIVED.label
                        val hwBuffer = screenshot.hardwareBuffer
                        try {
                            val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                            val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBuffer.close()
                            hwBitmap?.recycle()
                            if (swBitmap != null) {
                                // Downscale to 480p width for fast, reliable ML Kit Pose + TFLite Segmentation inference
                                val targetW = 480
                                val targetH = ((swBitmap.height.toFloat() / swBitmap.width) * targetW).toInt().coerceAtLeast(1)
                                val scaledBitmap = Bitmap.createScaledBitmap(swBitmap, targetW, targetH, true)
                                swBitmap.recycle()

                                val frameFeatures = MlKitPoseInferenceProvider.analyzeBitmap(
                                    bitmap = scaledBitmap,
                                    contentItemId = itemIdSnapshot,
                                    timestampMs = now,
                                    isSupportedFeed = true
                                )
                                scaledBitmap.recycle()

                                val decision = decisionEngine.evaluateFrame(frameFeatures, nowMs = now)

                                if (decision.state == DecisionState.SKIP_CANDIDATE) {
                                    currentScannerDiagnosticText = DiagnosticStatus.MATCH_CONFIRMED_SKIP.label
                                    tickerHandler.post {
                                        if (currentContentItemId == itemIdSnapshot &&
                                            currentForegroundAppKey == appKey &&
                                            !isOurAppScreenOpen &&
                                            prefs.isVisualAutoSkipEnabled
                                        ) {
                                            val skipNow = SystemClock.elapsedRealtime()
                                            if (skipNow - lastAutoSkipElapsedMs >= 1600L) {
                                                lastAutoSkipElapsedMs = skipNow
                                                decisionEngine.markSkipExecuted(itemIdSnapshot, skipNow)
                                                Log.i(TAG, "v11.0 ML Kit Locked Visual Auto-Skip Triggered: rules=${decision.matchedRules}")
                                                autoSkipCurrentReelOrVideo()
                                                currentScrollSeq++
                                                val nextId = "${appKey}_skipped_${currentScrollSeq}"
                                                currentContentItemId = nextId
                                                decisionEngine.resetForNewItem(nextId)
                                                consecutiveConfirmedMatchFrames = 0
                                                previousLumaGrid = null
                                            }
                                        }
                                    }
                                } else {
                                    currentScannerDiagnosticText = decision.diagnosticStatus.label
                                }
                            }
                        } catch (e: Exception) {
                            try { hwBuffer.close() } catch (_: Exception) {}
                            currentScannerDiagnosticText = DiagnosticStatus.ANALYSIS_FAILED.label
                            e.printStackTrace()
                        } finally {
                            lastScreenshotCompletedElapsedMs = SystemClock.elapsedRealtime()
                            isScreenshotInFlight = false
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        lastScreenshotCompletedElapsedMs = SystemClock.elapsedRealtime()
                        isScreenshotInFlight = false
                        currentScannerDiagnosticText = DiagnosticStatus.WAITING_FOR_FRAMES.label
                        Log.d(TAG, "takeScreenshot failed with errorCode=$errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            lastScreenshotCompletedElapsedMs = SystemClock.elapsedRealtime()
            isScreenshotInFlight = false
            currentScannerDiagnosticText = DiagnosticStatus.ANALYSIS_FAILED.label
            e.printStackTrace()
        }
    }

    private fun dispatchSwipeUpGestureOnly() {
        try {
            val metrics = resources.displayMetrics
            val centerX = metrics.widthPixels * 0.5f
            val startY = metrics.heightPixels * 0.82f
            val endY = metrics.heightPixels * 0.16f
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
    }

    private fun autoSkipCurrentReelOrVideo() {
        dispatchSwipeUpGestureOnly()

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

    // v15.6 Anti-Cheat & Immortal Alarm Enforcement Methods
    private var lastAlarmActivityBringToFrontElapsedMs = 0L
    private var lastAlarmCalculatorToastElapsedMs = 0L
    private var lastBrowserCalculatorToastElapsedMs = 0L
    private var lastPowerOffBlockElapsedMs = 0L

    private val POWER_OFF_KEYWORDS = listOf(
        "power off", "shut down", "shutdown", "restart", "reboot",
        "slide to power off", "turn off", "power menu", "tap to power off", "swipe to power off"
    )

    private val CALCULATOR_KEYPAD_BUTTONS = setOf("sin", "cos", "tan", "ac", "=")

    private fun bringAlarmActivityToFront() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAlarmActivityBringToFrontElapsedMs < 300L) return
        lastAlarmActivityBringToFrontElapsedMs = now

        try {
            val alarmIntent = Intent(this, AlarmRingingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(alarmIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun enforceCalculatorAppBlock(pkg: String) {
        if (pkg == applicationContext.packageName) return // NEVER block or back out of our own app!

        val now = SystemClock.elapsedRealtime()
        triggerDoublePulseVibration()
        performGlobalAction(GLOBAL_ACTION_BACK)
        tickerHandler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
        }, 50L)

        if (isAlarmCurrentlyRinging) {
            bringAlarmActivityToFront()
            if (now - lastAlarmCalculatorToastElapsedMs >= 2000L) {
                lastAlarmCalculatorToastElapsedMs = now
                tickerHandler.post {
                    try {
                        Toast.makeText(
                            applicationContext,
                            "🚫 Calculator Locked! Solve all 5 Alarm Questions first!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } else {
            if (now - lastCalculatorBlockToastElapsedMs >= 2000L) {
                lastCalculatorBlockToastElapsedMs = now
                tickerHandler.post {
                    try {
                        Toast.makeText(
                            applicationContext,
                            "🚫 Calculator Locked! Solve the Alarm Challenge without cheating!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun enforceBrowserCalculatorBlock() {
        val now = SystemClock.elapsedRealtime()
        triggerDoublePulseVibration()
        performGlobalAction(GLOBAL_ACTION_BACK)
        tickerHandler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
        }, 50L)

        if (isAlarmCurrentlyRinging) {
            bringAlarmActivityToFront()
            if (now - lastBrowserCalculatorToastElapsedMs >= 2000L) {
                lastBrowserCalculatorToastElapsedMs = now
                tickerHandler.post {
                    try {
                        Toast.makeText(
                            applicationContext,
                            "🚫 Browser Calculator Bypass Blocked! Complete all 5 Alarm Questions!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } else {
            if (now - lastCalculatorSearchBlockToastElapsedMs >= 2000L) {
                lastCalculatorSearchBlockToastElapsedMs = now
                tickerHandler.post {
                    try {
                        Toast.makeText(
                            applicationContext,
                            "🚫 Online Calculator Search Blocked during alarm challenge!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun containsPowerOffKeywords(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        var found = false
        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30 || found) return

            val text = node.text?.toString()?.lowercase(Locale.ROOT)
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT)

            for (kw in POWER_OFF_KEYWORDS) {
                if ((text != null && text.contains(kw)) || (desc != null && desc.contains(kw))) {
                    found = true
                    return
                }
            }

            val count = node.childCount
            for (i in 0 until count) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null) {
                    traverse(child, depth + 1)
                    try { child.recycle() } catch (_: Exception) {}
                    if (found) return
                }
            }
        }
        traverse(root, 0)
        return found
    }

    private fun isPowerOffMenuOpen(event: AccessibilityEvent?): Boolean {
        if (event != null) {
            val cls = event.className?.toString() ?: ""
            if (cls.contains("GlobalActions", ignoreCase = true) ||
                cls.contains("PowerOff", ignoreCase = true) ||
                cls.contains("Shutdown", ignoreCase = true) ||
                cls.contains("PowerMenu", ignoreCase = true)) {
                return true
            }
        }

        val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }
        if (activeRoot != null) {
            val found = containsPowerOffKeywords(activeRoot)
            try { activeRoot.recycle() } catch (_: Exception) {}
            if (found) return true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val winList = windows
                if (winList != null) {
                    for (w in winList) {
                        val root = try { w.root } catch (_: Exception) { null }
                        if (root != null) {
                            val found = containsPowerOffKeywords(root)
                            try { root.recycle() } catch (_: Exception) {}
                            if (found) return true
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun enforcePowerOffMenuBlock() {
        val now = SystemClock.elapsedRealtime()
        triggerDoublePulseVibration()
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        tickerHandler.postDelayed({
            bringAlarmActivityToFront()
        }, 50L)

        if (now - lastPowerOffBlockElapsedMs >= 1500L) {
            lastPowerOffBlockElapsedMs = now
            tickerHandler.post {
                try {
                    Toast.makeText(
                        applicationContext,
                        "🚫 Power Off Blocked! Solve all 5 Questions to stop the alarm!",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun hasCalculatorOrMathBypassInBrowser(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false

        var foundMatch = false
        var hasSin = false
        var hasCos = false
        var hasTan = false
        var hasAC = false

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 45 || foundMatch) return

            val text = node.text?.toString()?.lowercase(Locale.ROOT)
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT)
            val viewId = try { node.viewIdResourceName?.lowercase(Locale.ROOT) } catch (e: Exception) { null }
            val className = node.className?.toString() ?: ""

            // 1. Check viewId for calculator
            if (viewId != null && viewId.contains("calculator")) {
                foundMatch = true
                return
            }

            // 2. Check keywords in text or desc
            if (text != null) {
                for (kw in CALCULATOR_KEYWORDS) {
                    if (text.contains(kw)) {
                        foundMatch = true
                        return
                    }
                }
            }
            if (desc != null) {
                for (kw in CALCULATOR_KEYWORDS) {
                    if (desc.contains(kw)) {
                        foundMatch = true
                        return
                    }
                }
            }

            // 3. Check calculator keypad widget indicators
            val exactLabel = (text ?: desc)?.trim()
            if (exactLabel != null) {
                if (CALCULATOR_KEYPAD_BUTTONS.contains(exactLabel)) {
                    foundMatch = true
                    return
                }
                when (exactLabel) {
                    "sin" -> hasSin = true
                    "cos" -> hasCos = true
                    "tan" -> hasTan = true
                    "ac" -> hasAC = true
                }
                if ((hasSin && hasCos) || (hasSin && hasTan) || (hasAC && (hasSin || hasCos || hasTan))) {
                    foundMatch = true
                    return
                }
            }

            // 4. Check for arithmetic expressions inside EditText or input fields
            val isInput = className.contains("EditText", ignoreCase = true) || node.isEditable
            if (isInput) {
                val inputContent = text ?: desc
                if (inputContent != null && MATH_EQUATION_REGEX.containsMatchIn(inputContent)) {
                    foundMatch = true
                    return
                }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) {
                    traverse(child, depth + 1)
                    try { child.recycle() } catch (e: Exception) {}
                    if (foundMatch) return
                }
            }
        }

        try {
            traverse(root, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return foundMatch
    }

    private fun hasCalculatorSearchOrWidgetInBrowser(root: AccessibilityNodeInfo?): Boolean {
        return hasCalculatorOrMathBypassInBrowser(root)
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
            MlKitPoseInferenceProvider.close()
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
        stopAlarmWatchdogExecutor()
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

        // v13.3 Anti-Cheat Alarm Ringing State
        @Volatile
        var isAlarmCurrentlyRinging: Boolean = false

        @Volatile
        var alarmAntiCheatActiveUntilElapsedMs: Long = 0L

        @Volatile
        var lastActualAlarmRingRealtimeMs: Long = 0L

        val CALCULATOR_PACKAGES = setOf(
            "com.google.android.calculator",
            "com.android.calculator2",
            "com.sec.android.app.popupcalculator",   // Samsung Calculator
            "com.miui.calculator",                   // Xiaomi / Redmi / POCO Calculator
            "com.coloros.calculator",                // OPPO / Realme Calculator
            "com.oplus.calculator",                  // OnePlus / OPPO new Calculator
            "com.oneplus.calculator",                // OnePlus Calculator
            "com.vivo.calculator",                   // Vivo / iQOO Calculator
            "com.transsion.calculator",              // Infinix / Tecno / Itel Calculator
            "com.asus.calculator",                   // ASUS Calculator
            "com.huawei.calculator",                 // Huawei / Honor Calculator
            "com.hihonor.calculator",
            "com.motorola.cn.calculator"
        )

        val ANTI_CHEAT_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.google.android.googlequicksearchbox",
            "com.sec.android.app.sbrowser",
            "org.mozilla.firefox",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.opera.gx",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.duckduckgo.mobile.android"
        )

        fun isCalculatorAppPackage(pkg: String): Boolean {
            val lower = pkg.lowercase(Locale.ROOT)
            return CALCULATOR_PACKAGES.contains(lower) ||
                   lower.contains(".calculator") ||
                   lower.endsWith("calculator") ||
                   lower.contains("popupcalculator")
        }

        fun isAntiCheatBrowserPackage(pkg: String): Boolean {
            val lower = pkg.lowercase(Locale.ROOT)
            return ANTI_CHEAT_BROWSER_PACKAGES.contains(lower) ||
                   BROWSER_PACKAGES.contains(lower) ||
                   lower.contains("browser") ||
                   lower.contains("chrome")
        }

        fun triggerAlarmWakeLockUpdate(context: Context) {
            activeServiceInstance?.updateAlarmSchedulerWakeLock()
        }

        fun clearAlarmRingingState(context: Context) {
            isAlarmCurrentlyRinging = false
            alarmAntiCheatActiveUntilElapsedMs = 0L
            lastActualAlarmRingRealtimeMs = 0L
            try {
                context.applicationContext.getSharedPreferences("InstaGuardPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("alarm_currently_ringing", false)
                    .putLong("alarm_anticheat_until_epoch", 0L)
                    .putLong("last_actual_alarm_ring_epoch", 0L)
                    .putString("active_ringing_alarm_id", "")
                    .remove("active_ringing_delete_after")
                    .commit()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun setAlarmRingingState(context: Context, isRinging: Boolean, alarmId: String? = null) {
            if (!isRinging) {
                clearAlarmRingingState(context)
                return
            }
            isAlarmCurrentlyRinging = true
            lastActualAlarmRingRealtimeMs = SystemClock.elapsedRealtime()
            try {
                val sp = context.applicationContext.getSharedPreferences("InstaGuardPrefs", Context.MODE_PRIVATE)
                sp.edit()
                    .putBoolean("alarm_currently_ringing", true)
                    .putLong("last_actual_alarm_ring_epoch", System.currentTimeMillis())
                    .commit()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun activateAlarmAntiCheatWindow(context: Context, isRingingNow: Boolean, durationMinutesAfterRing: Int = 3) {
            setAlarmRingingState(context, isRingingNow)
        }

        fun deactivateAlarmRinging(context: Context) {
            clearAlarmRingingState(context)
        }

        val EMERGENCY_CALL_PACKAGES = setOf(
            "com.android.incallui",
            "com.google.android.dialer",
            "com.android.server.telecom",
            "com.android.phone",
            "com.android.emergency",
            "com.samsung.android.incallui"
        )

        fun isAlarmAntiCheatGuardActive(context: Context): Boolean {
            return isAlarmCurrentlyRinging
        }

        val STRICT_EXPLICIT_TAG_REGEX = Regex("""(?i)#(nsfw|onlyfans|nude|18plus|uncensored)\b""")

        val APP_KEYS = listOf("INSTAGRAM", "YOUTUBE", "FACEBOOK", "TIKTOK")

        val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.google.android.googlequicksearchbox",
            "com.sec.android.app.sbrowser",
            "org.mozilla.firefox"
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

        @Volatile
        var currentScannerDiagnosticText: String = DiagnosticStatus.WAITING_FOR_FRAMES.label

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
            try {
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
            } catch (e: Exception) {
                Log.e(TAG, "Error in syncFromPrefs: ${e.message}", e)
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
