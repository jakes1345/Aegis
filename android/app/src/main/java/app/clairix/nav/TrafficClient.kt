package app.clairix.nav

import android.content.Context
import android.util.Log
import app.clairix.comms.CommsRepository
import app.clairix.comms.RelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A traffic incident as the relay reports it (spec section 4). Times are epoch seconds. */
data class Incident(
    val id: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val seg: String?,
    val source: String,
    val confirmations: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val description: String?,
) {
    companion object {
        const val POLICE = "police"
        const val CRASH = "crash"
        const val HAZARD = "hazard"
        const val CLOSURE = "closure"
        const val JAM = "jam"
        val TYPES = listOf(POLICE, CRASH, HAZARD, CLOSURE, JAM)

        fun fromJson(o: JSONObject): Incident = Incident(
            id = o.getString("id"),
            type = o.getString("type"),
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            seg = if (o.isNull("seg")) null else o.optString("seg").takeIf { it.isNotEmpty() },
            source = o.optString("source", "user"),
            confirmations = o.optInt("confirmations", 1),
            createdAt = o.optLong("createdAt", 0L),
            expiresAt = o.optLong("expiresAt", 0L),
            description = if (o.isNull("description")) null else o.optString("description").takeIf { it.isNotEmpty() },
        )
    }
}

/**
 * The relay client COMMS already uses, or null when this phone is not registered with a relay;
 * traffic then is simply unavailable. Same path as CommsService: init is idempotent.
 */
internal fun navRelay(context: Context): RelayClient? = try {
    CommsRepository.init(context.applicationContext)
    if (CommsRepository.isRegistered()) CommsRepository.relayClient() else null
} catch (e: RuntimeException) {
    Log.w("Nav", "COMMS unavailable for traffic: ${e.message}")
    null
}

/**
 * Crowd-sourced traffic for one map region: live segment speeds (refreshed every minute while
 * navigating), hour-of-week typical speeds (cached on disk for a week) and incident reports.
 *
 * [liveKph] and [typicalKph] are called from the router's inner loop, so they only read
 * in-memory maps and never touch the network or disk.
 */
class TrafficClient(context: Context, val region: String) : SpeedSource {

    private companion object {
        const val TAG = "TrafficClient"
        const val LIVE_INTERVAL_MS = 60_000L
        /** The relay's live window is 10 min; past half that without a refresh our copy is too old to trust. */
        const val LIVE_STALE_MS = 5 * 60_000L
        const val TYPICAL_FRESH_SEC = 7L * 24 * 3600
        const val HOURS_PER_WEEK = 168
    }

    private val appContext = context.applicationContext
    private val cacheFile = File(File(appContext.filesDir, "nav"), "typical-$region.json")

    private val live = ConcurrentHashMap<String, Float>()
    @Volatile private var liveFetchedAtMs = 0L

    /** Hour-of-week km/h per segment; NaN where the relay has no data for that hour. */
    private val typical = ConcurrentHashMap<String, FloatArray>()
    /** When each segment's profile was fetched (epoch seconds), including segments the relay knew nothing about. */
    private val typicalFetchedAt = ConcurrentHashMap<String, Long>()
    private val typicalLock = Mutex()
    @Volatile private var typicalLoaded = false

    private val _incidents = MutableStateFlow<List<Incident>>(emptyList())
    val incidents: StateFlow<List<Incident>> = _incidents.asStateFlow()

