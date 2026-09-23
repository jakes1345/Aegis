package com.xat.aegis.comms

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.twilio.voice.CallException
import com.twilio.voice.CallInvite
import com.twilio.voice.CancelledCallInvite
import com.twilio.voice.MessageListener
import com.twilio.voice.Voice
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives the relay's wake-ups and Twilio's call pushes.
 *
 * A Twilio payload (an incoming call, or its cancellation) is recognised by the
 * Voice SDK and handed to [CallService]. Anything else is the relay's own
 * `{kind, …}` data: it carries only a kind and a cursor, so the content is
 * fetched from the relay over TLS with the device token, then cached and shown.
 *
 * Only active in builds that include google-services.json; without it Firebase
 * never starts this service.
 */
class CommsPushService : FirebaseMessagingService(), MessageListener {

    // firebase-messaging 25.1 marks onNewToken deprecated but ships no successor;
    // it is still the only callback that reports a rotated registration token.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        CommsRepository.init(applicationContext)
        CommsRepository.onPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        CommsRepository.init(applicationContext)
        val data = message.data
        if (data.isEmpty()) return
        // Twilio's payload is recognised by the SDK; a false return means it was
        // not one, so it must be the relay's.
        if (Voice.handleMessage(this, data, this)) return
        when (data["kind"]) {
            "message", "call" -> Unit
            else -> return
        }
        if (!CommsRepository.isPaired) return
        // onMessageReceived runs on a background thread with roughly ten seconds
        // of budget; the sync is bounded well inside that.
        val (fresh, missed) = runBlocking { withTimeoutOrNull(8_000L) { CommsRepository.syncAll() } } ?: return
        fresh.groupBy { it.peer }.forEach { (peer, list) ->
            CommsNotifications.notifyInbound(applicationContext, peer, list, COMMS_TAB_INDEX)
        }
        missed.forEach { CommsNotifications.notifyMissedCall(applicationContext, it, COMMS_TAB_INDEX) }
    }

    override fun onCallInvite(callInvite: CallInvite) {
        startService(
            Intent(this, CallService::class.java)
                .setAction(CallService.ACTION_INCOMING)
                .putExtra(CallService.EXTRA_INVITE, callInvite)
        )
    }

    override fun onCancelledCallInvite(cancelledCallInvite: CancelledCallInvite, callException: CallException?) {
        startService(
            Intent(this, CallService::class.java)
                .setAction(CallService.ACTION_CANCELLED)
                .putExtra(CallService.EXTRA_CANCELLED, cancelledCallInvite)
        )
    }

    companion object {
        /** Index of the COMMS tab in MainActivity's tab bar. */
        const val COMMS_TAB_INDEX = 7
    }
}
