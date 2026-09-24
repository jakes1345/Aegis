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
import com.xat.aegis.CallActivity
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
    /**
     * Incoming calls. The original calls channel had vibration off, so a ring
     * whose in-app vibration Android suppressed was silent in vibrate mode.
     * Channel settings are fixed once created, hence a new id.
     */
    const val CHANNEL_RINGING = "calls_ringing"

    /** Intent extras naming the tab and conversation MainActivity should open. */
    const val EXTRA_TAB = "com.xat.aegis.TAB"
    const val EXTRA_PEER = "com.xat.aegis.PEER"
    /**
     * Set when the notification's Answer button was tapped: accept the ringing
     * call. Read only by [CallActivity], which is not exported, and only for
     * the call named by [EXTRA_CALL_ID]; no other app can make Aegis answer.
     */
    const val EXTRA_ACCEPT_CALL = "com.xat.aegis.ACCEPT_CALL"
    /** The call a call notification or its buttons are about. */
    const val EXTRA_CALL_ID = "com.xat.aegis.CALL_ID"

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
            NotificationChannel(CHANNEL_RINGING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Encrypted calls ringing on this phone"
                    // The app plays the phone's own ringtone; the channel adds a
                    // vibration that still works when Android mutes the app's own.
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 1200, 800, 1200)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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

    /**
     * The call screen: [CallActivity], which shows over the lock screen and
     * turns the display on, unlike the main screen.
     */
    private fun openCallScreen(context: Context, callId: String, accept: Boolean, requestCode: Int): PendingIntent {
        val open = Intent(context, CallActivity::class.java)
            .setAction(if (accept) "com.xat.aegis.ACCEPT_CALL" else "com.xat.aegis.OPEN_CALL")
            .putExtra(EXTRA_CALL_ID, callId)
            .putExtra(EXTRA_ACCEPT_CALL, accept)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Rings for an incoming call: a call-style card with Decline and Answer, full screen when allowed. */
    fun incomingCall(context: Context, call: ActiveCall) {
        if (!canPost(context)) return
        ensureChannels(context)
        val decline = PendingIntent.getBroadcast(
            context, INCOMING_CALL_NOTIFICATION_ID + 1,
            Intent(context, CallActionReceiver::class.java)
                .setAction(CallActionReceiver.ACTION_DECLINE)
                .putExtra(EXTRA_CALL_ID, call.id)
                .putExtra(EXTRA_PEER, call.peer.number),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val answer = openCallScreen(context, call.id, accept = true, requestCode = INCOMING_CALL_NOTIFICATION_ID + 2)
        val show = openCallScreen(context, call.id, accept = false, requestCode = INCOMING_CALL_NOTIFICATION_ID + 3)
        val notification = NotificationCompat.Builder(context, CHANNEL_RINGING)
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
            Intent(context, CallActionReceiver::class.java)
                .setAction(CallActionReceiver.ACTION_HANGUP)
                .putExtra(EXTRA_CALL_ID, call.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when (call.phase) {
            CallPhase.DIALING -> if (call.ringing) "Ringing…" else "Calling…"
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
            .setContentIntent(openCallScreen(context, call.id, accept = false, requestCode = CALL_NOTIFICATION_ID + 2))
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
     * Whether an incoming call may take over the screen of a locked phone. From
     * Android 14 this is a special permission the owner can switch off (and
     * some phones ship with it off); without it the call rings as a heads-up
     * notification only.
     */
    fun canUseFullScreen(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != false
        } else true

    /**
     * Whether the incoming-call channel can still ring: the owner (or a phone's
     * notification manager) can switch it off or lower it to silent, and then
     * a call arrives with no heads-up and no full-screen ring even though
     * notifications as a whole are allowed.
     */
    fun callsChannelOk(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        ensureChannels(context)
        val channel = manager.getNotificationChannel(CHANNEL_RINGING) ?: return true
        return channel.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    /** Opens the system settings of the incoming-call channel. */
    fun openCallsChannelSettings(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_RINGING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { openAppNotificationSettings(context) }
    }

    /** Opens Aegis's notification settings, where a permanently refused permission can be granted. */
    fun openAppNotificationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Opens the system page where full-screen calls are allowed for Aegis (Android 14+). */
    fun openFullScreenSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { openAppNotificationSettings(context) }
    }

    /**
     * The foreground notification [CallService] shows when it starts after the
     * call has already ended: a service started in the foreground must go
     * foreground before it may stop, or Android kills the app.
     */
    fun callEndedPlaceholder(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Call ended")
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
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