    private val _available = MutableStateFlow(false)
    /** True once the relay has answered a traffic request; false without COMMS or while offline. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private var liveJob: Job? = null

    // ── SpeedSource ─────────────────────────────────────────────────────

    override fun liveKph(segmentKey: String): Double? {
        if (System.currentTimeMillis() - liveFetchedAtMs > LIVE_STALE_MS) return null
        return live[segmentKey]?.toDouble()
    }

    override fun typicalKph(segmentKey: String, epochSec: Long): Double? {
        val h = typical[segmentKey] ?: return null
        val v = h[hourOfWeek(epochSec)]
        return if (v.isNaN()) null else v.toDouble()
    }

    /** Index into the relay's 168-hour profile: UTC, Monday 00:00 = 0. */
    private fun hourOfWeek(epochSec: Long): Int {
        val days = Math.floorDiv(epochSec, 86_400L)
        // 1970-01-01 was a Thursday, which is day 3 counting Monday as 0.
        val dow = Math.floorMod(days + 3, 7L).toInt()
        val hour = (Math.floorMod(epochSec, 86_400L) / 3600).toInt()
        return dow * 24 + hour
    }

    // ── Live speeds and incidents ───────────────────────────────────────

    /** Refreshes live speeds and incidents every minute on [scope] until [stopLive]. */
    @Synchronized
    fun startLive(scope: CoroutineScope) {
        if (liveJob?.isActive == true) return
        liveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshLive()
                delay(LIVE_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
    }

    /** One fetch of live speeds and incidents. Returns false when the relay could not be reached. */
    suspend fun refreshLive(): Boolean = withContext(Dispatchers.IO) {
        val relay = navRelay(appContext) ?: run { _available.value = false; return@withContext false }
        try {
            val json = relay.trafficLive(region)
            val segs = json.optJSONObject("segments") ?: JSONObject()
            val fresh = HashMap<String, Float>(segs.length() * 2)
            val keys = segs.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val kph = segs.optJSONObject(k)?.optDouble("kph", Double.NaN) ?: Double.NaN
                if (!kph.isNaN() && kph >= 0) fresh[k] = kph.toFloat()
            }
            live.keys.retainAll(fresh.keys)
            live.putAll(fresh)
            liveFetchedAtMs = System.currentTimeMillis()
            json.optJSONArray("incidents")?.let { _incidents.value = parseIncidents(it) }
            _available.value = true
            true
        } catch (e: IOException) {
            Log.i(TAG, "Live traffic unavailable: ${e.message}")
            _available.value = false
            pruneExpired()
            false
        } catch (e: JSONException) {
            Log.w(TAG, "Malformed live traffic: ${e.message}")
            false
        }
    }

