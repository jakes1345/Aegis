package com.xat.aegis.comms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xat.aegis.MainActivity

/** Message notifications: one per conversation, updated in place as messages arrive. */
object CommsNotifications {

    const val CHANNEL = "messages"

    /** Intent extra naming the tab MainActivity should open on. */
    const val EXTRA_TAB = "com.xat.aegis.TAB"
    const val EXTRA_PEER = "com.xat.aegis.PEER"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Texts to your Aegis number" }
        )
    }

    /** Posts (or replaces) the notification for [peer] with the newest inbound messages. */
    fun notifyInbound(context: Context, peer: String, messages: List<SmsMessage>, tabIndex: Int) {
        if (messages.isEmpty()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)

        val open = Intent(context, MainActivity::class.java)
            .setAction("com.xat.aegis.OPEN_THREAD")
            .putExtra(EXTRA_TAB, tabIndex)
            .putExtra(EXTRA_PEER, peer)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, peer.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val newest = messages.maxBy { it.ts }
        val style = NotificationCompat.InboxStyle()
        messages.sortedBy { it.ts }.takeLast(5).forEach { style.addLine(previewOf(it)) }

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(peer)
            .setContentText(previewOf(newest))
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setWhen(newest.ts)
            .setShowWhen(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(notificationId(peer), notification)
    }

    /** A call to the number that no phone answered. */
    fun notifyMissedCall(context: Context, call: CallRecord, tabIndex: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java)
            .setAction("com.xat.aegis.OPEN_CALLS")
            .putExtra(EXTRA_TAB, tabIndex)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, call.id.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Missed call")
            .setContentText(call.peer)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setWhen(call.ts)
            .setShowWhen(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(0x5B00_0000 or (call.id.hashCode() and 0x00FF_FFFF), notification)
    }

    fun cancel(context: Context, peer: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(peer))
    }

    private fun notificationId(peer: String) = 0x5A00_0000 or (peer.hashCode() and 0x00FF_FFFF)

    private fun previewOf(m: SmsMessage): String = when {
        m.body.isNotBlank() -> m.body
        m.media.isNotEmpty() -> "Picture message"
        else -> "Message"
    }
}
