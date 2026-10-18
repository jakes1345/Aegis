package com.xat.aegis.nav

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * A planned road work or closure from the state DOT feeds (WZDx / CWZ), as the workzones
 * aggregator publishes it. [geom] is in travel order; times are epoch seconds.
 */
data class WorkZone(
    val id: String,
    val type: String,
    val impact: String,
    val speedKph: Double?,
    val road: String?,
    val direction: String?,
    val description: String?,
    val start: Long?,
    val end: Long?,
    val geom: List<LatLon>,
    val source: String,
) {
    fun isActive(nowSec: Long): Boolean = (start == null || start <= nowSec) && (end == null || end > nowSec)

    companion object {
        const val CLOSURE = "closure"
        const val LANE_CLOSURE = "lane_closure"
        const val WORK_ZONE = "work_zone"
        const val DETOUR = "detour"
        const val RESTRICTION = "restriction"
        val TYPES = setOf(CLOSURE, LANE_CLOSURE, WORK_ZONE, DETOUR, RESTRICTION)

        const val IMPACT_ALL_CLOSED = "all_closed"
        const val IMPACT_SOME_CLOSED = "some_closed"
        const val IMPACT_SHOULDER = "shoulder"
        const val IMPACT_OPEN = "open"
        const val IMPACT_UNKNOWN = "unknown"
        val IMPACTS = setOf(IMPACT_ALL_CLOSED, IMPACT_SOME_CLOSED, IMPACT_SHOULDER, IMPACT_OPEN, IMPACT_UNKNOWN)

        /** Parses one aggregator item; null when it lacks an id or a usable geometry. */
        fun fromJson(o: JSONObject): WorkZone? {
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val arr = o.optJSONArray("geom") ?: return null
            val geom = ArrayList<LatLon>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                if (p.length() < 2) continue
                val lat = p.optDouble(0, Double.NaN)
                val lon = p.optDouble(1, Double.NaN)
                if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                geom.add(LatLon(lat, lon))
            }
            if (geom.isEmpty()) return null
            val type = o.optString("type").takeIf { it in TYPES } ?: WORK_ZONE
            val impact = o.optString("impact").takeIf { it in IMPACTS } ?: IMPACT_UNKNOWN
            return WorkZone(
                id = id,
                type = type,
                impact = impact,
                speedKph = optPositiveDouble(o, "speed_kph"),
                road = optText(o, "road"),
                direction = optText(o, "direction"),
                description = optText(o, "description"),
                start = optEpoch(o, "start"),
                end = optEpoch(o, "end"),
                geom = geom,
                source = optText(o, "source") ?: "State DOT",
            )
        }

        private fun optText(o: JSONObject, key: String): String? =
            if (o.isNull(key)) null else o.optString(key).trim().takeIf { it.isNotEmpty() }

        private fun optEpoch(o: JSONObject, key: String): Long? {
            if (o.isNull(key)) return null
            val v = o.optDouble(key, Double.NaN)
            return if (v.isNaN() || v <= 0) null else v.toLong()
        }

        private fun optPositiveDouble(o: JSONObject, key: String): Double? {
            if (o.isNull(key)) return null
            val v = o.optDouble(key, Double.NaN)
            return if (v.isNaN() || v <= 0) null else v
        }
    }
}

/**
 * Downloads the work zones for one map region from the workzones release (rebuilt every 15
 * minutes by GitHub Actions), with conditional requests so an unchanged file costs one round
 * trip. The last good file is kept in filesDir/nav/ so zones are known offline.
 */
class WorkZoneFeed(context: Context, val slug: String) {

