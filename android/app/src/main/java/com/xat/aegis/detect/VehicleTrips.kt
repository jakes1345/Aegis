package com.xat.aegis.detect

import com.xat.aegis.Severity
import com.xat.aegis.Store
import com.xat.aegis.TrackerType
import com.xat.aegis.analysis.Fix
import com.xat.aegis.analysis.haversine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers which Bluetooth devices rode along on each car trip, and notices one
 * that keeps coming back.
 *
 * The persistence test — "with you for ten minutes across three hundred metres" —
 * catches a tracker on the first drive. What it cannot catch is one that only wakes
 * up to advertise now and then, or a day when the drive was short. Trips are a
 * natural unit for that: a thing in your own car is there every time, a thing in
 * the car next to you at the lights is there once. A device that qualifies on three
 * separate trips is in the car.
 *
 * Two keys are kept per device. The address, which is conclusive when it matches —
 * a GPS box, a Tile, most cheap trackers keep one — and, for identified tracker
 * types only, the advertisement fingerprint, which survives the address rotation of
 * an AirTag or SmartTag but cannot tell two AirTags apart. The second is therefore
 * reported a notch lower and phrased as "an AirTag", not "the same AirTag".
 *
 * Trips are persisted through [Store] so the count survives the service being
 * restarted between drives, which it always is.
 */
class VehicleTrips(private val store: Store) {

    /**
     * One past trip: when, where it set off from (when a fix was in hand), and what
     * qualified on it. The start point is what separates three drives from three
     * places from three drives out of the same car park.
     */
    data class Trip(
        val start: Long, val end: Long, val addresses: Set<String>, val signatures: Set<String>,
        val startLat: Double? = null, val startLon: Double? = null
    )

    /** A device that has now qualified on [TRIPS_TO_ALERT] trips. */
    data class Finding(
        val id: String,
        val severity: Severity,
        val title: String,
        val detail: String,
        val dedupeKey: String
    )

    private class Seen(val address: String) {
        var first = 0L
        var last = 0L
        var count = 0
        var name = ""
        var tracker: TrackerType? = null
        var fingerprint: String? = null
        var qualified = false
        var reported = false
    }

    private val lock = Any()
    private var tripStart = 0L
    private var tripStartLat: Double? = null
    private var tripStartLon: Double? = null
    private val seen = HashMap<String, Seen>()
    private val history = ArrayList<Trip>()

    init {
        synchronized(lock) { history.addAll(loadHistory()) }
    }

    val inTrip: Boolean get() = synchronized(lock) { tripStart != 0L }

    /** Trips remembered so far, oldest first. */
    fun trips(): List<Trip> = synchronized(lock) { ArrayList(history) }

    fun beginTrip(now: Long, startLat: Double? = null, startLon: Double? = null) {
        synchronized(lock) {
            if (tripStart != 0L) return
            tripStart = now
            tripStartLat = startLat
            tripStartLon = startLon
            seen.clear()
        }
    }

    /**
     * Earlier trips matching [has], counted so that trips setting off within
     * [DISTINCT_START_M] of each other — or of the current trip — count once. A trip
     * with no recorded start cannot be told apart from any other and counts on its own.
     */
    private fun earlierTrips(has: (Trip) -> Boolean): Int {
        val starts = ArrayList<Pair<Double, Double>>()
        val lat0 = tripStartLat
        val lon0 = tripStartLon
        if (lat0 != null && lon0 != null) starts.add(lat0 to lon0)
        var n = 0
        for (t in history) {
            if (!has(t)) continue
            val lat = t.startLat
            val lon = t.startLon
            if (lat == null || lon == null) { n++; continue }
            if (starts.any { haversine(Fix(it.first, it.second, null, 0L), Fix(lat, lon, null, 0L)) < DISTINCT_START_M }) continue
            starts.add(lat to lon)
            n++
        }
        return n
    }

    /**
     * Records a sighting during the current trip. Returns a [Finding] the first time
     * this device qualifies for the trip and has already qualified on enough earlier
     * trips to make the total [TRIPS_TO_ALERT]; null in every other case, including
     * when no trip is under way.
     */
    fun sight(
        key: String,
        address: String,
        name: String,
        tracker: TrackerType?,
        fingerprint: String?,
        now: Long
    ): Finding? {
        synchronized(lock) {
            if (tripStart == 0L) return null
            val entry = seen.getOrPut(key) {
                if (seen.size >= MAX_SEEN) return null
                Seen(address).apply { first = now }
            }
            entry.last = now
            entry.count++
            entry.name = name
            if (tracker != null) entry.tracker = tracker
            if (fingerprint != null) entry.fingerprint = fingerprint

            if (entry.qualified) return null
            if (entry.count < MIN_SIGHTINGS || entry.last - entry.first < MIN_SPAN_MS) return null
            entry.qualified = true

            val byAddress = earlierTrips { it.addresses.contains(entry.address) }
            if (byAddress + 1 >= TRIPS_TO_ALERT) {
                entry.reported = true
                return Finding(
                    id = "vehicle@${entry.address}@$now",
                    severity = Severity.CRITICAL,
                    title = "${entry.name} has been in your vehicle on ${byAddress + 1} trips",
                    detail = "Same Bluetooth address (${entry.address}) present for the length of " +
                        "${byAddress + 1} separate drives. Check the OBD-II port, under the seats, " +
                        "the bumper covers and the wheel wells.",
                    dedupeKey = "vehicle@${entry.address}"
                )
            }
            val signature = signatureOf(entry) ?: return null
            val bySignature = earlierTrips { it.signatures.contains(signature) }
            if (bySignature + 1 >= TRIPS_TO_ALERT) {
                entry.reported = true
                val label = entry.tracker?.label ?: entry.name
                return Finding(
                    id = "vehicle@$signature@$now",
                    severity = Severity.HIGH,
                    title = "A $label has ridden along on ${bySignature + 1} trips",
                    detail = "A tracker of this type and advertisement shape was present for the length of " +
                        "${bySignature + 1} separate drives. It rotates its address, so this could be two " +
                        "different ones — or the same one each time. Search the vehicle.",
                    dedupeKey = "vehicle@$signature"
                )
            }
            return null
        }
    }

