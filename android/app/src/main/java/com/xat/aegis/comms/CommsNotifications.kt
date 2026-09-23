package com.xat.aegis.comms

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
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
    const val CHANNEL_CALLS = "calls"

    /** Intent extras naming the tab and conversation MainActivity should open. */
    const val EXTRA_TAB = "com.xat.aegis.TAB"
    const val EXTRA_PEER = "com.xat.aegis.PEER"
    /** Set when the notification's Answer button was tapped: accept the ringing call. */
    const val EXTRA_ACCEPT_CALL = "com.xat.aegis.ACCEPT_CALL"

    /** Notification id of the connection service's foreground notification. */
    const val LINK_NOTIFICATION_ID = 0x5C00_0001
    const val INCOMING_CALL_NOTIFICATION_ID = 0x5C00_0002
    const val CALL_NOTIFICATION_ID = 0x5C00_0003

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
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Incoming and ongoing encrypted calls"
                    // The app rings with the phone's own ringtone; the channel stays quiet.
                    setSound(null, null)
                    enableVibration(false)
                }
        )
    }

    // ── Calls ────────────────────────────────────────────────────────────

    private fun person(contact: Contact) = Person.Builder()
        .setName(contact.name.ifBlank { formatAegisNumber(contact.number) })
        .setImportant(true)
        .build()

    private fun openCallScreen(context: Context, accept: Boolean, requestCode: Int): PendingIntent {
        val open = Intent(context, MainActivity::class.java)
            .setAction(if (accept) "com.xat.aegis.ACCEPT_CALL" else "com.xat.aegis.OPEN_CALL")
            .putExtra(EXTRA_TAB, COMMS_TAB_INDEX)
            .putExtra(EXTRA_ACCEPT_CALL, accept)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Rings for an incoming call: a call-style card with Decline and Answer, full screen when allowed. */
    fun incomingCall(context: Context, call: ActiveCall) {
        if (!canPost(context)) return
        ensureChannels(context)
        val decline = PendingIntent.getBroadcast(
            context, INCOMING_CALL_NOTIFICATION_ID + 1,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val answer = openCallScreen(context, accept = true, requestCode = INCOMING_CALL_NOTIFICATION_ID + 2)
        val show = openCallScreen(context, accept = false, requestCode = INCOMING_CALL_NOTIFICATION_ID + 3)
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Incoming encrypted call")
            .setContentText(call.peer.name.ifBlank { formatAegisNumber(call.peer.number) } + if (call.peer.verified) "" else " (unverified)")
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(call.peer), decline, answer))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(show)
            .setFullScreenIntent(show, true)
            .setTimeoutAfter(50_000L)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(INCOMING_CALL_NOTIFICATION_ID, notification)
    }

    fun cancelIncomingCall(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(INCOMING_CALL_NOTIFICATION_ID)
    }

    /** The foreground notification behind [CallService] while a call is up. */
    fun ongoingCall(context: Context, call: ActiveCall): Notification {
        ensureChannels(context)
        val hangUp = PendingIntent.getBroadcast(
            context, CALL_NOTIFICATION_ID + 1,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_HANGUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when (call.phase) {
            CallPhase.DIALING -> "Calling…"
            CallPhase.CONNECTING -> "Connecting…"
            CallPhase.CONNECTED -> "Encrypted call in progress"
            CallPhase.RECONNECTING -> "Reconnecting…"
            CallPhase.INCOMING -> "Ringing"
            CallPhase.ENDED -> "Call ended"
        }
        return NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(call.peer.name.ifBlank { formatAegisNumber(call.peer.number) })
            .setContentText(text)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(call.peer), hangUp))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openCallScreen(context, accept = false, requestCode = CALL_NOTIFICATION_ID + 2))
            .apply { if (call.connectedAt > 0L) setWhen(call.connectedAt).setUsesChronometer(true) }
            .build()
    }

    /** A call that rang out, or arrived while the phone was offline or busy. */
    fun missedCall(context: Context, contact: Contact) {
        if (!canPost(context)) return
        ensureChannels(context)
        val open = Intent(context, MainActivity::class.java)
            .setAction("com.xat.aegis.OPEN_THREAD")
            .putExtra(EXTRA_TAB, COMMS_TAB_INDEX)
            .putExtra(EXTRA_PEER, contact.number)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, contact.number.hashCode() xor 0x4D, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Missed call")
            .setContentText(contact.name.ifBlank { formatAegisNumber(contact.number) })
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setShowWhen(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(0x5B00_0000 or (contact.number.hashCode() and 0x00FF_FFFF), notification)
    }

    /**
     * Whether a message notification would be shown. POST_NOTIFICATIONS is a
     * runtime permission only from API 33; on 31 and 32 the permission check
     * reports "denied" for a permission that does not exist there, so those
     * versions ask the notification manager instead.
     */
    fun canPost(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /** Posts (or replaces) the notification for [contact] with the newest inbound messages. */
    fun notifyInbound(context: Context, contact: Contact, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        if (!canPost(context)) return
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
