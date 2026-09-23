package com.xat.aegis.comms

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xat.aegis.MainActivity

/**
 * Notifications for the comms module: one per conversation for new messages,
 * updated in place as more arrive, and the quiet one the relay connection
 * service shows while comms are online.
 */
object CommsNotifications {

    /** Index of the COMMS tab in MainActivity's tab row. */
    const val COMMS_TAB_INDEX = 7

    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_LINK = "comms_link"

    /** Intent extras naming the tab and conversation MainActivity should open. */
    const val EXTRA_TAB = "com.xat.aegis.TAB"
    const val EXTRA_PEER = "com.xat.aegis.PEER"

    /** Notification id of the connection service's foreground notification. */
    const val LINK_NOTIFICATION_ID = 0x5C00_0001

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Encrypted messages to your Aegis number" }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LINK, "Comms connection", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Shown while Aegis keeps its connection to your relay open"
                    setShowBadge(false)
                }
        )
    }

    /** Posts (or replaces) the notification for [contact] with the newest inbound messages. */
    fun notifyInbound(context: Context, contact: Contact, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(context)

        val open = Intent(context, MainActivity::class.java)
            .setAction("com.xat.aegis.OPEN_THREAD")
            .putExtra(EXTRA_TAB, COMMS_TAB_INDEX)
            .putExtra(EXTRA_PEER, contact.number)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, contact.number.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val newest = messages.maxBy { it.ts }
        val style = NotificationCompat.InboxStyle()
        messages.sortedBy { it.ts }.takeLast(5).forEach { style.addLine(it.body) }
        val title = contact.name.ifBlank { formatAegisNumber(contact.number) }

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (contact.verified) title else "$title (unverified)")
            .setContentText(newest.body)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setWhen(newest.ts)
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle("New message")
                    .setContentText("Unlock to read")
                    .build()
            )
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(notificationId(contact.number), notification)
    }

    fun cancel(context: Context, peer: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(peer))
    }

    /** The ongoing notification behind the relay connection service. */
    fun linkNotification(context: Context, connected: Boolean, number: String?): Notification {
        ensureChannels(context)
        val open = Intent(context, MainActivity::class.java)
            .setAction("com.xat.aegis.OPEN_COMMS")
            .putExtra(EXTRA_TAB, COMMS_TAB_INDEX)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, LINK_NOTIFICATION_ID, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            context, LINK_NOTIFICATION_ID + 1,
            Intent(context, CommsService::class.java).setAction(CommsService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_LINK)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(if (connected) "Comms online" else "Comms reconnecting")
            .setContentText(
                number?.let { "Aegis number ${formatAegisNumber(it)} · encrypted messages arrive instantly" }
                    ?: "Connecting to your relay"
            )
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, "Go offline", stop)
            .build()
    }

    private fun notificationId(peer: String) = 0x5A00_0000 or (peer.hashCode() and 0x00FF_FFFF)
}
