package com.xat.aegis.comms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioSwitch
import com.twilio.voice.AcceptOptions
import com.twilio.voice.Call
import com.twilio.voice.CallException
import com.twilio.voice.CallInvite
import com.twilio.voice.CancelledCallInvite
import com.twilio.voice.ConnectOptions
import com.twilio.voice.Voice
import com.xat.aegis.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the one call this phone can have at a time.
 *
 * An incoming call arrives as a Twilio push through [CommsPushService], which
 * starts this service with the CallInvite: it rings, vibrates and posts a
 * full-screen call notification with Accept and Decline. Accepting, or placing
 * an outgoing call, promotes the service to the foreground with the microphone
 * type so the audio session survives the app leaving the screen. Every state
 * change is published through [CommsRepository.activeCall] for the in-call UI.
 */
class CallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var invite: CallInvite? = null
    private var call: Call? = null
    private var record: ActiveCall? = null

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private lateinit var audioSwitch: AudioSwitch
    private var audioActive = false

    override fun onCreate() {
        super.onCreate()
        CommsRepository.init(applicationContext)
        ensureChannels()
        audioSwitch = AudioSwitch(
            applicationContext,
            preferredDeviceList = listOf(
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Earpiece::class.java,
                AudioDevice.Speakerphone::class.java
            )
        )
        audioSwitch.start { _, selected ->
            record?.let { publish(it.copy(speaker = selected is AudioDevice.Speakerphone)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INCOMING -> IntentCompat.getParcelableExtra(intent, EXTRA_INVITE, CallInvite::class.java)?.let { incoming(it) }
            ACTION_CANCELLED -> IntentCompat.getParcelableExtra(intent, EXTRA_CANCELLED, CancelledCallInvite::class.java)?.let { cancelled(it) }
            ACTION_OUTGOING -> intent.getStringExtra(EXTRA_TO)?.let { outgoing(it) }
            // The notification's Accept/Decline carry the invite again: while
            // ringing the service is not in the foreground, so the process may
            // have been killed and this be a fresh instance.
            ACTION_ACCEPT -> { restoreInvite(intent); accept() }
            ACTION_REJECT -> { restoreInvite(intent); reject() }
            ACTION_HANGUP -> hangUp()
            ACTION_TOGGLE_MUTE -> toggleMute()
            ACTION_TOGGLE_SPEAKER -> toggleSpeaker()
        }
        // record is set synchronously by every path that is about to do work,
        // including an outgoing call whose token is still being fetched.
        if (call == null && invite == null && record == null) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun restoreInvite(intent: Intent) {
        if (invite != null) return
        val carried = IntentCompat.getParcelableExtra(intent, EXTRA_INVITE, CallInvite::class.java) ?: return
        invite = carried
        if (record == null) {
            val peer = carried.from?.let { normalise(it) } ?: "Unknown"
            record = ActiveCall(id = carried.callSid, peer = peer, direction = Direction.IN, phase = CallPhase.INCOMING)
        }
    }

    // ── Incoming ──────────────────────────────────────────────────────────

    private fun incoming(newInvite: CallInvite) {
        if (call != null || invite != null) {
            // Already busy: let the second caller hear busy rather than stacking.
            runCatching { newInvite.reject(applicationContext) }
            return
        }
        invite = newInvite
        val peer = newInvite.from?.let { normalise(it) } ?: "Unknown"
        publish(ActiveCall(id = newInvite.callSid, peer = peer, direction = Direction.IN, phase = CallPhase.INCOMING))
        startRinging()
        notificationManager().notify(NOTIFICATION_ID, incomingNotification(peer, newInvite))
    }

    private fun cancelled(cancelledInvite: CancelledCallInvite) {
        val current = invite ?: return
        if (current.callSid != cancelledInvite.callSid) return
        stopRinging()
        invite = null
        notificationManager().cancel(NOTIFICATION_ID)
        publish(record?.copy(phase = CallPhase.ENDED, error = null))
        finish()
    }

    private fun accept() {
        val current = invite ?: return
        stopRinging()
        invite = null
        val peer = record?.peer ?: current.from.orEmpty()
        publish(ActiveCall(id = current.callSid, peer = peer, direction = Direction.IN, phase = CallPhase.CONNECTING))
        goForeground(peer, "Connecting…")
        audioSwitch.activate(); audioActive = true
        call = current.accept(applicationContext, AcceptOptions.Builder().build(), listener)
    }

    private fun reject() {
        val current = invite ?: return
        stopRinging()
        invite = null
        runCatching { current.reject(applicationContext) }
        notificationManager().cancel(NOTIFICATION_ID)
        publish(null)
        finish()
    }

    // ── Outgoing ──────────────────────────────────────────────────────────

    private fun outgoing(to: String) {
        if (call != null || invite != null) return
        publish(ActiveCall(id = "", peer = to, direction = Direction.OUT, phase = CallPhase.CONNECTING))
        goForeground(to, "Calling…")
        scope.launch {
            val token = withContext(Dispatchers.IO) { CommsRepository.voiceToken() }
            if (token == null) {
                publish(record?.copy(phase = CallPhase.ENDED, error = "Could not get a call token from the relay"))
                finish()
                return@launch
            }
            audioSwitch.activate(); audioActive = true
            val options = ConnectOptions.Builder(token).params(mapOf("To" to to)).build()
            call = Voice.connect(applicationContext, options, listener)
        }
    }

    // ── In-call controls ──────────────────────────────────────────────────

    private fun hangUp() {
        val active = call
        if (active != null) {
            active.disconnect()
        } else if (invite != null) {
            reject()
        }
    }

    private fun toggleMute() {
        val active = call ?: return
        val muted = !active.isMuted
        active.mute(muted)
        publish(record?.copy(muted = muted))
        record?.let { updateOngoingNotification(it) }
    }

    private fun toggleSpeaker() {
        val speakerOn = record?.speaker == true
        val devices = audioSwitch.availableAudioDevices
        val target = if (speakerOn) {
            devices.firstOrNull { it is AudioDevice.BluetoothHeadset }
                ?: devices.firstOrNull { it is AudioDevice.WiredHeadset }
                ?: devices.firstOrNull { it is AudioDevice.Earpiece }
        } else {
            devices.firstOrNull { it is AudioDevice.Speakerphone }
        }
        if (target != null) {
            audioSwitch.selectDevice(target)
            publish(record?.copy(speaker = target is AudioDevice.Speakerphone))
        }
    }

    // ── Twilio call events ────────────────────────────────────────────────

    private val listener = object : Call.Listener {
        override fun onRinging(c: Call) {
            publish(record?.copy(id = c.sid ?: record?.id.orEmpty(), phase = CallPhase.RINGING))
            record?.let { updateOngoingNotification(it) }
        }

        override fun onConnected(c: Call) {
            publish(record?.copy(id = c.sid ?: record?.id.orEmpty(), phase = CallPhase.CONNECTED, connectedAt = System.currentTimeMillis()))
            record?.let { updateOngoingNotification(it) }
        }

        override fun onReconnecting(c: Call, e: CallException) {
            publish(record?.copy(phase = CallPhase.RECONNECTING))
        }

        override fun onReconnected(c: Call) {
            publish(record?.copy(phase = CallPhase.CONNECTED))
        }

        override fun onConnectFailure(c: Call, e: CallException) {
            ended(describe(e))
        }

        override fun onDisconnected(c: Call, e: CallException?) {
            ended(e?.let { describe(it) })
        }
    }

    private fun describe(e: CallException): String = when (e.errorCode) {
        CallException.EXCEPTION_INVALID_PHONE_NUMBER -> "That number is not valid"
        CallException.EXCEPTION_AUTHORIZATION_ERROR -> "The relay's call token was rejected"
        CallException.EXCEPTION_CONNECTION_ERROR, CallException.EXCEPTION_TRANSPORT_ERROR,
        CallException.EXCEPTION_SIGNALING_CONNECTION_DISCONNECTED -> "Connection lost"
        CallException.EXCEPTION_BUSY_HERE_ERROR, CallException.EXCEPTION_BUSY_EVERYWHERE_ERROR -> "Busy"
        CallException.EXCEPTION_DECLINE_ERROR -> "Declined"
        CallException.EXCEPTION_CALL_CANCELLED -> null
        else -> e.message ?: "Call failed (${e.errorCode})"
    } ?: ""

    private fun ended(error: String?) {
        call = null
        publish(record?.copy(phase = CallPhase.ENDED, error = error?.takeIf { it.isNotBlank() }))
        finish()
        // The call log on the relay updates from Twilio's callbacks a moment later.
        CommsRepository.syncInBackground()
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    private fun publish(state: ActiveCall?) {
        record = state
        CommsRepository.publishCall(state)
    }

    private fun finish() {
        if (audioActive) {
            runCatching { audioSwitch.deactivate() }
            audioActive = false
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notificationManager().cancel(NOTIFICATION_ID)
        // Leave the ENDED state visible briefly so the UI can show why; the
        // repository clears it.
        scope.launch {
            kotlinx.coroutines.delay(2_500L)
            if (call == null && invite == null) {
                if (record?.phase == CallPhase.ENDED) publish(null)
                stopSelf()
            }
        }
    }

    private fun goForeground(peer: String, status: String) {
        val notification = ongoingNotification(peer, status, muted = false)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun updateOngoingNotification(state: ActiveCall) {
        val status = when (state.phase) {
            CallPhase.CONNECTING -> "Connecting…"
            CallPhase.RINGING -> "Ringing…"
            CallPhase.CONNECTED -> "In call"
            CallPhase.RECONNECTING -> "Reconnecting…"
            else -> return
        }
        notificationManager().notify(NOTIFICATION_ID, ongoingNotification(state.peer, status, state.muted))
    }

    private fun startRinging() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                isLooping = true
                play()
            }
        }
        runCatching {
            val manager = getSystemService(VibratorManager::class.java)
            vibrator = manager?.defaultVibrator
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 800L, 1200L), 0))
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun ensureChannels() {
        val manager = notificationManager()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Calls to your Aegis number"
                // The service plays the ringtone itself so it can stop it on answer.
                setSound(null, null)
                enableVibration(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Ongoing call", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a call is in progress"
            }
        )
    }

    private fun incomingNotification(peer: String, callInvite: CallInvite): android.app.Notification {
        val open = openIntent()
        val fullScreen = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val accept = servicePending(2, ACTION_ACCEPT) { putExtra(EXTRA_INVITE, callInvite) }
        val decline = servicePending(3, ACTION_REJECT) { putExtra(EXTRA_INVITE, callInvite) }
        return NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(peer)
            .setContentText("Incoming call to your Aegis number")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(peer), decline, accept))
            .build()
    }

    private fun ongoingNotification(peer: String, status: String, muted: Boolean): android.app.Notification {
        val open = PendingIntent.getActivity(this, 1, openIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val hangUp = servicePending(4, ACTION_HANGUP)
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(peer)
            .setContentText(if (muted) "$status · muted" else status)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(peer), hangUp))
            .build()
    }

    private fun person(peer: String) = Person.Builder().setName(peer).setImportant(true).build()

    private fun openIntent() = Intent(this, MainActivity::class.java)
        .setAction("com.xat.aegis.OPEN_CALL")
        .putExtra(CommsNotifications.EXTRA_TAB, CommsPushService.COMMS_TAB_INDEX)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun servicePending(code: Int, action: String, configure: Intent.() -> Unit = {}) = PendingIntent.getService(
        this, code, Intent(this, CallService::class.java).setAction(action).apply(configure),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun normalise(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("client:")) return trimmed.removePrefix("client:")
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.isEmpty()) trimmed else "+$digits"
    }

    override fun onDestroy() {
        stopRinging()
        runCatching { call?.disconnect() }
        runCatching { invite?.reject(applicationContext) }
        if (audioActive) runCatching { audioSwitch.deactivate() }
        runCatching { audioSwitch.stop() }
        if (record != null) CommsRepository.publishCall(null)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_INCOMING = "com.xat.aegis.call.INCOMING"
        const val ACTION_CANCELLED = "com.xat.aegis.call.CANCELLED"
        const val ACTION_OUTGOING = "com.xat.aegis.call.OUTGOING"
        const val ACTION_ACCEPT = "com.xat.aegis.call.ACCEPT"
        const val ACTION_REJECT = "com.xat.aegis.call.REJECT"
        const val ACTION_HANGUP = "com.xat.aegis.call.HANGUP"
        const val ACTION_TOGGLE_MUTE = "com.xat.aegis.call.TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.xat.aegis.call.TOGGLE_SPEAKER"
        const val EXTRA_INVITE = "invite"
        const val EXTRA_CANCELLED = "cancelled"
        const val EXTRA_TO = "to"

        private const val CHANNEL_INCOMING = "incoming_calls"
        private const val CHANNEL_ONGOING = "ongoing_call"
        private const val NOTIFICATION_ID = 0x5A11

        fun send(context: Context, action: String, configure: Intent.() -> Unit = {}) {
            val intent = Intent(context, CallService::class.java).setAction(action).apply(configure)
            context.startService(intent)
        }
    }
}