    /**
     * Closes the trip, stores what qualified, and returns any device that has now
     * reached [TRIPS_TO_ALERT] and was not already reported mid-trip. A trip shorter
     * than [MIN_TRIP_MS] is a false transition — reversing out of a parking space —
     * and is discarded.
     */
    fun endTrip(now: Long): List<Finding> {
        synchronized(lock) {
            val start = tripStart
            if (start == 0L) return emptyList()
            tripStart = 0L
            val startLat = tripStartLat
            val startLon = tripStartLon
            if (now - start < MIN_TRIP_MS) { seen.clear(); tripStartLat = null; tripStartLon = null; return emptyList() }

            val addresses = HashSet<String>()
            val signatures = HashSet<String>()
            val findings = ArrayList<Finding>()
            for (entry in seen.values) {
                if (!entry.qualified) continue
                addresses.add(entry.address)
                signatureOf(entry)?.let(signatures::add)
                if (entry.reported) continue
                val byAddress = earlierTrips { it.addresses.contains(entry.address) } + 1
                if (byAddress >= TRIPS_TO_ALERT) {
                    findings.add(Finding(
                        id = "vehicle@${entry.address}@$now", severity = Severity.CRITICAL,
                        title = "${entry.name} has been in your vehicle on $byAddress trips",
                        detail = "Same Bluetooth address (${entry.address}) present for the length of $byAddress separate drives.",
                        dedupeKey = "vehicle@${entry.address}"
                    ))
                }
            }
            history.add(Trip(start, now, addresses, signatures, startLat, startLon))
            while (history.size > MAX_TRIPS) history.removeAt(0)
            seen.clear()
            tripStartLat = null
            tripStartLon = null
            saveHistory()
            return findings
        }
    }

    fun clear() {
        synchronized(lock) {
            tripStart = 0L
            tripStartLat = null
            tripStartLon = null
            seen.clear()
            history.clear()
            store.replace(JSONObject())
        }
    }

    /** "airtag|3f9a…" for an identified tracker; generic devices have no rotation-proof signature. */
    private fun signatureOf(entry: Seen): String? {
        val tracker = entry.tracker ?: return null
        val fingerprint = entry.fingerprint ?: return null
        return "${tracker.id}|$fingerprint"
    }

    private fun loadHistory(): List<Trip> {
        val root = store.load { JSONObject() }
        val trips = root.optJSONArray("trips") ?: return emptyList()
        val out = ArrayList<Trip>(trips.length())
        for (i in 0 until trips.length()) {
            val t = trips.optJSONObject(i) ?: continue
            out.add(Trip(
                start = t.optLong("start"), end = t.optLong("end"),
                addresses = strings(t.optJSONArray("addresses")),
                signatures = strings(t.optJSONArray("signatures")),
                startLat = if (t.has("startLat")) t.optDouble("startLat") else null,
                startLon = if (t.has("startLon")) t.optDouble("startLon") else null
            ))
        }
        return out
    }

    private fun saveHistory() {
        val trips = JSONArray()
        for (t in history) {
            trips.put(JSONObject().apply {
                put("start", t.start)
                put("end", t.end)
                put("addresses", JSONArray(t.addresses.toList()))
                put("signatures", JSONArray(t.signatures.toList()))
                t.startLat?.let { put("startLat", it) }
                t.startLon?.let { put("startLon", it) }
            })
        }
        store.replace(JSONObject().put("trips", trips))
    }

    private fun strings(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val out = HashSet<String>(array.length())
        for (i in 0 until array.length()) array.optString(i, null)?.let(out::add)
        return out
    }

    companion object {
        /** How many trips a device must ride along on before it is reported. */
        const val TRIPS_TO_ALERT = 3
        /** Trips remembered; older ones fall off. */
        const val MAX_TRIPS = 10
        /** A trip shorter than this is a false start. */
        const val MIN_TRIP_MS = 2 * 60_000L
        /** To count for a trip a device must be heard this often… */
        const val MIN_SIGHTINGS = 3
        /** …over at least this long, so a car alongside for one light does not qualify. */
        const val MIN_SPAN_MS = 90_000L
        /** Trips setting off closer together than this count as the same start point. */
        const val DISTINCT_START_M = 500.0
        private const val MAX_SEEN = 2000
    }
}
