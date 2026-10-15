package com.instaguard.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.instaguard.app.R
import com.instaguard.app.databinding.ActivityLockScreenBinding
import com.instaguard.app.util.GuardManager

/**
 * Full-screen, non-bypassable lock activity displayed during the focus cooldown period.
 * Ticks smoothly at 33ms (~30 FPS) displaying live countdown with millisecond precision.
 */
class LockScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockScreenBinding
    private lateinit var guardManager: GuardManager
    private val timerHandler = Handler(Looper.getMainLooper())

    private val countdownRunnable = object : Runnable {
        override fun run() {
            val remainingMs = guardManager.getRemainingCooldownMs()
            if (remainingMs <= 0L) {
                binding.tvCountdownTimer.text = "00:00:00.000"
                guardManager.resetSession()
                Toast.makeText(this@LockScreenActivity, R.string.lock_cooldown_finished, Toast.LENGTH_LONG).show()
                finish()
            } else {
                binding.tvCountdownTimer.text = guardManager.formatWithMillis(remainingMs)
                timerHandler.postDelayed(this, 33L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        guardManager = GuardManager(applicationContext)

        binding.tvCountdownTimer.typeface = Typeface.MONOSPACE

        configureWindowFlags()
        setupBackPressInterceptor()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        if (!guardManager.isCooldownActive()) {
            Toast.makeText(this, R.string.lock_cooldown_finished, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val unlockTime = guardManager.prefs.unlockTimestampMs
        binding.tvUnlockTimeEstimate.text = "Unlocks at ${guardManager.formatTimeOfDay(unlockTime)}"

        timerHandler.removeCallbacks(countdownRunnable)
        timerHandler.post(countdownRunnable)
    }

    override fun onPause() {
        super.onPause()
        timerHandler.removeCallbacks(countdownRunnable)
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Prevents bypassing the lock screen via the system Back button or gesture.
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
    }

    /**
     * Sends the user to the device's default Home launcher.
     */
    private fun redirectToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(homeIntent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(countdownRunnable)
    }
}
