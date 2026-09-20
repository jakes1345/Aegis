package com.trackdetect

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * A small JSON-backed key/value store written atomically to the app's private
 * files directory.
 *
 * IMSI-catcher detection needs to know what "normal" looks like at the places
 * you actually go, and the timeline has to survive the process being killed and
 * the phone rebooting. Public tower databases need a key and a connection; a
 * baseline you build yourself needs neither and is accurate for your own routes.
 * So this state persists across runs, on disk, and never leaves the device.
 *
 * Writes are batched — the cell baseline updates every poll and the flash does
 * not need to hear about each one — but always flushed on stop and on a
 * confirmed detection, the two moments where losing data would matter.
 */
class Store(context: Context, filename: String) {

    private val file = File(context.filesDir, filename)
    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var data: JSONObject? = null
    private var dirty = false
    private var lastFlush = 0L

    fun load(fallback: () -> JSONObject): JSONObject {
        synchronized(lock) {
            data?.let { return it }
            val loaded = try {
                if (file.exists()) JSONObject(file.readText()) else fallback()
            } catch (_: Exception) {
                fallback()
            }
            data = loaded
            return loaded
        }
    }

    val json: JSONObject
        get() = data ?: load { JSONObject() }

    /** Marks the store dirty and schedules a flush on the IO dispatcher if enough time has passed. */
    fun touch(minIntervalMs: Long = 5_000L) {
        synchronized(lock) {
            dirty = true
            val now = System.currentTimeMillis()
            if (now - lastFlush >= minIntervalMs) {
                ioScope.launch { synchronized(lock) { flushLocked() } }
            }
        }
    }

    fun flush() {
        synchronized(lock) { flushLocked() }
    }

    private fun flushLocked() {
        val d = data ?: return
        if (!dirty) return
        try {
            file.parentFile?.mkdirs()
            // Write to a temp file and rename so a crash mid-write cannot leave a
            // half-written, unparseable baseline behind.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(d.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(d.toString())
                tmp.delete()
            }
            dirty = false
            lastFlush = System.currentTimeMillis()
        } catch (_: Exception) {
            // A failed write is not worth crashing the scanner over; the next
            // touch() will try again.
        }
    }
}
