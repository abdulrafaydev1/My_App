package com.instaguard.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.instaguard.app.service.AppBlockerService
import com.instaguard.app.ui.MainActivity

/**
 * v7.0 Home Screen Dark Glass Widget Provider.
 * Displays live daily scrolls, total screen time used today, and discipline score.
 */
class InstaGuardWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAllWidgets(context)
    }

    companion object {
        fun updateAllWidgets(context: Context) {
            try {
                val appCtx = context.applicationContext
                val totalScrolls = AppBlockerService.getTotalScrollsToday(appCtx)

                var totalTodayMs = 0L
                for (key in AppBlockerService.APP_KEYS) {
                    totalTodayMs += AppBlockerService.getLiveDailyTotalMs(key, appCtx)
                }
                val usedMinutes = totalTodayMs / 60_000L

                val lockedCount = AppBlockerService.APP_KEYS.count {
                    AppBlockerService.isAppLocked(it, appCtx)
                }
                val scrollPenalty = if (totalScrolls > 50) (totalScrolls - 50) / 10 else 0
                val disciplineScore = (100 - (lockedCount * 15) - scrollPenalty).coerceIn(10, 100)

                val views = RemoteViews(appCtx.packageName, R.layout.widget_instaguard)
                views.setTextViewText(R.id.tvWidgetScrolls, "🎬 Scrolls: $totalScrolls")
                views.setTextViewText(R.id.tvWidgetTime, "⏱ Used: ${usedMinutes}m")
                views.setTextViewText(R.id.tvWidgetScore, "🏆 Score: $disciplineScore%")

                val statusText = when {
                    AppBlockerService.getDeepWorkRemainingMs(appCtx) > 0L -> "DEEP WORK"
                    AppBlockerService.isNightSleepWindowActive(appCtx) -> "SLEEP GUARD"
                    lockedCount > 0 -> "LOCKED ($lockedCount)"
                    else -> "ACTIVE"
                }
                views.setTextViewText(R.id.tvWidgetStatus, statusText)

                val launchIntent = Intent(appCtx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    appCtx,
                    7001,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widgetRootContainer, pendingIntent)

                val manager = AppWidgetManager.getInstance(appCtx)
                val component = ComponentName(appCtx, InstaGuardWidgetProvider::class.java)
                manager.updateAppWidget(component, views)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
