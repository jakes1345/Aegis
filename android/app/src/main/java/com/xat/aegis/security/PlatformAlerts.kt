package com.xat.aegis.security

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xat.aegis.Alert
import com.xat.aegis.MainActivity
import com.xat.aegis.Severity

/**
 * The notification shade's view of the platform monitors. [com.xat.aegis.Registry.publishAlert]
 * calls [notify] for every alert of MEDIUM or worse once the timeline has
 * accepted it, so a standing condition the timeline deduplicates is also
 * posted once, not on every poll.
 *
 * One channel for all of them: the alerts are rare and each is a different
 * kind of "someone may be at your phone", so splitting them across channels
 * would only give the user more switches to turn the warning off with.
 */
object PlatformAlerts {

    const val CHANNEL = "platform_alerts"

    /**
     * Notification ids live in their own range so they cannot collide with the
     * scanner's (1, 2, hash of a device key) or comms' (0x5C00_xxxx). The low
     * bits come from the alert's dedupe key, so a repeat of the same condition
     * replaces its earlier notification instead of stacking.
     */
    private const val ID_BASE = 0x7A00_0000
    private const val ID_MASK = 0x00FF_FFFF

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone security alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "A biometric was enrolled, the screen was recorded, the phone was " +
                        "unlocked while you slept, a wireless microphone appeared, or the clock is wrong"
                }
        )
    }

    /** Posts [alert]. A no-op without POST_NOTIFICATIONS; never throws. */
    fun notify(context: Context, alert: Alert) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = when (alert.severity) {
            Severity.CRITICAL, Severity.HIGH -> android.R.drawable.stat_notify_error
            else -> android.R.drawable.stat_sys_warning
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle(alert.title)
            .setContentText(alert.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.detail))
            .setSmallIcon(icon)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setWhen(alert.ts)
            .setShowWhen(true)
            .build()
        val id = ID_BASE or ((alert.dedupeKey ?: alert.id).hashCode() and ID_MASK)
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(id, n) }
    }
}
