package com.xat.aegis

import android.content.Context
import com.xat.aegis.analysis.EventLog

/**
 * The one timeline for the whole process.
 *
 * The event log used to be created inside ScanService, so anything outside the
 * service — the NFC reader in the activity, most obviously — had nowhere to record
 * to, and EventKind.NFC_TAG was never written. Two independent instances would
 * each hold their own copy of timeline.json in memory and overwrite each other on
 * flush, so there is exactly one, created on first use from the application
 * context and shared by the service and the activity.
 */
object TimelineLog {

    @Volatile
    private var store: Store? = null

    @Volatile
    private var log: EventLog? = null

    /** The shared log, loading it from disk on first use. Does file I/O on first call. */
    fun get(context: Context): EventLog {
        log?.let { return it }
        synchronized(this) {
            log?.let { return it }
            val s = Store(context.applicationContext, "timeline.json")
            val l = EventLog(s)
            store = s
            log = l
            return l
        }
    }

    /**
     * Records [event] and, if it was not suppressed as a duplicate, publishes the new
     * timeline. Writes to disk; call off the main thread.
     */
    fun record(context: Context, event: TimelineEvent, dedupeKey: String? = null): Boolean {
        val l = get(context)
        val recorded = l.record(event, dedupeKey)
        if (recorded) Registry.publishTimeline(l.snapshot())
        return recorded
    }

    /** Publishes what is on disk, for a UI that starts before anything is recorded. */
    fun publish(context: Context) {
        Registry.publishTimeline(get(context).snapshot())
    }

    /** Writes any pending changes. A no-op if the log was never opened. */
    fun flush() {
        store?.flush()
    }
}
