package com.instaguard.app

import android.app.Application
import android.util.Log
import com.instaguard.app.util.GuardManager

/**
 * Application class for InstaGuard.
 * Initializes core components and checks session state at launch.
 */
class InstaGuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "InstaGuard application initialized.")
        val guardManager = GuardManager(this)
        // Check and evaluate cooldown state on startup
        if (guardManager.isCooldownActive()) {
            Log.i(TAG, "Device started with active cooldown: ${guardManager.getRemainingCooldownMs()} ms remaining.")
        }
    }

    companion object {
        private const val TAG = "InstaGuardApp"
    }
}
