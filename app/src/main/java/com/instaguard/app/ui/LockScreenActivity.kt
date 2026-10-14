package com.instaguard.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.instaguard.app.R
import com.instaguard.app.data.GuardPreferences
import com.instaguard.app.databinding.ActivityLockScreenBinding
import com.instaguard.app.util.GuardManager

/**
 * Full-screen, non-bypassable lock activity displayed when Instagram's
 * 1-hour usage limit has been reached and the 3-hour cooldown is active.
 */
class LockScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockScreenBinding
    private lateinit var guardManager: GuardManager
    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        guardManager = GuardManager(applicationContext)

        configureWindowFlags()
        setupBackPressInterceptor()
        setupListeners()
        startCountdown()
    }

    override fun onResume() {
        super.onResume()
        if (!guardManager.isCooldownActive()) {
            // Cooldown already expired: dismiss lock screen
            Toast.makeText(this, R.string.lock_cooldown_finished, Toast.LENGTH_SHORT).show()
            finish()
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

    /**
     * Launches a live 1-second countdown ticker for the remaining 3-hour cooldown duration.
     */
    private fun startCountdown() {
        countDownTimer?.cancel()

        val remainingMs = guardManager.getRemainingCooldownMs()
        val prefs = GuardPreferences(applicationContext)
        val unlockTime = prefs.unlockTimestamp

        if (remainingMs <= 0L) {
            binding.tvCountdownTimer.text = "00:00:00"
            finish()
            return
        }

        binding.tvUnlockTimeEstimate.text = getString(
            R.string.status_unlocked
        ).let { "Unlocks at ${guardManager.formatTimeOfDay(unlockTime)}" }

        countDownTimer = object : CountDownTimer(remainingMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                binding.tvCountdownTimer.text = guardManager.formatCountdown(millisUntilFinished)
            }

            override fun onFinish() {
                binding.tvCountdownTimer.text = "00:00:00"
                guardManager.resetSession()
                Toast.makeText(this@LockScreenActivity, R.string.lock_cooldown_finished, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
