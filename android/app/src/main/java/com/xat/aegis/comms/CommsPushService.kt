package com.xat.aegis.comms

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives the relay's wake-ups. The push itself carries only a kind and a
 * cursor; the message content is fetched from the relay over TLS with the device
 * token, then cached and shown.
 *
 * Only active in builds that include google-services.json; without it Firebase
 * never starts this service.
 */
class CommsPushService : FirebaseMessagingService() {

    // firebase-messaging 25.1 marks onNewToken deprecated but ships no successor;
    // it is still the only callback that reports a rotated registration token.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        CommsRepository.init(applicationContext)
        CommsRepository.onPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["kind"] != "message") return
        CommsRepository.init(applicationContext)
        if (!CommsRepository.isPaired) return
        // onMessageReceived runs on a background thread with roughly ten seconds
        // of budget; the sync is bounded well inside that.
        val fresh = runBlocking { withTimeoutOrNull(8_000L) { CommsRepository.sync() } } ?: return
        fresh.groupBy { it.peer }.forEach { (peer, list) ->
            CommsNotifications.notifyInbound(applicationContext, peer, list, COMMS_TAB_INDEX)
        }
    }

    companion object {
        /** Index of the COMMS tab in MainActivity's tab bar. */
        const val COMMS_TAB_INDEX = 7
    }
}
