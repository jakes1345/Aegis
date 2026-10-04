package com.xat.aegis.analysis

import android.content.Context
import com.xat.aegis.Store
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

// ── Storage ───────────────────────────────────────────────────────────────────

/**
 * The GPS log the route analysis reads. The scanner used to keep only the last
 * 500 fixes in memory, so nothing survived a restart; this is the on-disk record,
 * a [Store]-backed JSON file:
 *
 *   {"fixes": [[ts, lat, lon, accuracy], …]}   oldest first, at most [CAP] rows
 *
 * [ScanService] records each fix it receives; a fix is kept when the phone moved
 * at least 25 m or 30 s passed, and coarse fixes (worse than 100 m) are dropped.
 * At the scanner's 15 s cadence while moving that is a few hundred rows a day, so
 * the cap holds several weeks. Never leaves the phone; cleared with everything else.
 */
object LocationHistory {

    private const val FILE = "location_log.json"
    private const val KEY = "fixes"
    const val CAP = 30_000
    private const val MIN_INTERVAL_MS = 30_000L
    private const val MIN_MOVE_M = 25.0
    private const val MAX_ACCURACY_M = 100f

    private val lock = Any()
    @Volatile private var store: Store? = null
    private var last: Fix? = null

    private fun store(context: Context): Store =
        store ?: synchronized(lock) { store ?: Store(context.applicationContext, FILE).also { store = it } }

    fun record(context: Context, fix: Fix) {
        if (fix.accuracy != null && fix.accuracy > MAX_ACCURACY_M) return
        synchronized(lock) {
            val prev = last
            if (prev != null && fix.time - prev.time < MIN_INTERVAL_MS && haversine(prev, fix) < MIN_MOVE_M) return
            last = fix
            val s = store(context)
            val root = s.json
            var arr = root.optJSONArray(KEY) ?: JSONArray().also { root.put(KEY, it) }
            arr.put(JSONArray().put(fix.time).put(fix.lat).put(fix.lon).put((fix.accuracy ?: -1f).toDouble()))
            if (arr.length() > CAP + CAP / 10) {
                // Trim in one go rather than removing the head row by row on every fix.
                val trimmed = JSONArray()
                for (i in arr.length() - CAP until arr.length()) trimmed.put(arr.get(i))
                arr = trimmed
                root.put(KEY, arr)
            }
            s.touch(60_000L)
        }
    }

    /** Every stored fix, oldest first. File I/O; call off the main thread. */
    fun load(context: Context): List<Fix> {
        val arr = store(context).json.optJSONArray(KEY) ?: return emptyList()
        val out = ArrayList<Fix>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            if (row.length() < 3) continue
            val acc = if (row.length() > 3) row.optDouble(3, -1.0).toFloat().takeIf { it >= 0f } else null
            out += Fix(row.optDouble(1), row.optDouble(2), null, row.optLong(0), acc)
        }
        out.sortBy { it.time }
        return out
    }

    fun size(context: Context): Int = store(context).json.optJSONArray(KEY)?.length() ?: 0

    fun clear(context: Context) {
        synchronized(lock) {
            last = null
            store(context).replace(JSONObject().put(KEY, JSONArray()))
        }
    }

    fun flush() { store?.flush() }
}

// ── Report types ──────────────────────────────────────────────────────────────

/** When the owner usually leaves a place on one weekday. Minutes are of the local day. */
data class DepartureStats(
    val weekday: DayOfWeek,
    val meanMinuteOfDay: Int,
    val stdMinutes: Float,
    val samples: Int
) {
    val label: String get() = "%s %s ± %d min".format(Locale.US, weekday.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, clock(meanMinuteOfDay), stdMinutes.toInt())
}

/** A place the owner stops at, found from the fixes alone. */
data class LocationCluster(
    val id: Int,
    /** "Home", "Work" or "Place n". */
    val label: String,
    val lat: Double,
    val lon: Double,
    /** Separate stays here. */
    val visits: Int,
    /** Distinct local dates with a stay here. */
    val days: Int,
    val dwellMinutes: Long,
    val firstSeen: Long,
    val lastSeen: Long,
    /** Per weekday, where there were at least two departures to measure. */
    val departures: List<DepartureStats>
)

