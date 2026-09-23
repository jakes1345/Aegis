package com.xat.aegis.comms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The decline and hang-up buttons on call notifications. Accept goes through MainActivity. */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        CommsRepository.init(context.applicationContext)
        when (intent.action) {
            ACTION_DECLINE -> CallManager.reject()
            ACTION_HANGUP -> CallManager.hangUp()
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.xat.aegis.comms.CALL_DECLINE"
        const val ACTION_HANGUP = "com.xat.aegis.comms.CALL_HANGUP"
    }
}
