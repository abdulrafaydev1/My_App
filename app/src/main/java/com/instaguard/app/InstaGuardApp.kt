package com.instaguard.app

import android.app.Application
import android.util.Log
import com.instaguard.app.service.AppBlockerService

/**
 * Application class for InstaGuard.
 * Initializes per-app persistent state on launch.
 */
class InstaGuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "InstaGuard application initialized.")
        AppBlockerService.syncFromPrefs(this)
    }

    companion object {
        private const val TAG = "InstaGuardApp"
    }
}
