package com.instaguard.app.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.instaguard.app.databinding.ActivityLockScreenBinding
import com.instaguard.app.model.TrackedApp
import com.instaguard.app.service.AppBlockerService
import com.instaguard.app.util.GuardManager

/**
 * Full-screen, non-bypassable lock activity displayed during an app's 5-hour focus cooldown period.
 * Dynamically adapts to whichever of the 4 social apps is locked (Instagram, YouTube, Facebook, TikTok).
 * v5.0 Master Edition Features:
 * - Feature 3: Emergency 5-Minute Master PIN Bypass (strict 1x per day limit).
 * - Feature 4: Motivational & Focus Quotes Engine with crossfade animations.
 * - Breathing lock icon animation, single-line millisecond countdown, and continuous media suppression.
 */
class LockScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockScreenBinding
    private lateinit var guardManager: GuardManager
    private val timerHandler = Handler(Looper.getMainLooper())

    private var pulseScaleXAnimator: ObjectAnimator? = null
    private var pulseScaleYAnimator: ObjectAnimator? = null

    private var lockedAppKey: String = "INSTAGRAM"
    private var appName: String = "Instagram"
    private var tamperWarningMessage: String? = null
    private var mediaSuppressTick: Int = 0
    private var currentQuoteIndex: Int = 0

    private val motivationalQuotes = listOf(
        "“Discipline is choosing between what you want now and what you want most.”",
        "“Stop scrolling other people's lives. Start building your own empire.”",
        "“1 hour of deep coding beats 5 hours of mindless scrolling.”",
        "“Your future self is watching you right now through your memories.”",
        "“Don't trade your biggest dreams for cheap dopamine.”",
        "“Focus is the new IQ in a distracted world.”",
        "“Every hour saved from social media is an hour invested in your success.”",
        "“Winners use technology as a tool; others let it use them.”",
        "“The cost of a distraction is another day delayed from your destiny.”",
        "“Self-control is strength. Right thought is mastery. Calmness is power.”",
        "“Small daily disciplines compound into massive lifelong triumphs.”",
        "“You cannot change your destination overnight, but you can change your direction today.”",
        "“Do what is hard now and life will be easy later.”",
        "“Your focus determines your reality. Guard it fiercely.”",
        "“Break the habit before the habit breaks your potential.”"
    )

    private val countdownRunnable = object : Runnable {
        override fun run() {
            val remainingMs = AppBlockerService.getRemainingLockMs(lockedAppKey, this@LockScreenActivity)
            if (remainingMs <= 0L) {
                if (!tamperWarningMessage.isNullOrEmpty()) {
                    val activeUsageMs = AppBlockerService.getLiveUsageMs(lockedAppKey, this@LockScreenActivity)
                    binding.tvCountdownTimer.text = guardManager.formatWithMillis(activeUsageMs)
                    binding.tvUnlockTimeEstimate.text = "Active Session Protected — Uninstall/Reset Blocked"
                    timerHandler.postDelayed(this, 50L)
                } else {
                    binding.tvCountdownTimer.text = "00:00:00.000"
                    Toast.makeText(
                        this@LockScreenActivity,
                        "$appName cooldown completed. You may now return.",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            } else {
                binding.tvCountdownTimer.text = guardManager.formatWithMillis(remainingMs)
                mediaSuppressTick++
                if (mediaSuppressTick >= 10) {
                    mediaSuppressTick = 0
                    stopAllMediaPlayback()
                }
                timerHandler.postDelayed(this, 33L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        guardManager = GuardManager(applicationContext)
        AppBlockerService.syncFromPrefs(applicationContext)

        updateLockedAppFromIntent()
        applyMonospaceTypeface()
        configureWindowFlags()
        setupBackPressInterceptor()
        setupListeners()
        setupQuotesEngine()
        startLockIconBreathingAnimation()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        updateLockedAppFromIntent()
    }

    private fun updateLockedAppFromIntent() {
        val currentIntent = intent
        lockedAppKey = currentIntent?.getStringExtra("EXTRA_APP_KEY")
            ?: currentIntent?.getStringExtra(EXTRA_APP_KEY)
            ?: "INSTAGRAM"

        val trackedApp = TrackedApp.fromKey(lockedAppKey)
        appName = currentIntent?.getStringExtra("EXTRA_APP_NAME")
            ?: currentIntent?.getStringExtra(EXTRA_APP_NAME)
            ?: (trackedApp?.displayName ?: lockedAppKey)

        tamperWarningMessage = currentIntent?.getStringExtra(EXTRA_TAMPER_WARNING)

        applyDynamicTexts()

        if (!tamperWarningMessage.isNullOrEmpty()) {
            Toast.makeText(this, tamperWarningMessage, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Force-pause media playback and seize audio focus whenever lock screen is active
        stopAllMediaPlayback()

        val remainingMs = AppBlockerService.getRemainingLockMs(lockedAppKey, this)
        if (remainingMs <= 0L && tamperWarningMessage.isNullOrEmpty()) {
            Toast.makeText(this, "$appName cooldown completed.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val unlockTime = AppBlockerService.unlockTimestampMap[lockedAppKey]
            ?: guardManager.prefs.getUnlockTimestamp(lockedAppKey)
        if (unlockTime > 0L) {
            binding.tvUnlockTimeEstimate.text = "Unlocks at ${guardManager.formatTimeOfDay(unlockTime)}"
        } else if (!tamperWarningMessage.isNullOrEmpty()) {
            binding.tvUnlockTimeEstimate.text = "Active Session Protected — Uninstall/Reset Blocked"
        }

        timerHandler.removeCallbacks(countdownRunnable)
        timerHandler.post(countdownRunnable)
    }

    override fun onPause() {
        super.onPause()
        timerHandler.removeCallbacks(countdownRunnable)
    }

    private fun applyDynamicTexts() {
        val warning = tamperWarningMessage
        if (!warning.isNullOrEmpty()) {
            binding.tvLockBadgeTitle.text = "ANTI-TAMPER PROTECTION SHIELD"
            binding.tvLockTitle.text = "Tamper Blocked!"
            binding.tvLockSubtitle.text = warning
        } else {
            binding.tvLockBadgeTitle.text = "${appName.uppercase()} FOCUS SHIELD"
            binding.tvLockTitle.text = "$appName is Locked!"
            binding.tvLockSubtitle.text = "1-Hour limit reached. Take a 5-Hour focus break."
        }
    }

    private fun applyMonospaceTypeface() {
        binding.tvCountdownTimer.typeface = Typeface.MONOSPACE
    }

    /**
     * Feature 4: Motivational & Focus Quotes Engine with smooth crossfade animation.
     */
    private fun setupQuotesEngine() {
        currentQuoteIndex = (0 until motivationalQuotes.size).random()
        binding.tvMotivationalQuote.text = motivationalQuotes[currentQuoteIndex]
        binding.cardMotivationalQuote.setOnClickListener {
            cycleToNextQuote()
        }
    }

    private fun cycleToNextQuote() {
        currentQuoteIndex = (currentQuoteIndex + 1) % motivationalQuotes.size
        val nextQuote = motivationalQuotes[currentQuoteIndex]

        binding.tvMotivationalQuote.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                binding.tvMotivationalQuote.text = nextQuote
                binding.tvMotivationalQuote.animate()
                    .alpha(1f)
                    .setDuration(220)
                    .start()
            }
            .start()
    }

    /**
     * Breathing scale animation on the lock icon for premium visual feedback.
     */
    private fun startLockIconBreathingAnimation() {
        pulseScaleXAnimator?.cancel()
        pulseScaleYAnimator?.cancel()

        pulseScaleXAnimator = ObjectAnimator.ofFloat(binding.imgLockIcon, View.SCALE_X, 1.0f, 1.14f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }

        pulseScaleYAnimator = ObjectAnimator.ofFloat(binding.imgLockIcon, View.SCALE_Y, 1.0f, 1.14f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun configureWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )
    }

    /**
     * Prevents bypassing the lock screen via system Back button or gestures.
     * Back navigation automatically redirects to the device's Home Screen.
     */
    private fun setupBackPressInterceptor() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                redirectToHomeScreen()
            }
        })
    }

    private fun setupListeners() {
        binding.btnGoHome.setOnClickListener {
            redirectToHomeScreen()
        }

        // Feature 3: Emergency 5-Min Unlock (Strict 1x Per Day)
        binding.btnEmergencyUnlock.setOnClickListener {
            handleEmergencyBypassClick()
        }
    }

    /**
     * Feature 2: 3-Stage Hard Math Brain Challenge + Master PIN (Strict 1x Per Day).
     */
    private fun handleEmergencyBypassClick() {
        val todayStr = AppBlockerService.getTodayDateStr()
        val usedDate = guardManager.prefs.getEmergencyUsedDate(lockedAppKey)

        if (usedDate == todayStr) {
            Toast.makeText(
                this,
                "❌ Emergency 5-Min Bypass already used today for $appName! Wait for cooldown.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        startMathBrainChallenge(step = 1)
    }

    private fun startMathBrainChallenge(step: Int) {
        if (step > 3) {
            // All 3 math steps passed! Now request Master PIN
            showFinalMasterPinDialog()
            return
        }

        // Generate hard arithmetic problem e.g. (17 × 6) + 29
        val num1 = (14..28).random()
        val num2 = (5..9).random()
        val num3 = (20..85).random()
        val isAddition = (0..1).random() == 1

        val problemStr = if (isAddition) "($num1 × $num2) + $num3" else "($num1 × $num2) - $num3"
        val expectedAnswer = if (isAddition) (num1 * num2) + num3 else (num1 * num2) - num3

        val container = FrameLayout(this).apply {
            setPadding(56, 24, 56, 12)
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            hint = "Answer"
            setHintTextColor(Color.parseColor("#64748B"))
        }
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("🧠 Brain Challenge (Step $step of 3)")
            .setMessage("Solve this problem to test your willpower and discipline before unlocking:\n\n$problemStr = ?")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(if (step < 3) "Next Step ➔" else "Proceed to PIN ➔") { dialog, _ ->
                val enteredText = input.text.toString().trim()
                val enteredAnswer = enteredText.toIntOrNull()

                if (enteredAnswer != null && enteredAnswer == expectedAnswer) {
                    dialog.dismiss()
                    Toast.makeText(this, "✅ Problem $step solved correctly!", Toast.LENGTH_SHORT).show()
                    startMathBrainChallenge(step + 1)
                } else {
                    dialog.dismiss()
                    Toast.makeText(
                        this,
                        "❌ Incorrect answer! Generating a new challenge to prevent guessing.",
                        Toast.LENGTH_LONG
                    ).show()
                    startMathBrainChallenge(step = 1) // Reset challenge
                }
            }
            .setNegativeButton("Give Up") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "Good choice! Keep your focus streak alive.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showFinalMasterPinDialog() {
        val container = FrameLayout(this).apply {
            setPadding(56, 24, 56, 12)
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            hint = "••••"
            setHintTextColor(Color.parseColor("#64748B"))
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        container.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle("🔐 Final Step: Master PIN")
            .setMessage("All 3 Math Challenges Passed! Enter your 4-digit Master PIN to unlock 5 minutes of emergency access for $appName (Strict limit: 1x per day).")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Authorize") { dialog, _ ->
                val entered = input.text.toString().trim()
                val master = guardManager.prefs.masterPin
                if (entered == master) {
                    AppBlockerService.grantEmergencyFiveMinutes(applicationContext, lockedAppKey)
                    Toast.makeText(
                        this,
                        "✅ 5-Minute Emergency Access Granted for $appName!",
                        Toast.LENGTH_LONG
                    ).show()
                    dialog.dismiss()
                    finish()
                } else {
                    Toast.makeText(this, "❌ Incorrect Master PIN! Emergency unlock aborted.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun redirectToHomeScreen() {
        stopAllMediaPlayback()
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(homeIntent)
        finish()
    }

    /**
     * Dispatches hardware media pause/stop keys, seizes both transient exclusive and permanent
     * audio focus, and kills YouTube background processes to prevent PiP playback.
     */
    private fun stopAllMediaPlayback() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 1. Dispatch Hardware Media PAUSE and STOP key events to immediately pause YouTube/TikTok video
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP))

            // 2. Seize Exclusive Transient AND Permanent Audio Focus so Android OS forces YouTube to pause playback
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

            if (lockedAppKey == "YOUTUBE" || AppBlockerService.isAppLocked("YOUTUBE", this)) {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses("com.google.android.youtube")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(countdownRunnable)
        pulseScaleXAnimator?.cancel()
        pulseScaleYAnimator?.cancel()
    }

    companion object {
        const val EXTRA_APP_KEY = "extra_app_key"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_TAMPER_WARNING = "extra_tamper_warning"
    }
}