data class PatternReport(
    val clusters: List<LocationCluster>,
    /**
     * Width in minutes of the window in which 80% of weekday departures from home
     * fall (10th to 90th percentile); 0 when there are too few to say.
     */
    val weekdayDepartureWindowMin: Int,
    /** Percent (0–100) of repeated trips between the same two places that followed the usual route. */
    val routeRepetitionPct: Float,
    /** 0.0 (unpredictable) to 1.0 (clockwork). */
    val predictabilityScore: Float,
    val suggestions: List<String>,
    val fixes: Int = 0,
    val daysCovered: Int = 0,
    val trips: Int = 0
)

// ── Analysis ──────────────────────────────────────────────────────────────────

/**
 * Pattern-of-life analysis: how predictable the owner is to someone watching.
 * Everything runs on the phone from [LocationHistory]; nothing is sent anywhere.
 *
 * Stops are found with a space–time DBSCAN (50 m by Haversine, three or more
 * fixes within ten minutes of each other), so each stop is one stay; stays whose
 * centres fall within 75 m are the same place. The most-dwelt place is home, the
 * next is work. For each place the departure times per weekday are averaged, and
 * trips between places are compared by the 100 m grid cells their paths cross,
 * which stands in for road segments without a map.
 */
object PatternOfLife {

    private const val EPS_M = 50.0
    private const val MIN_PTS = 3
    private const val WINDOW_MS = 10 * 60_000L
    private const val MERGE_M = 75.0
    /** A dwell shorter than this is a traffic light, not a stop worth a departure time. */
    private const val MIN_STAY_MS = 15 * 60_000L
    /** Fixes stop arriving while the phone sits still; a gap this long at one spot is a stay, filled in at this cadence. */
    private const val DENSIFY_GAP_MS = 5 * 60_000L
    private const val DENSIFY_STEP_MS = 5 * 60_000L
    private const val RESAMPLE_M = 50.0
    private const val CELL_M = 100.0
    private const val SAME_ROUTE_JACCARD = 0.6
    private const val MAX_TRIPS_PER_PAIR = 40

    /** Loads the history and analyses it. Seconds of CPU on a long log; call off the main thread. */
    fun analyze(context: Context): PatternReport = analyze(LocationHistory.load(context))

