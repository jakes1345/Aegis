package com.xat.aegis.analysis

import com.xat.aegis.EventKind
import com.xat.aegis.Severity
import com.xat.aegis.Store
import com.xat.aegis.TimelineEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * A durable, newest-first log of the events worth remembering: a device
 * confirmed following, an IMSI-catcher finding, a scanned NFC tag, and when
 * scanning started and stopped. This is what turns "something is wrong right
 * now" into "this has happened three times this week, here and here" — the
 * record you can actually act on or hand to someone.
 *
 * It is capped so the file cannot grow without bound, dropping the oldest
 * entries first.
 */
class EventLog(private val store: Store, private val cap: Int = 500) {

    private val events = ArrayList<TimelineEvent>()

    init {
        val root = store.load { JSONObject().put("events", JSONArray()) }
        val arr = root.optJSONArray("events") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            events.add(fromJson(o))
        }
        events.sortByDescending { it.ts }
    }

    @Synchronized
    fun snapshot(): List<TimelineEvent> = ArrayList(events)

    /**
     * Records an event. [dedupeKey], when given, suppresses a repeat of the same
     * logical event within [dedupeWindowMs] — so a device that stays "following"
     * across many scan cycles produces one timeline entry, not hundreds.
     */
    @Synchronized
    fun record(
        event: TimelineEvent,
        dedupeKey: String? = null,
        dedupeWindowMs: Long = 30 * 60 * 1000L
    ): Boolean {
        if (dedupeKey != null) {
            val recent = events.firstOrNull { it.id.startsWith("$dedupeKey@") }
            if (recent != null && event.ts - recent.ts < dedupeWindowMs) return false
        }
        events.add(0, event)
        while (events.size > cap) events.removeAt(events.size - 1)
        persist()
        return true
    }

    @Synchronized
    fun clear() {
        events.clear()
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        for (e in events) arr.put(toJson(e))
        store.json.put("events", arr)
        // Mutating the JSON in place leaves the store unaware it has changed, and
        // flush() short-circuits when it is not dirty — so without this the timeline
        // was rebuilt in memory on every event and never once written to disk.
        store.markDirty()
        store.flush()
    }

    private fun toJson(e: TimelineEvent) = JSONObject().apply {
        put("id", e.id)
        put("kind", e.kind.name)
        put("ts", e.ts)
        put("title", e.title)
        put("detail", e.detail)
        put("severity", e.severity.name)
        if (e.lat != null) put("lat", e.lat)
        if (e.lon != null) put("lon", e.lon)
    }

    private fun fromJson(o: JSONObject) = TimelineEvent(
        id = o.optString("id"),
        kind = runCatching { EventKind.valueOf(o.optString("kind")) }.getOrDefault(EventKind.FOLLOWING),
        ts = o.optLong("ts"),
        title = o.optString("title"),
        detail = o.optString("detail"),
        severity = runCatching { Severity.valueOf(o.optString("severity")) }.getOrDefault(Severity.MEDIUM),
        lat = if (o.has("lat")) o.optDouble("lat") else null,
        lon = if (o.has("lon")) o.optDouble("lon") else null
    )
}
