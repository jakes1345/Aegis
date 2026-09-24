package com.xat.aegis.comms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Checks on the background relay connection every few minutes while comms
 * are online.
 *
 * Timers inside the app stop while the phone sleeps, so a socket that died
 * quietly (a carrier dropping the connection, a relay restart the phone never
 * heard about) could stay dead for hours, and calls in that time never rang.
 * An alarm is the one clock Android keeps for a sleeping app: when it fires,
 * the link is probed and, if the relay has gone silent, reconnected, with the
 * phone kept awake until that is done.
 */
class LinkWatchdog : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        CommsRepository.init(app)
        if (!CommsRepository.isRegistered() || !CommsRepository.state.value.online) {
            cancel(app)
            return
        }
        val lock = app.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aegis:relay-watchdog")
            ?.apply { acquire(CHECK_BUDGET_MS + 2_000L) }
        val pending = goAsync()
        scope.launch {
            try {
                // The service keeps the link held; if Android stopped it, ask for it again.
                if (!LiveLink.held()) CommsService.start(app)
                LiveLink.checkAlive()
                // Give a probe or a reconnect time to finish before the phone may sleep.
                delay(PROBE_SETTLE_MS)
                withTimeoutOrNull(CHECK_BUDGET_MS - PROBE_SETTLE_MS) { LiveLink.connected.first { it } }
            } finally {
                schedule(app)
                runCatching { if (lock?.isHeld == true) lock.release() }
                pending.finish()
            }
        }
    }

    companion object {
        /** Android lets an idle app's alarm fire about every nine minutes at most. */
        private const val INTERVAL_MS = 10 * 60_000L
        private const val CHECK_BUDGET_MS = 25_000L
        private const val PROBE_SETTLE_MS = 7_000L
        private const val ACTION = "com.xat.aegis.comms.LINK_WATCHDOG"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0x5C00_0010,
            Intent(context, LinkWatchdog::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /** Arms (or re-arms) the next check. Allowed in Doze; needs no exact-alarm permission. */
        fun schedule(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            runCatching {
                alarms.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS,
                    intent(context)
                )
            }.onFailure { CommsLog.add("Could not schedule the connection check: ${it.message}") }
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(intent(context))
        }
    }
}