    private fun parseIncidents(arr: JSONArray): List<Incident> {
        val now = System.currentTimeMillis() / 1000
        val out = ArrayList<Incident>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val inc = try { Incident.fromJson(o) } catch (e: JSONException) { continue }
            if (inc.expiresAt == 0L || inc.expiresAt > now) out.add(inc)
        }
        return out
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis() / 1000
        _incidents.value = _incidents.value.filter { it.expiresAt == 0L || it.expiresAt > now }
    }

    private fun upsertIncident(inc: Incident) {
        _incidents.value = _incidents.value.filter { it.id != inc.id } + inc
    }

    /** Reports an incident at a position, optionally tied to the segment being driven. */
    suspend fun report(type: String, lat: Double, lon: Double, seg: String?): Result<Incident> = withContext(Dispatchers.IO) {
        require(type in Incident.TYPES) { "Unknown incident type $type" }
        val relay = navRelay(appContext) ?: return@withContext Result.failure(IOException("COMMS is not set up, so reports cannot be sent"))
        runCatching {
            val json = relay.trafficReport(region, type, lat, lon, seg)
            val inc = try {
                Incident.fromJson(json.getJSONObject("incident"))
            } catch (e: JSONException) {
                throw IOException("The relay returned an unexpected report: ${e.message}")
            }
            upsertIncident(inc)
            inc
        }
    }

    /** Says whether an incident is [still] there; the result is null once the relay has removed it. */
    suspend fun vote(id: String, still: Boolean): Result<Incident?> = withContext(Dispatchers.IO) {
        val relay = navRelay(appContext) ?: return@withContext Result.failure(IOException("COMMS is not set up, so votes cannot be sent"))
        runCatching {
            val json = relay.trafficVote(region, id, still)
            val inc = json.optJSONObject("incident")?.let {
                try { Incident.fromJson(it) } catch (e: JSONException) { throw IOException("The relay returned an unexpected incident: ${e.message}") }
            }
            if (inc == null) _incidents.value = _incidents.value.filter { it.id != id } else upsertIncident(inc)
            inc
        }
    }

    // ── Typical speeds ──────────────────────────────────────────────────

    /**
     * Makes sure typical profiles for [segs] are in memory: loads the disk cache once, then fetches
     * segments missing or older than a week from the relay in batches of 500 and saves the cache.
     */
    suspend fun ensureTypical(segs: Collection<String>) = withContext(Dispatchers.IO) {
        typicalLock.withLock {
            if (!typicalLoaded) {
                loadTypicalCache()
                typicalLoaded = true
            }
            val now = System.currentTimeMillis() / 1000
            val wanted = LinkedHashSet<String>()
            for (s in segs) {
                val at = typicalFetchedAt[s]
                if (at == null || now - at > TYPICAL_FRESH_SEC) wanted.add(s)
            }
            if (wanted.isEmpty()) return@withLock
            val relay = navRelay(appContext) ?: return@withLock
            var changed = false
            for (batch in wanted.chunked(RelayClient.TRAFFIC_TYPICAL_MAX)) {
                val json = try {
                    relay.trafficTypical(region, batch)
                } catch (e: IOException) {
                    Log.i(TAG, "Typical traffic unavailable: ${e.message}")
                    break
                }
                val got = json.optJSONObject("segments") ?: JSONObject()
                for (s in batch) {
                    val h = got.optJSONObject(s)?.optJSONArray("h")
                    val profile = h?.let { parseProfile(it) }
                    if (profile != null) typical[s] = profile else typical.remove(s)
                    typicalFetchedAt[s] = now
                }
                changed = true
            }
            if (changed) saveTypicalCache(now)
        }
    }

    private fun parseProfile(h: JSONArray): FloatArray? {
        if (h.length() != HOURS_PER_WEEK) return null
        var any = false
        val out = FloatArray(HOURS_PER_WEEK) { i ->
            val v = if (h.isNull(i)) Double.NaN else h.optDouble(i, Double.NaN)
            if (!v.isNaN() && v > 0) { any = true; v.toFloat() } else Float.NaN
        }
        return if (any) out else null
    }

    private fun loadTypicalCache() {
        if (!cacheFile.isFile) return
        try {
            val root = JSONObject(cacheFile.readText())
            val now = System.currentTimeMillis() / 1000
            val segs = root.optJSONObject("segments") ?: return
            val keys = segs.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = segs.optJSONObject(k) ?: continue
                val t = o.optLong("t", 0L)
                if (now - t > TYPICAL_FRESH_SEC) continue
                typicalFetchedAt[k] = t
                o.optJSONArray("h")?.let { parseProfile(it) }?.let { typical[k] = it }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Typical cache unreadable: ${e.message}")
        } catch (e: JSONException) {
            Log.w(TAG, "Typical cache corrupt, discarding: ${e.message}")
            cacheFile.delete()
        }
    }

    private fun saveTypicalCache(now: Long) {
        val segs = JSONObject()
        for ((k, t) in typicalFetchedAt) {
            if (now - t > TYPICAL_FRESH_SEC) continue
            val o = JSONObject().put("t", t)
            val h = typical[k]
            if (h != null) {
                val arr = JSONArray()
                for (v in h) arr.put(if (v.isNaN()) JSONObject.NULL else v.toDouble())
                o.put("h", arr)
            } else {
                o.put("h", JSONObject.NULL)
            }
            segs.put(k, o)
        }
        val text = JSONObject().put("region", region).put("savedAt", now).put("segments", segs).toString()
        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.delete()
                if (!tmp.renameTo(cacheFile)) throw IOException("Could not replace ${cacheFile.name}")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not save typical cache: ${e.message}")
        }
    }
}
