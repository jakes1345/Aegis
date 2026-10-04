package com.xat.aegis.security

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs

/**
 * The relay's clock as last seen, kept for the Device tab's clock-integrity row.
 *
 * Every relay response carries a `Date` header, and the WebSocket handshake is
 * the one that arrives on every (re)connection. [com.xat.aegis.comms.RelayClient]
 * reads it to sign requests with the relay's time; it is also handed here, with
 * the phone's own time at the same instant, so a phone whose clock has been
 * moved can be told so even when the relay is not reachable right now.
 *
 * A clock that is off by more than a few seconds with automatic time switched on
 * is unusual; with it switched off, it is how certificate checks, one-time
 * codes and "this message is from an hour ago" are quietly broken.
 */
object RelayClock {

    internal const val PREFS_NAME = "relay_clock"
    private const val K_SERVER_TS = "server_ts"
    private const val K_LOCAL_TS = "local_ts"

    /** Drift beyond this is reported as a HIGH health fact. */
    const val DRIFT_WARN_MS = 30_000L

    /** A relay timestamp and what this phone's clock read when it arrived. */
    data class Sample(val serverTs: Long, val localTs: Long) {
        /** Relay time minus phone time: positive when the phone is behind. */
        val driftMs: Long get() = serverTs - localTs
        val drifted: Boolean get() = abs(driftMs) > DRIFT_WARN_MS
    }

    @Volatile
    private var appContext: Context? = null

    /** Call once per process; [record] is a no-op until it has run. */
    fun bind(context: Context) {
        appContext = context.applicationContext
    }

    /** Stores [serverMs] against [localMs]; called from the relay client on every response. */
    fun record(serverMs: Long, localMs: Long = System.currentTimeMillis()) {
        val ctx = appContext ?: return
        runCatching {
            prefs(ctx).edit().putLong(K_SERVER_TS, serverMs).putLong(K_LOCAL_TS, localMs).apply()
        }
    }

    /** The most recent sample, or null when the relay has never answered on this install. */
    fun lastSample(context: Context): Sample? = runCatching {
        val p = prefs(context)
        val server = p.getLong(K_SERVER_TS, 0L)
        val local = p.getLong(K_LOCAL_TS, 0L)
        if (server == 0L || local == 0L) null else Sample(server, local)
    }.getOrNull()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
