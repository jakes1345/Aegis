package com.xat.aegis.comms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Decline and Hang up buttons on call notifications. Answer opens
 * [com.xat.aegis.CallActivity] instead, which needs the screen.
 *
 * Both buttons send a signal over the network, so the receiver keeps itself
 * alive with goAsync() until it has gone (or a few seconds have passed):
 * returning at once let Android freeze the process with the reject unsent,
 * and the caller heard ringing until the call timed out.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        CommsRepository.init(app)
        val callId = intent.getStringExtra(CommsNotifications.EXTRA_CALL_ID)
        val peer = intent.getStringExtra(CommsNotifications.EXTRA_PEER)
        val action = intent.action
        if (action == ACTION_DECLINE) CommsNotifications.cancelIncomingCall(app)
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(SIGNAL_WAIT_MS) {
                    runCatching {
                        when (action) {
                            ACTION_DECLINE -> CallManager.declineFromNotification(callId, peer)
                            ACTION_HANGUP -> CallManager.hangUpFromNotification(callId)
                        }
                    }.onFailure { CommsLog.add("Call button failed: ${it.message}") }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.xat.aegis.comms.CALL_DECLINE"
        const val ACTION_HANGUP = "com.xat.aegis.comms.CALL_HANGUP"
        /** A broadcast gets about ten seconds before Android calls it hung. */
        private const val SIGNAL_WAIT_MS = 8_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
