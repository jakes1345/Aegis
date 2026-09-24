package com.xat.aegis.comms

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A short, on-device record of what the comms module did with the relay and
 * with each envelope: connections made and lost, envelopes that could not be
 * read, calls that arrived too late to ring, sessions that were reset.
 *
 * Failures in this path used to be dropped silently, so a call that never rang
 * left nothing to go on. The owner can read this in COMMS settings and copy it.
 * It never holds message text, only events, numbers of the owner's own contacts
 * and error descriptions, and it stays on the phone.
 */
object CommsLog {

    private const val TAG = "CommsLog"
    private const val PREFS = "comms_log"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 150

    data class Entry(val ts: Long, val text: String)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            _entries.value = runCatching {
                val arr = JSONArray(p.getString(KEY, "[]"))
                (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Entry(it.getLong("t"), it.getString("m")) } }
            }.getOrDefault(emptyList())
        }
    }

    fun add(text: String) {
        Log.i(TAG, text)
        synchronized(this) {
            val next = (_entries.value + Entry(System.currentTimeMillis(), text)).takeLast(MAX_ENTRIES)
            _entries.value = next
            persistLocked(next)
        }
    }

    fun clear() {
        synchronized(this) {
            _entries.value = emptyList()
            persistLocked(emptyList())
        }
    }

    private fun persistLocked(list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().put("t", e.ts).put("m", e.text))
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
    }
}

/** The last characters of a relay envelope id, enough to tell envelopes apart in the log. */
internal fun shortId(id: String): String = id.takeLast(6)
