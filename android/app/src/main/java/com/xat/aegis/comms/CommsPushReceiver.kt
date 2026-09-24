package com.xat.aegis.comms

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush endpoint of the app. The distributor the owner installed (ntfy,
 * NextPush, Conversations…) delivers here; there is no Google service in the
 * path and the push itself carries nothing but the word "wake". On a wake the
 * app fetches its inbox over the signed relay channel and opens the envelopes
 * locally, so the push provider learns only that a message exists.
 */
class CommsPushReceiver : PushService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CommsRepository.init(applicationContext)
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        Log.i(TAG, "push endpoint received (temporary=${endpoint.temporary})")
        CommsRepository.onPushEndpoint(endpoint.url)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // The library's own wake lock lasts ten seconds; the sync gets its own so
        // a slow network does not leave a half-fetched inbox behind.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aegis:comms-sync").apply { acquire(SYNC_BUDGET_MS) }
        // A wake means the background connection is not there. Android allows the
        // restart from here when Aegis is exempt from battery optimisation or the
        // distributor raised it to the foreground; otherwise the app does it on open.
        if (CommsRepository.state.value.online) CommsService.start(applicationContext)
        scope.launch {
            try {
                if (!CommsService.syncWithin(SYNC_BUDGET_MS - 2_000L)) Log.w(TAG, "push-triggered sync did not finish in time")
            } finally {
                if (lock.isHeld) lock.release()
            }
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "push registration failed: $reason")
        CommsRepository.onPushFailed(
            when (reason) {
                FailedReason.NETWORK -> "no network; it will be retried"
                FailedReason.ACTION_REQUIRED -> "the distributor app needs your attention"
                FailedReason.VAPID_REQUIRED -> "the distributor requires web-push keys, which Aegis does not use"
                FailedReason.INTERNAL_ERROR -> "distributor error"
            }
        )
    }

    override fun onUnregistered(instance: String) {
        Log.i(TAG, "push unregistered by the distributor")
        CommsRepository.onPushEndpoint(null)
    }

    override fun onTempUnavailable(instance: String) {
        Log.i(TAG, "distributor temporarily unavailable")
    }

    private companion object {
        const val TAG = "CommsPush"
        const val SYNC_BUDGET_MS = 30_000L
    }
}