    fun analyze(raw: List<Fix>): PatternReport {
        val zone = ZoneId.systemDefault()
        val fixes = densify(raw.sortedBy { it.time })
        if (fixes.size < MIN_PTS) {
            return PatternReport(emptyList(), 0, 0f, 0f, listOf("Not enough location history yet. Keep the scanner running for a week or two."), raw.size, 0, 0)
        }
        val daysCovered = fixes.map { LocalDate.ofInstant(Instant.ofEpochMilli(it.time), zone) }.toSet().size

        // 1. Stays.
        val labels = dbscan(fixes)
        val stays = buildStays(fixes, labels).filter { it.end - it.start >= MIN_STAY_MS }.sortedBy { it.start }

        // 2. Places.
        val places = mergeStays(stays)
        places.sortByDescending { it.dwellMs }
        val clusters = places.mapIndexed { i, p ->
            val label = when {
                i == 0 -> "Home"
                i == 1 && p.stays.size >= 3 -> "Work"
                else -> "Place ${i + 1}"
            }
            LocationCluster(
                id = i, label = label, lat = p.lat, lon = p.lon,
                visits = p.stays.size,
                days = p.stays.map { LocalDate.ofInstant(Instant.ofEpochMilli(it.start), zone) }.toSet().size,
                dwellMinutes = p.dwellMs / 60_000L,
                firstSeen = p.stays.minOf { it.start }, lastSeen = p.stays.maxOf { it.end },
                departures = departureStats(p.stays, zone)
            )
        }

        // 3. Weekday departure window from home.
        val home = places.firstOrNull()
        val homeWeekdayDepartures = home?.let { firstWeekdayDepartures(it.stays, zone) } ?: emptyMap()
        val depMinutes = homeWeekdayDepartures.values.sorted()
        val windowMin: Int
        val windowLo: Int
        val windowHi: Int
        if (depMinutes.size >= 3) {
            windowLo = percentile(depMinutes, 0.10)
            windowHi = percentile(depMinutes, 0.90)
            windowMin = (windowHi - windowLo).coerceAtLeast(1)
        } else { windowLo = 0; windowHi = 0; windowMin = 0 }
        val weekdaysObserved = fixes.map { LocalDate.ofInstant(Instant.ofEpochMilli(it.time), zone) }.toSet()
            .count { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
        val inWindow = homeWeekdayDepartures.values.count { it in windowLo..windowHi }
        val inWindowPct = if (weekdaysObserved > 0 && windowMin > 0) inWindow * 100 / weekdaysObserved else 0

        // 4. Route repetition.
        val (tripCount, repeated, repeatedOf) = routeRepetition(fixes, stays)
        val routePct = if (repeatedOf > 0) repeated * 100f / repeatedOf else 0f

        // 5. Predictability.
        val depStd = if (depMinutes.size >= 3) stdDev(depMinutes.map { it.toDouble() }) else null
        val depScore = depStd?.let { (1.0 - (it / 90.0).coerceIn(0.0, 1.0)).toFloat() }
        val routeScore = if (repeatedOf > 0) routePct / 100f else null
        val predictability = when {
            depScore != null && routeScore != null -> 0.6f * depScore + 0.4f * routeScore
            depScore != null -> depScore
            routeScore != null -> routeScore
            else -> 0f
        }.coerceIn(0f, 1f)

        // 6. Suggestions.
        val suggestions = ArrayList<String>()
        if (home != null && depMinutes.size >= 3) {
            suggestions += "You leave home ${clock(windowLo)}–${clock(windowHi)} on $inWindowPct% of weekdays."
            if (windowMin <= 30) suggestions += "Consider varying your departure time by ±15 minutes."
            else if (windowMin <= 60) suggestions += "Your departure window is under an hour; widening it makes you harder to wait for."
        } else if (home != null) {
            suggestions += "Too few weekday departures from home recorded to judge your timing yet."
        }
        clusters.getOrNull(1)?.takeIf { it.label == "Work" }?.let { work ->
            val back = work.departures.filter { it.weekday != DayOfWeek.SATURDAY && it.weekday != DayOfWeek.SUNDAY && it.samples >= 2 }
            if (back.isNotEmpty()) {
                val tightest = back.minByOrNull { it.stdMinutes }!!
                if (tightest.stdMinutes <= 20f) {
                    suggestions += "You leave work around ${clock(tightest.meanMinuteOfDay)} on ${tightest.weekday.name.lowercase().replaceFirstChar { it.uppercase() }}s (± ${tightest.stdMinutes.toInt()} min). Someone who knows that knows where to stand."
                }
            }
        }
        if (repeatedOf >= 4) {
            suggestions += "%.0f%% of your repeated trips follow the same route.".format(Locale.US, routePct)
            if (routePct >= 70f) suggestions += "Take a different road or a different mode of transport on some days; a watcher relies on the usual route."
        }
        if (clusters.size >= 3) {
            val others = clusters.drop(2).filter { it.days >= 3 }
            if (others.isNotEmpty()) suggestions += "${others.size} other place(s) you return to regularly were found; a regular gym or café is a regular opportunity."
        }
        if (predictability >= 0.75f) suggestions += "Overall predictability is high (%.0f%%). Small, deliberate variations in time and route matter more than any one big change.".format(Locale.US, predictability * 100)
        else if (predictability > 0f && predictability < 0.4f) suggestions += "Overall predictability is low (%.0f%%). Keep it that way.".format(Locale.US, predictability * 100)
        if (daysCovered < 7) suggestions += "Only $daysCovered day(s) of history so far; the picture firms up after two weeks."

        return PatternReport(
            clusters = clusters,
            weekdayDepartureWindowMin = windowMin,
            routeRepetitionPct = routePct,
            predictabilityScore = predictability,
            suggestions = suggestions,
            fixes = raw.size, daysCovered = daysCovered, trips = tripCount
        )
    }

    // ── Stays ────────────────────────────────────────────────────────────

    private class Stay(val lat: Double, val lon: Double, val start: Long, val end: Long, val points: Int)

    private class Place(var lat: Double, var lon: Double, val stays: MutableList<Stay>) {
        var dwellMs: Long = 0L
    }

    /**
     * Fused location delivers nothing while the phone sits still (the scanner asks
     * for a 25 m minimum displacement), so a stay is one fix on arrival and one on
     * leaving. Two consecutive fixes within [EPS_M] with a long gap between them
     * mean the phone stayed; synthetic fixes are placed between them so DBSCAN
     * sees the stay as the dense cluster it was.
     */
    private fun densify(sorted: List<Fix>): List<Fix> {
        if (sorted.size < 2) return sorted
        val out = ArrayList<Fix>(sorted.size + sorted.size / 4)
        for (i in sorted.indices) {
            val f = sorted[i]
            out += f
            val next = sorted.getOrNull(i + 1) ?: continue
            val gap = next.time - f.time
            if (gap > DENSIFY_GAP_MS && haversine(f, next) <= EPS_M) {
                var t = f.time + DENSIFY_STEP_MS
                while (t < next.time) {
                    out += Fix(f.lat, f.lon, 0f, t, f.accuracy)
                    t += DENSIFY_STEP_MS
                }
            }
        }
        return out
    }

    /**
     * DBSCAN over space and time: neighbours are within [EPS_M] and [WINDOW_MS].
     * Indexed by a ~55 m grid with per-cell time-sorted lists, so each neighbour
     * query is a few binary searches rather than a pass over the whole log.
     * Returns a cluster id per fix, -1 for noise.
     */
    private fun dbscan(fixes: List<Fix>): IntArray {
        val n = fixes.size
        val labels = IntArray(n) { UNVISITED }
        val index = GridIndex(fixes, EPS_M)
        var cluster = 0
        val queue = ArrayDeque<Int>()
        for (i in 0 until n) {
            if (labels[i] != UNVISITED) continue
            val neigh = index.neighbours(i, WINDOW_MS)
            if (neigh.size < MIN_PTS) { labels[i] = NOISE; continue }
            labels[i] = cluster
            queue.clear()
            queue.addAll(neigh)
            while (queue.isNotEmpty()) {
                val j = queue.removeFirst()
                if (labels[j] == NOISE) labels[j] = cluster
                if (labels[j] != UNVISITED) continue
                labels[j] = cluster
                val more = index.neighbours(j, WINDOW_MS)
                if (more.size >= MIN_PTS) queue.addAll(more)
            }
            cluster++
        }
        return labels
    }

    private const val UNVISITED = -2
    private const val NOISE = -1

    private class GridIndex(private val fixes: List<Fix>, epsM: Double) {
        private val dLat = epsM / 111_320.0
        private val cells = HashMap<Long, IntArray>()

        // Longitude cells shrink with latitude; one factor from the log's mean latitude
        // keeps every cell at least [epsM] wide so the 3×3 neighbourhood is enough.
        private val dLon = dLat / cos(Math.toRadians(fixes.sumOf { it.lat } / fixes.size)).coerceAtLeast(0.01)
        private val epsM = epsM

        init {
            val tmp = HashMap<Long, MutableList<Int>>()
            for (i in fixes.indices) tmp.getOrPut(key(fixes[i])) { ArrayList() } += i
            // Fixes are time-sorted, so each cell list is too.
            for ((k, v) in tmp) cells[k] = v.toIntArray()
        }

        private fun latCell(f: Fix): Long = floor(f.lat / dLat).toLong()
        private fun lonCell(f: Fix): Long = floor(f.lon / dLon).toLong()
        private fun key(f: Fix): Long = pack(latCell(f), lonCell(f))
        private fun pack(la: Long, lo: Long): Long = (la shl 32) xor (lo and 0xffffffffL)

        /** Indices of the fixes within [epsM] and [windowMs] of fix [i], itself included. */
        fun neighbours(i: Int, windowMs: Long): List<Int> {
            val f = fixes[i]
            val la = latCell(f)
            val lo = lonCell(f)
            val lowT = f.time - windowMs
            val highT = f.time + windowMs
            val out = ArrayList<Int>(8)
            for (dl in -1L..1L) for (dn in -1L..1L) {
                val cell = cells[pack(la + dl, lo + dn)] ?: continue
                var k = lowerBound(cell, lowT)
                while (k < cell.size) {
                    val j = cell[k]
                    if (fixes[j].time > highT) break
                    if (haversine(f, fixes[j]) <= epsM) out += j
                    k++
                }
            }
            return out
        }

        /** First position in [cell] whose fix time is at least [t]. */
        private fun lowerBound(cell: IntArray, t: Long): Int {
            var lo = 0
            var hi = cell.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (fixes[cell[mid]].time < t) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }

    /** One stay per DBSCAN cluster: its centroid and the time span of its fixes. */
    private fun buildStays(fixes: List<Fix>, labels: IntArray): List<Stay> {
        val byCluster = HashMap<Int, MutableList<Int>>()
        for (i in labels.indices) {
            val c = labels[i]
            if (c >= 0) byCluster.getOrPut(c) { ArrayList() } += i
        }
        return byCluster.values.map { idx ->
            var lat = 0.0
            var lon = 0.0
            var start = Long.MAX_VALUE
            var end = Long.MIN_VALUE
            for (i in idx) {
                val f = fixes[i]
                lat += f.lat; lon += f.lon
                if (f.time < start) start = f.time
                if (f.time > end) end = f.time
            }
            Stay(lat / idx.size, lon / idx.size, start, end, idx.size)
        }
    }

    /** Stays whose centres fall within [MERGE_M] are one place; centres are dwell-weighted. */
    private fun mergeStays(stays: List<Stay>): MutableList<Place> {
        val places = ArrayList<Place>()
        for (s in stays) {
            val dwell = (s.end - s.start).coerceAtLeast(1L)
            val hit = places.firstOrNull { metres(it.lat, it.lon, s.lat, s.lon) <= MERGE_M }
            if (hit == null) {
                places += Place(s.lat, s.lon, mutableListOf(s)).also { it.dwellMs = dwell }
            } else {
                val total = hit.dwellMs + dwell
                hit.lat = (hit.lat * hit.dwellMs + s.lat * dwell) / total
                hit.lon = (hit.lon * hit.dwellMs + s.lon * dwell) / total
                hit.dwellMs = total
                hit.stays += s
            }
        }
        return places
    }

    private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        haversine(Fix(lat1, lon1, null, 0L), Fix(lat2, lon2, null, 0L))

    // ── Departures ───────────────────────────────────────────────────────

    private fun minuteOfDay(ts: Long, zone: ZoneId): Int {
        val t = Instant.ofEpochMilli(ts).atZone(zone)
        return t.hour * 60 + t.minute
    }

    /** Per weekday with at least two departures: mean and spread of the leaving time. */
    private fun departureStats(stays: List<Stay>, zone: ZoneId): List<DepartureStats> {
        val byDay = HashMap<DayOfWeek, MutableList<Int>>()
        for (s in stays) {
            val day = Instant.ofEpochMilli(s.end).atZone(zone).dayOfWeek
            byDay.getOrPut(day) { ArrayList() } += minuteOfDay(s.end, zone)
        }
        return byDay.entries
            .filter { it.value.size >= 2 }
            .map { (day, mins) ->
                val d = mins.map { it.toDouble() }
                DepartureStats(day, d.average().toInt(), stdDev(d).toFloat(), mins.size)
            }
            .sortedBy { it.weekday }
    }

    /**
     * For each weekday date: the minute of the first departure from this place
     * that day. A departure before 03:00 belongs to the night before and is skipped.
     */
    private fun firstWeekdayDepartures(stays: List<Stay>, zone: ZoneId): Map<LocalDate, Int> {
        val out = HashMap<LocalDate, Int>()
        for (s in stays) {
            val t = Instant.ofEpochMilli(s.end).atZone(zone)
            if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) continue
            val minute = t.hour * 60 + t.minute
            if (minute < 3 * 60) continue
            val date = t.toLocalDate()
            val prev = out[date]
            if (prev == null || minute < prev) out[date] = minute
        }
        return out
    }

    /** Nearest-rank percentile of an ascending list. */
    private fun percentile(sorted: List<Int>, p: Double): Int {
        if (sorted.isEmpty()) return 0
        val rank = Math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    // ── Routes ───────────────────────────────────────────────────────────

    private class Trip(val from: Int, val to: Int, val cells: Set<Long>, val start: Long)

    /**
     * Trips are the fixes between two consecutive stays at different places. A
     * trip's shape is the set of 100 m cells its path crosses (resampled every
     * 50 m so a sparse log still fills the cells between fixes). Within each
     * from→to pair the trip most like the others is the usual route; the share
     * of the remaining trips that match it (Jaccard ≥ [SAME_ROUTE_JACCARD]) is
     * the repetition. Returns (trips, repeated, repeatedOf).
     */
    private fun routeRepetition(fixes: List<Fix>, stays: List<Stay>): Triple<Int, Int, Int> {
        if (stays.size < 2) return Triple(0, 0, 0)
        val places = mergeStays(stays)
        val placeOf = java.util.IdentityHashMap<Stay, Int>()
        for ((i, p) in places.withIndex()) for (s in p.stays) placeOf[s] = i

        val trips = ArrayList<Trip>()
        var fi = 0
        for (k in 0 until stays.size - 1) {
            val a = stays[k]
            val b = stays[k + 1]
            val from = placeOf[a] ?: continue
            val to = placeOf[b] ?: continue
            if (from == to) continue
            while (fi < fixes.size && fixes[fi].time <= a.end) fi++
            val path = ArrayList<Fix>()
            var j = fi
            while (j < fixes.size && fixes[j].time < b.start) { path += fixes[j]; j++ }
            if (path.size < 2) continue
            trips += Trip(from, to, pathCells(path), a.end)
        }

        var repeated = 0
        var repeatedOf = 0
        for ((_, group) in trips.groupBy { it.from to it.to }) {
            val recent = group.sortedByDescending { it.start }.take(MAX_TRIPS_PER_PAIR)
            if (recent.size < 2) continue
            var best = 0
            var bestSum = -1.0
            for (i in recent.indices) {
                var sum = 0.0
                for (j in recent.indices) if (i != j) sum += jaccard(recent[i].cells, recent[j].cells)
                if (sum > bestSum) { bestSum = sum; best = i }
            }
            val usual = recent[best].cells
            for (i in recent.indices) {
                if (i == best) continue
                repeatedOf++
                if (jaccard(usual, recent[i].cells) >= SAME_ROUTE_JACCARD) repeated++
            }
        }
        return Triple(trips.size, repeated, repeatedOf)
    }

    /** The [CELL_M] grid cells a path crosses, with straight segments filled in every [RESAMPLE_M]. */
    private fun pathCells(path: List<Fix>): Set<Long> {
        val dLat = CELL_M / 111_320.0
        val dLon = dLat / cos(Math.toRadians(path[0].lat)).coerceAtLeast(0.01)
        val out = HashSet<Long>()
        fun add(lat: Double, lon: Double) {
            val la = floor(lat / dLat).toLong()
            val lo = floor(lon / dLon).toLong()
            out += (la shl 32) xor (lo and 0xffffffffL)
        }
        add(path[0].lat, path[0].lon)
        for (i in 1 until path.size) {
            val a = path[i - 1]
            val b = path[i]
            val d = haversine(a, b)
            val steps = (d / RESAMPLE_M).toInt().coerceAtMost(2_000)
            for (s in 1..steps) {
                val t = s.toDouble() / (steps + 1)
                add(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            }
            add(b.lat, b.lon)
        }
        return out
    }

    private fun jaccard(a: Set<Long>, b: Set<Long>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        var inter = 0
        val (small, large) = if (a.size <= b.size) a to b else b to a
        for (x in small) if (x in large) inter++
        val union = a.size + b.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }
}

/** "08:05" for 485 minutes into the day. */
internal fun clock(minuteOfDay: Int): String {
    val m = ((minuteOfDay % 1440) + 1440) % 1440
    return "%02d:%02d".format(Locale.US, m / 60, m % 60)
}