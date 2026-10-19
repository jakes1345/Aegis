package app.clairix.nav

import java.util.BitSet
import kotlin.math.abs
import kotlin.math.max

/**
 * A* over the CSR road graph. The search state is the edge just driven (not the node), so turn
 * penalties that depend on the approach are costed exactly and a U-turn is a real choice.
 *
 * Instances hold per-edge work arrays sized to the graph and reuse them between queries;
 * [route] and [alternatives] are synchronized, so one Router serves one query at a time.
 */
class Router(
    private val graph: RoadGraph,
    private val speeds: SpeedSource,
    private val vehicle: VehicleModel = VehicleModel.DEFAULT,
) {
    companion object {
        private const val MAX_KPH = 130.0
        private const val MIN_KPH = 2.0
        private const val SIGNAL_SEC = 15.0
        private const val STOP_SEC = 5.0
        private const val LEFT_SEC = 8.0
        private const val RIGHT_SEC = 3.0
        private const val UTURN_SEC = 30.0
        private const val TOLL_FACTOR = 1e3
        private const val HIGHWAY_FACTOR = 5.0
        // Share of signalised junctions where the car actually has to stop.
        private const val SIGNAL_STOP_SHARE = 0.5
        private const val SNAP_SLACK_M = 25.0
        private const val MIN_PARTIAL_M = 1.0
        private const val DEDUP_SHARE = 0.8
        private val SNAP_RADII = doubleArrayOf(60.0, 250.0, 1_000.0, 3_000.0)
        private val CLASS_KPH = doubleArrayOf(105.0, 85.0, 65.0, 55.0, 45.0, 40.0, 30.0, 15.0)

        /** Bytes per edge [ensureArrays] allocates: g, gTime, pred and speedCache, 4 bytes each. */
        private const val WORK_BYTES_PER_EDGE = 4L * 4
        /** [settled] grows up to one bit per edge. */
        private const val SETTLED_EDGES_PER_BYTE = 8L

        /**
         * Heap a [Router] over [graph] needs on top of the memory-mapped file: its per-edge work
         * arrays, the `settled` bitset at full size, and the graph's spatial index if it is not
         * built yet. The touched lists and the A* heap grow with the search frontier instead of the
         * graph and are left to the caller's safety margin.
         */
        fun estimatedHeapBytes(graph: RoadGraph): Long {
            val edges = graph.edgeCount.toLong()
            return edges * WORK_BYTES_PER_EDGE + (edges + SETTLED_EDGES_PER_BYTE - 1) / SETTLED_EDGES_PER_BYTE +
                graph.estimatedIndexBytes()
        }
    }

    private val edgeCount = graph.edgeCount
    private var g = FloatArray(0)
    private var gTime = FloatArray(0)
    private var pred = IntArray(0)
    private var speedCache = FloatArray(0)
    private val settled = BitSet()
    private var touched = IntArray(1024)
    private var touchedCount = 0
    private var speedTouched = IntArray(1024)
    private var speedTouchedCount = 0
    private val heap = MinHeap()
    private val scratch = PolyScratch()

    // Results of the last traverse()/turn() call; the hot loop avoids allocating a pair per edge.
    private var outCost = 0.0
    private var outTime = 0.0

    private var profile = RouteProfile.FASTEST
    private var departEpochSec = 0L

    @Synchronized
    fun route(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        profile: RouteProfile, departEpochSec: Long,
    ): Route? {
        val origins = snap(fromLat, fromLon) ?: return null
        val dests = snap(toLat, toLon) ?: return null
        return search(origins, dests, toLat, toLon, profile, departEpochSec)
    }

    @Synchronized
    fun alternatives(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, departEpochSec: Long): List<Route> {
        val origins = snap(fromLat, fromLon) ?: return emptyList()
        val dests = snap(toLat, toLon) ?: return emptyList()
        val order = listOf(
            RouteProfile.FASTEST, RouteProfile.ECO, RouteProfile.NO_TOLLS,
            RouteProfile.NO_HIGHWAYS, RouteProfile.SHORTEST,
        )
        val kept = ArrayList<Route>()
        for (p in order) {
            val r = search(origins, dests, toLat, toLon, p, departEpochSec) ?: continue
            if (kept.none { sharedShare(r, it) > DEDUP_SHARE }) kept.add(r)
        }
        kept.sortBy { it.durationSec }
        return kept
    }

    /** Share of [a]'s driven length that lies on edges [b] also uses. */
    private fun sharedShare(a: Route, b: Route): Double {
        if (a.distanceM <= 0) return 1.0
        val other = HashSet<Int>(b.edges.size * 2).apply { b.edges.forEach { add(it) } }
        var shared = 0.0
        var total = 0.0
        for (e in a.edges) {
            val len = graph.edgeLengthM(e)
            total += len
            if (e in other) shared += len
        }
        return if (total > 0) shared / total else 1.0
    }

    private fun snap(lat: Double, lon: Double): List<EdgeHit>? {
        for (r in SNAP_RADII) {
            val hits = graph.nearestEdges(lat, lon, r, 16)
            if (hits.isNotEmpty()) {
                // Only the nearest road (both directions of it, and roads meeting it right there) is a
                // plausible start; a cheaper road 80 m away is not where the car is.
                val limit = hits[0].distanceM + SNAP_SLACK_M
                return hits.filter { it.distanceM <= limit }
            }
        }
        return null
    }

    // ── Costs ───────────────────────────────────────────────────────────────

    private fun freeFlowKph(e: Int): Double {
        val max = graph.edgeMaxspeedKph(e)
        return if (max > 0) max * 0.9 else CLASS_KPH[graph.edgeRoadClass(e).coerceIn(0, CLASS_KPH.size - 1)]
    }

    private fun lookupKph(e: Int, elapsedSec: Double): Double {
        val key = graph.segmentKey(e)
        // A NaN would survive coerceIn and poison every cost compared against it.
        val kph = speeds.liveKph(key)?.takeUnless { it.isNaN() }
            ?: speeds.typicalKph(key, departEpochSec + elapsedSec.toLong())?.takeUnless { it.isNaN() }
            ?: freeFlowKph(e)
        // Capped at the heuristic's speed so the A* bound stays admissible.
        return kph.coerceIn(MIN_KPH, MAX_KPH)
    }

    private fun kph(e: Int, elapsedSec: Double): Double {
        val cached = speedCache[e]
        if (!cached.isNaN()) return cached.toDouble()
        val v = lookupKph(e, elapsedSec)
        speedCache[e] = v.toFloat()
        if (speedTouchedCount == speedTouched.size) speedTouched = speedTouched.copyOf(speedTouched.size * 2)
        speedTouched[speedTouchedCount++] = e
        return v
    }

    private fun nodeDelaySec(flags: Int): Double =
        (if (flags and EdgeFlags.SIGNALS != 0) SIGNAL_SEC else 0.0) + (if (flags and EdgeFlags.STOP != 0) STOP_SEC else 0.0)

    private fun nodeFuel(flags: Int): Double {
        var l = 0.0
        if (flags and EdgeFlags.SIGNALS != 0) l += SIGNAL_STOP_SHARE * vehicle.stopPenaltyLiters + vehicle.idleLiters(SIGNAL_SEC)
        if (flags and EdgeFlags.STOP != 0) l += vehicle.stopPenaltyLiters + vehicle.idleLiters(STOP_SEC)
        return l
    }

    /** Sets outTime/outCost for driving [e] from fraction [f0] to [f1] at speed [v]. */
    private fun traverse(e: Int, f0: Double, f1: Double, v: Double, arrives: Boolean) {
        val flags = graph.edgeFlags(e)
        val len = graph.edgeLengthM(e) * (f1 - f0)
        val delay = if (arrives) nodeDelaySec(flags) else 0.0
        val t = len / (v / 3.6) + delay
        outTime = t
        outCost = when (profile) {
            RouteProfile.SHORTEST -> len
            RouteProfile.FASTEST -> t
            RouteProfile.NO_TOLLS -> if (flags and EdgeFlags.TOLL != 0) t * TOLL_FACTOR else t
            RouteProfile.NO_HIGHWAYS -> if (graph.edgeRoadClass(e) <= 1) t * HIGHWAY_FACTOR else t
            RouteProfile.ECO -> vehicle.litersFor(len, v) + (if (arrives) nodeFuel(flags) else 0.0)
        }
    }

    /**
     * Seconds lost turning from [eIn] (which started at [inFrom]) onto [eOut]. A bend where the
     * road offers no other way on is not a turn decision and costs nothing.
     */
    private fun turnSeconds(eIn: Int, inFrom: Int, eOut: Int): Double {
        val node = graph.edgeTo(eIn)
        if (graph.isReverseOf(eIn, inFrom, eOut)) return UTURN_SEC
        var options = 0
        for (o in graph.firstEdge(node) until graph.lastEdgeExclusive(node)) {
            if (!graph.isReverseOf(eIn, inFrom, o)) options++
        }
        // Checked before the angle: a hairpin where one way hands over to the next is still a bend.
        if (options <= 1) return 0.0
        val delta = RoadGeo.deltaDeg(graph.endBearing(eIn, inFrom), graph.startBearing(eOut, node))
        if (abs(delta) > 170) return UTURN_SEC
        return when {
            delta < -30 -> LEFT_SEC
            delta > 30 -> RIGHT_SEC
            else -> 0.0
        }
    }

    /** Sets outTime/outCost for the turn between two consecutive edges. */
    private fun turn(eIn: Int, inFrom: Int, eOut: Int) {
        val sec = turnSeconds(eIn, inFrom, eOut)
        outTime = sec
        outCost = when (profile) {
            RouteProfile.SHORTEST -> 0.0
            RouteProfile.ECO -> vehicle.idleLiters(sec) +
                (if (sec >= LEFT_SEC) SIGNAL_STOP_SHARE * vehicle.stopPenaltyLiters else 0.0)
            else -> sec
        }
    }

    private fun heuristic(node: Int, toLat: Double, toLon: Double, snapRadius: Double): Double {
        if (profile == RouteProfile.ECO) return 0.0
        // The route ends at a snapped point up to snapRadius from the target; 0.995 absorbs
        // decimetre rounding of stored edge lengths so the bound never overestimates.
        val d = max(0.0, RoadGeo.haversineM(graph.nodeLat(node), graph.nodeLon(node), toLat, toLon) - snapRadius) * 0.995
        return if (profile == RouteProfile.SHORTEST) d else d / (MAX_KPH / 3.6)
    }

    // ── Search ──────────────────────────────────────────────────────────────

    private fun ensureArrays() {
        if (g.size == edgeCount) return
        g = FloatArray(edgeCount) { Float.POSITIVE_INFINITY }
        gTime = FloatArray(edgeCount)
        pred = IntArray(edgeCount)
        speedCache = FloatArray(edgeCount) { Float.NaN }
    }

    private fun touch(e: Int) {
        if (touchedCount == touched.size) touched = touched.copyOf(touched.size * 2)
        touched[touchedCount++] = e
    }

    private fun resetArrays() {
        for (i in 0 until touchedCount) g[touched[i]] = Float.POSITIVE_INFINITY
        touchedCount = 0
        for (i in 0 until speedTouchedCount) speedCache[speedTouched[i]] = Float.NaN
        speedTouchedCount = 0
        settled.clear()
        heap.clear()
    }

    private fun search(
        origins: List<EdgeHit>, dests: List<EdgeHit>, toLat: Double, toLon: Double,
        profile: RouteProfile, departEpochSec: Long,
    ): Route? {
        if (edgeCount == 0) return null
        this.profile = profile
        this.departEpochSec = departEpochSec
        ensureArrays()
        try {
            return runSearch(origins, dests, toLat, toLon)
        } finally {
            resetArrays()
        }
    }

    private fun runSearch(origins: List<EdgeHit>, dests: List<EdgeHit>, toLat: Double, toLon: Double): Route? {
        val snapRadius = dests.maxOf { it.distanceM }
        val originFraction = HashMap<Int, Double>()
        val destsByNode = HashMap<Int, MutableList<EdgeHit>>()
        for (d in dests) destsByNode.getOrPut(graph.edgeFrom(d.edge)) { ArrayList() }.add(d)

        var bestCost = Double.POSITIVE_INFINITY
        var bestLast = -1      // state reached before the destination edge; -1 = same-edge trip
        var bestDest: EdgeHit? = null
        var bestOrigin: EdgeHit? = null

        for (o in origins) {
            originFraction[o.edge] = o.fraction
            val v = kph(o.edge, 0.0)
            for (d in dests) {
                if (d.edge == o.edge && d.fraction >= o.fraction) {
                    traverse(o.edge, o.fraction, d.fraction, v, arrives = false)
                    if (outCost < bestCost) {
                        bestCost = outCost; bestLast = -1; bestDest = d; bestOrigin = o
                    }
                }
            }
            traverse(o.edge, o.fraction, 1.0, v, arrives = true)
            if (outCost < g[o.edge]) {
                if (g[o.edge].isInfinite()) touch(o.edge)
                g[o.edge] = outCost.toFloat()
                gTime[o.edge] = outTime.toFloat()
                pred[o.edge] = -1
                heap.push(outCost + heuristic(graph.edgeTo(o.edge), toLat, toLon, snapRadius), o.edge)
            }
        }

        while (heap.size > 0) {
            val key = heap.peekKey()
            if (key >= bestCost) break
            val e = heap.pop()
            if (settled.get(e)) continue
            settled.set(e)
            val ge = g[e].toDouble()
            val te = gTime[e].toDouble()
            val node = graph.edgeTo(e)
            val from = graph.edgeFrom(e)

            destsByNode[node]?.let { list ->
                for (d in list) {
                    turn(e, from, d.edge)
                    val turnCost = outCost
                    val turnTime = outTime
                    traverse(d.edge, 0.0, d.fraction, kph(d.edge, te + turnTime), arrives = false)
                    val total = ge + turnCost + outCost
                    if (total < bestCost) {
                        bestCost = total; bestLast = e; bestDest = d; bestOrigin = null
                    }
                }
            }

            for (e2 in graph.firstEdge(node) until graph.lastEdgeExclusive(node)) {
                if (settled.get(e2)) continue
                turn(e, from, e2)
                val turnCost = outCost
                val turnTime = outTime
                traverse(e2, 0.0, 1.0, kph(e2, te + turnTime), arrives = true)
                val ng = ge + turnCost + outCost
                if (ng < g[e2]) {
                    if (g[e2].isInfinite()) touch(e2)
                    g[e2] = ng.toFloat()
                    gTime[e2] = (te + turnTime + outTime).toFloat()
                    pred[e2] = e
                    heap.push(ng + heuristic(graph.edgeTo(e2), toLat, toLon, snapRadius), e2)
                }
            }
        }

        val dest = bestDest ?: return null
        val path = ArrayList<Int>()
        val fractions = ArrayList<DoubleArray>()
        if (bestLast == -1) {
            val o = bestOrigin!!
            path.add(o.edge); fractions.add(doubleArrayOf(o.fraction, dest.fraction))
        } else {
            var s = bestLast
            while (s != -1) { path.add(s); s = pred[s] }
            path.reverse()
            for (i in path.indices) fractions.add(doubleArrayOf(0.0, 1.0))
            fractions[0][0] = originFraction[path[0]]!!
            path.add(dest.edge); fractions.add(doubleArrayOf(0.0, dest.fraction))
        }
        return assemble(path, fractions)
    }

    /** Totals, geometry and instructions for a found path, with partial first and last edges. */
    private fun assemble(rawPath: List<Int>, rawFractions: List<DoubleArray>): Route {
        val path = ArrayList(rawPath)
        val fractions = ArrayList(rawFractions)
        // A snap that lands on a node leaves a zero-length sliver of the neighbouring edge.
        if (path.size > 1 && graph.edgeLengthM(path[0]) * (fractions[0][1] - fractions[0][0]) < MIN_PARTIAL_M) {
            path.removeAt(0); fractions.removeAt(0)
        }
        val li = path.size - 1
        if (path.size > 1 && graph.edgeLengthM(path[li]) * (fractions[li][1] - fractions[li][0]) < MIN_PARTIAL_M) {
            path.removeAt(li); fractions.removeAt(li)
        }

        var distance = 0.0
        var duration = 0.0
        var freeFlow = 0.0
        var toll = 0.0
        var fuel = 0.0
        val froms = IntArray(path.size) { graph.edgeFrom(path[it]) }
        for (i in path.indices) {
            val e = path[i]
            val f0 = fractions[i][0]
            val f1 = fractions[i][1]
            val arrives = f1 >= 1.0
            if (i > 0) {
                val sec = turnSeconds(path[i - 1], froms[i - 1], e)
                duration += sec; freeFlow += sec
                fuel += vehicle.idleLiters(sec) + (if (sec >= LEFT_SEC) SIGNAL_STOP_SHARE * vehicle.stopPenaltyLiters else 0.0)
            }
            val len = graph.edgeLengthM(e) * (f1 - f0)
            val flags = graph.edgeFlags(e)
            val delay = if (arrives) nodeDelaySec(flags) else 0.0
            val v = lookupKph(e, duration)
            val vFree = freeFlowKph(e).coerceIn(MIN_KPH, MAX_KPH)
            distance += len
            duration += len / (v / 3.6) + delay
            freeFlow += len / (vFree / 3.6) + delay
            fuel += vehicle.litersFor(len, v) + (if (arrives) nodeFuel(flags) else 0.0)
            if (flags and EdgeFlags.TOLL != 0) toll += len
        }

        val geometry = ArrayList<LatLon>()
        val edgeStart = IntArray(path.size)
        for (i in path.indices) {
            val pts = slice(path[i], froms[i], fractions[i][0], fractions[i][1])
            if (geometry.isNotEmpty() && geometry.last() == pts[0]) {
                edgeStart[i] = geometry.size - 1
                for (k in 1 until pts.size) geometry.add(pts[k])
            } else {
                edgeStart[i] = geometry.size
                geometry.addAll(pts)
            }
        }
        val edges = path.toIntArray()
        return Route(
            profile = profile, edges = edges, geometry = geometry,
            distanceM = distance, durationSec = duration, freeFlowDurationSec = freeFlow,
            tollM = toll, fuelLiters = fuel,
            maneuvers = Maneuvers.build(graph, edges, geometry, edgeStart),
        )
    }

    /** The part of [e]'s polyline between fractions [f0] and [f1] of its length. */
    private fun slice(e: Int, from: Int, f0: Double, f1: Double): List<LatLon> {
        val s = scratch
        graph.loadGeometry(e, s, from)
        val cum = DoubleArray(s.n)
        for (i in 1 until s.n) cum[i] = cum[i - 1] + RoadGeo.haversineM(s.lat[i - 1], s.lon[i - 1], s.lat[i], s.lon[i])
        val total = cum[s.n - 1]
        if (total <= 0) return listOf(LatLon(s.lat[0], s.lon[0]))
        fun at(f: Double): LatLon {
            val target = f * total
            var i = 1
            while (i < s.n - 1 && cum[i] < target) i++
            val segLen = cum[i] - cum[i - 1]
            val t = if (segLen > 0) ((target - cum[i - 1]) / segLen).coerceIn(0.0, 1.0) else 0.0
            return LatLon(s.lat[i - 1] + t * (s.lat[i] - s.lat[i - 1]), s.lon[i - 1] + t * (s.lon[i] - s.lon[i - 1]))
        }
        val out = ArrayList<LatLon>()
        out.add(if (f0 <= 0.0) LatLon(s.lat[0], s.lon[0]) else at(f0))
        val lo = f0 * total
        val hi = f1 * total
        for (i in 1 until s.n - 1) if (cum[i] > lo && cum[i] < hi) out.add(LatLon(s.lat[i], s.lon[i]))
        val end = if (f1 >= 1.0) LatLon(s.lat[s.n - 1], s.lon[s.n - 1]) else at(f1)
        if (end != out.last()) out.add(end)
        return out
    }

    /** Binary min-heap of (key, edge) on primitive arrays; stale entries are skipped by the caller. */
    private class MinHeap {
        private var keys = DoubleArray(1024)
        private var items = IntArray(1024)
        var size = 0
            private set

        fun clear() { size = 0 }

        fun peekKey(): Double = keys[0]

        fun push(key: Double, item: Int) {
            if (size == keys.size) {
                keys = keys.copyOf(size * 2); items = items.copyOf(size * 2)
            }
            var i = size++
            while (i > 0) {
                val parent = (i - 1) ushr 1
                if (keys[parent] <= key) break
                keys[i] = keys[parent]; items[i] = items[parent]
                i = parent
            }
            keys[i] = key; items[i] = item
        }

        fun pop(): Int {
            val top = items[0]
            size--
            if (size > 0) {
                val key = keys[size]
                val item = items[size]
                var i = 0
                while (true) {
                    var child = 2 * i + 1
                    if (child >= size) break
                    if (child + 1 < size && keys[child + 1] < keys[child]) child++
                    if (keys[child] >= key) break
                    keys[i] = keys[child]; items[i] = items[child]
                    i = child
                }
                keys[i] = key; items[i] = item
            }
            return top
        }
    }
}