    companion object {
        private const val TAG = "WorkZoneFeed"
        private const val RELEASE_BASE = "https://github.com/jakes1345/Aegis/releases/download/workzones"
        const val REFRESH_INTERVAL_MS = 15 * 60_000L
        const val MAX_BYTES = 10L * 1024 * 1024
        private const val FORMAT_VERSION = 1

        fun url(slug: String): String = "$RELEASE_BASE/workzones-$slug.json.gz"

        /** Decompresses [bytes] when they are gzip (else returns them as they are), refusing more than [cap] bytes of output. */
        fun gunzipCapped(bytes: ByteArray, cap: Long = MAX_BYTES): ByteArray {
            val gz = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
            if (!gz) {
                if (bytes.size > cap) throw IOException("Work zone file is larger than ${cap / (1024 * 1024)} MiB")
                return bytes
            }
            return GZIPInputStream(ByteArrayInputStream(bytes), 64 * 1024).use { readCapped(it, cap) }
        }

        fun readCapped(input: InputStream, cap: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > cap) throw IOException("Work zone file is larger than ${cap / (1024 * 1024)} MiB")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        /**
         * Parses an aggregator file and keeps the zones that have not ended by [nowSec]. Throws
         * [JSONException] when the file is not one; a file for another region is rejected too.
         */
        fun parse(text: String, nowSec: Long, expectSlug: String? = null): List<WorkZone> {
            val root = JSONObject(text)
            val version = root.optInt("version", -1)
            if (version != FORMAT_VERSION) throw JSONException("Unsupported work zone file version $version")
            if (expectSlug != null) {
                val s = root.optString("slug")
                if (s.isNotEmpty() && s != expectSlug) throw JSONException("Work zone file is for $s, not $expectSlug")
            }
            val items = root.optJSONArray("items") ?: JSONArray()
            val out = ArrayList<WorkZone>(items.length())
            val seen = HashSet<String>()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val z = WorkZone.fromJson(o) ?: continue
                if (z.end != null && z.end <= nowSec) continue
                if (seen.add(z.id)) out.add(z)
            }
            return out
        }
    }

    private val dir = File(context.applicationContext.filesDir, "nav")
    private val cacheFile = File(dir, "workzones-$slug.json.gz")
    private val metaFile = File(dir, "workzones-$slug.meta.json")

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true) // release assets redirect to GitHub's object storage
        .followSslRedirects(false)
        .build()

    private val lock = Mutex()
    @Volatile private var loaded = false
    @Volatile private var lastAttemptMs = 0L

    private val _zones = MutableStateFlow<List<WorkZone>>(emptyList())
    /** Work zones not yet ended, from the newest file this phone has. */
    val zones: StateFlow<List<WorkZone>> = _zones.asStateFlow()

    /** Refreshes when the last attempt was more than [maxAgeMs] ago. */
    suspend fun refreshIfStale(maxAgeMs: Long = REFRESH_INTERVAL_MS): Boolean {
        if (System.currentTimeMillis() - lastAttemptMs < maxAgeMs && loaded) return true
        return refresh()
    }

    /**
     * Loads the cached file on first use, then asks the release for a newer one. Returns false
     * when the download failed; the cached zones stay in place then.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded) {
                loadCache()
                loaded = true
            }
            lastAttemptMs = System.currentTimeMillis()
            try {
                download()
                true
            } catch (e: IOException) {
                Log.i(TAG, "Work zones for $slug unavailable: ${e.message}")
                pruneEnded()
                false
            } catch (e: JSONException) {
                Log.w(TAG, "Malformed work zone file for $slug: ${e.message}")
                pruneEnded()
                false
            }
        }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private fun pruneEnded() {
        val now = nowSec()
        val cur = _zones.value
        val kept = cur.filter { it.end == null || it.end > now }
        if (kept.size != cur.size) _zones.value = kept
    }

    private fun download() {
        val meta = readMeta()
        val builder = Request.Builder().url(url(slug)).get()
        // Validators only make sense while the file they describe is still on disk.
        if (cacheFile.isFile) {
            meta?.optString("etag")?.takeIf { it.isNotEmpty() }?.let { builder.header("If-None-Match", it) }
            meta?.optString("lastModified")?.takeIf { it.isNotEmpty() }?.let { builder.header("If-Modified-Since", it) }
        }
        http.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 304) {
                pruneEnded()
                return
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response")
            val declared = body.contentLength()
            if (declared > MAX_BYTES) throw IOException("Work zone file is larger than ${MAX_BYTES / (1024 * 1024)} MiB")
            val raw = body.byteStream().use { readCapped(it, MAX_BYTES) }
            val text = String(gunzipCapped(raw), Charsets.UTF_8)
            val zones = parse(text, nowSec(), slug)
            writeAtomically(cacheFile, raw)
            val m = JSONObject()
            resp.header("ETag")?.let { m.put("etag", it) }
            resp.header("Last-Modified")?.let { m.put("lastModified", it) }
            writeAtomically(metaFile, m.toString().toByteArray(Charsets.UTF_8))
            _zones.value = zones
        }
    }

    private fun readMeta(): JSONObject? = try {
        if (metaFile.isFile) JSONObject(metaFile.readText()) else null
    } catch (e: IOException) {
        null
    } catch (e: JSONException) {
        metaFile.delete()
        null
    }

    private fun loadCache() {
        if (!cacheFile.isFile) return
        try {
            val raw = cacheFile.inputStream().use { readCapped(it, MAX_BYTES) }
            _zones.value = parse(String(gunzipCapped(raw), Charsets.UTF_8), nowSec(), slug)
        } catch (e: IOException) {
            Log.w(TAG, "Work zone cache unreadable: ${e.message}")
        } catch (e: JSONException) {
            Log.w(TAG, "Work zone cache corrupt, discarding: ${e.message}")
            cacheFile.delete()
            metaFile.delete()
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        try {
            dir.mkdirs()
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Could not replace ${target.name}")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not save ${target.name}: ${e.message}")
        }
    }
}
