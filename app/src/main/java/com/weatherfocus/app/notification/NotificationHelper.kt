package com.weatherfocus.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.weatherfocus.app.MainActivity
import com.weatherfocus.app.R
import com.weatherfocus.app.data.model.CustomAlertMatch
import com.weatherfocus.app.data.model.DoNotDisturbSettings
import java.util.Calendar

object NotificationHelper {
    private const val CHANNEL_ID = "weather_alerts_v2"
    private const val OLD_CHANNEL_ID = "weather_alerts"
    private const val NOTIFICATION_ID_BASE = 1000

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Weather alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Custom weather alert rules you configured in Settings"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            // The first version's channel may have been muted/lowered by the user or the OS; start from a fresh one.
            nm?.deleteNotificationChannel(OLD_CHANNEL_ID)
            nm?.createNotificationChannel(channel)
        }
    }

    /** Returns true if the given Do-Not-Disturb window is currently active and notifications should be suppressed. */
    fun isQuietHoursActive(dnd: DoNotDisturbSettings, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!dnd.enabled) return false
        val hour = Calendar.getInstance().apply { timeInMillis = nowMillis }.get(Calendar.HOUR_OF_DAY)
        return dnd.isActiveAt(hour)
    }

    /** Shows a weather-alert notification unless the user's configured Do Not Disturb window is currently active.
     * Returns true if it was actually posted (so callers only mark an alert as "seen" when the user could see it). */
    fun showAlert(context: Context, match: CustomAlertMatch, dnd: DoNotDisturbSettings): Boolean {
        if (isQuietHoursActive(dnd)) return false
        return showNow(context, match)
    }

    /**
     * Single entry point for "notify about anything new", shared by the background worker and the app screen so
     * the same alert is never shown twice, and an alert that was suppressed by quiet hours is retried later
     * instead of being silently lost.
     */
    @Synchronized
    fun notifyNew(context: Context, matches: List<CustomAlertMatch>, dnd: DoNotDisturbSettings) {
        val prefs = context.getSharedPreferences("weather_alert_dedup", Context.MODE_PRIVATE)
        val cutoff = System.currentTimeMillis() - 12L * 60 * 60 * 1000
        val seen = (prefs.getStringSet("keys", emptySet()) ?: emptySet())
            .filter { it.substringAfterLast("|").toLongOrNull()?.let { t -> t > cutoff } ?: false }
            .toMutableSet()
        matches.filter { it.leadWarning && it.triggerAtMillis > System.currentTimeMillis() - 60 * 60 * 1000 }.forEach {
            val key = "${it.type}|${it.triggerAtMillis}"
            if (key !in seen && showAlert(context, it, dnd)) seen += key
        }
        prefs.edit().putStringSet("keys", seen).apply()
    }

    fun areNotificationsAllowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun openSystemNotificationSettings(context: Context) {
        val i = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    /** Fires immediately, ignoring Do Not Disturb - used by the "Send test notification" button in Settings so the
     * user can verify notifications are actually reaching their device (permission granted, channel not muted, etc). */
    fun showTestNotification(context: Context) {
        showNow(
            context,
            CustomAlertMatch(
                label = "Test notification",
                detail = "If you can see this, weather alerts will reach you too."
            )
        )
    }

    private fun showNow(context: Context, match: CustomAlertMatch): Boolean {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_alert)
            .setContentTitle(match.label)
            .setContentText(match.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(match.detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        // Distinct notification ID per alert type so multiple simultaneous alerts each get their own tray entry.
        val notificationId = NOTIFICATION_ID_BASE + match.type.ordinal
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching { nm.notify(notificationId, notification) }.isSuccess
    }
}
