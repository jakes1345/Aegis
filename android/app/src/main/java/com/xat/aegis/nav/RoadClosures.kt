package com.xat.aegis.nav

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Per-edge restrictions the router applies on top of traffic speeds: closed edges and speed caps. */
interface EdgeRestrictions {
    /** True when edge [e] must not be driven. */
    fun isClosed(e: Int): Boolean

    /** Highest speed in km/h edge [e] may be costed at, or null when it is not capped. */
    fun speedCapKph(e: Int): Double?

    companion object {
        val NONE: EdgeRestrictions = object : EdgeRestrictions {
            override fun isClosed(e: Int): Boolean = false
            override fun speedCapKph(e: Int): Double? = null
        }
    }
}

/** An immutable set of edge restrictions, looked up by binary search so the router's loop never allocates. */
class EdgeRestrictionSet private constructor(
    private val edges: IntArray,
    private val closed: BooleanArray,
    private val caps: FloatArray,
) : EdgeRestrictions {

    val size: Int get() = edges.size

    override fun isClosed(e: Int): Boolean {
        if (edges.isEmpty()) return false
        val i = edges.binarySearch(e)
        return i >= 0 && closed[i]
    }

    override fun speedCapKph(e: Int): Double? {
        if (edges.isEmpty()) return null
        val i = edges.binarySearch(e)
        if (i < 0 || closed[i]) return null
        val c = caps[i]
        return if (c.isNaN()) null else c.toDouble()
    }

    class Builder {
        private val closed = HashSet<Int>()
        private val caps = HashMap<Int, Double>()

        fun close(e: Int) { closed.add(e) }

        /** Caps [e] at [kph]; the lowest cap wins when several items cover one edge. */
        fun cap(e: Int, kph: Double) {
            if (kph.isNaN() || kph <= 0) return
            val cur = caps[e]
            if (cur == null || kph < cur) caps[e] = kph
        }

        fun build(): EdgeRestrictionSet {
            val all = (closed + caps.keys).sorted().toIntArray()
            val c = BooleanArray(all.size) { all[it] in closed }
            val k = FloatArray(all.size) { caps[all[it]]?.toFloat() ?: Float.NaN }
            return EdgeRestrictionSet(all, c, k)
        }
    }

    companion object {
        val EMPTY = EdgeRestrictionSet(IntArray(0), BooleanArray(0), FloatArray(0))
    }
}

/** Speed the router assumes on [e] without traffic data: 90% of the posted limit, else a road-class default. */
internal fun freeFlowKph(graph: RoadGraph, e: Int): Double {
    val max = graph.edgeMaxspeedKph(e)
    return if (max > 0) max * 0.9 else FREE_FLOW_CLASS_KPH[graph.edgeRoadClass(e).coerceIn(0, FREE_FLOW_CLASS_KPH.size - 1)]
}

internal val FREE_FLOW_CLASS_KPH = doubleArrayOf(105.0, 85.0, 65.0, 55.0, 45.0, 40.0, 30.0, 15.0)

/**
 * Maps work zones and provider incidents with a geometry onto [graph]'s edges and keeps the
 * resulting [EdgeRestrictions] current. Matching a geometry to edges is the expensive part and
 * depends only on the geometry, so it is cached per item; [update] then only re-evaluates which
 * items are active, which is cheap enough to run every minute.
 */
class RoadClosures(private val graph: RoadGraph) : EdgeRestrictions {

    companion object {
        const val STEP_M = 15.0
        const val MATCH_RADIUS_M = 20.0
        const val MAX_BEARING_DELTA_DEG = 35.0
        const val MIN_CONSECUTIVE_HITS = 2
        /** How much farther than the nearest edge in its heading an edge may be and still take a sample. */
        const val TIE_M = 1.5
        const val LANE_CLOSURE_FACTOR = 0.6
        const val WORK_ZONE_FACTOR = 0.85
        private const val NEAREST_LIMIT = 32
        /** Upper bound on samples per item, so one malformed feed geometry cannot stall the update. */
        private const val MAX_SAMPLES = 20_000

        private val CARDINAL_DEG = mapOf(
            "northbound" to 0.0, "eastbound" to 90.0, "southbound" to 180.0, "westbound" to 270.0,
        )

        /** True when an item with [direction] applies to both directions of the road it lies on. */
        fun isBothWays(direction: String?): Boolean {
            val d = direction?.trim()?.lowercase()
            return d.isNullOrEmpty() || d == "both" || d == "unknown" || d == "undefined"
        }
    }

    /** One thing that restricts roads, reduced to what matching needs. */
    data class Item(
        val id: String,
        val type: String,
        val closed: Boolean,
        val reducedKph: Double?,
        val directional: Boolean,
        val direction: String?,
        val geom: List<LatLon>,
        val start: Long?,
        val end: Long?,
    ) {
        fun isActive(nowSec: Long): Boolean = (start == null || start <= nowSec) && (end == null || end > nowSec)
    }

    @Volatile private var current: EdgeRestrictionSet = EdgeRestrictionSet.EMPTY
    private val matchCache = HashMap<String, IntArray>()

    /** The restrictions in force since the last [update]. */
    val restrictions: EdgeRestrictionSet get() = current

    override fun isClosed(e: Int): Boolean = current.isClosed(e)
    override fun speedCapKph(e: Int): Double? = current.speedCapKph(e)

    /** Recomputes the restrictions from the current work zones and provider incidents at [nowSec]. */
    @Synchronized
    fun update(zones: List<WorkZone>, incidents: List<Incident>, nowSec: Long): EdgeRestrictionSet {
        val items = ArrayList<Item>(zones.size + incidents.size)
        for (z in zones) toItem(z)?.let { items.add(it) }
        for (i in incidents) toItem(i)?.let { items.add(it) }
        val set = compute(items, nowSec)
        current = set
        return set
    }

    /** Builds the restriction set for [items] at [nowSec]; inactive items have no effect at all. */
    @Synchronized
    fun compute(items: List<Item>, nowSec: Long): EdgeRestrictionSet {
        val b = EdgeRestrictionSet.Builder()
        val live = HashSet<String>()
        for (item in items) {
            val key = cacheKey(item)
            live.add(key)
            if (!item.isActive(nowSec)) continue
            val edges = matchCache.getOrPut(key) { match(item.geom, if (item.directional) item.direction else null, item.directional) }
            for (e in edges) {
                when {
                    item.closed -> b.close(e)
                    item.reducedKph != null -> b.cap(e, item.reducedKph)
                    item.type == WorkZone.LANE_CLOSURE -> b.cap(e, freeFlowKph(graph, e) * LANE_CLOSURE_FACTOR)
                    item.type == WorkZone.WORK_ZONE -> b.cap(e, freeFlowKph(graph, e) * WORK_ZONE_FACTOR)
                }
            }
        }
        matchCache.keys.retainAll(live)
        return b.build()
    }

    private fun cacheKey(item: Item): String =
        item.id + "|" + item.directional + "|" + item.direction + "|" + item.geom.hashCode()

    private fun toItem(z: WorkZone): Item? {
        if (z.geom.size < 2) return null
        val closed = z.impact == WorkZone.IMPACT_ALL_CLOSED || z.type == WorkZone.CLOSURE
        return when {
            closed -> Item(z.id, WorkZone.CLOSURE, true, null, !isBothWays(z.direction), z.direction, z.geom, z.start, z.end)
            // A detour's geometry is the way around the work, not the work itself.
            z.type == WorkZone.DETOUR -> null
            z.type == WorkZone.LANE_CLOSURE || z.type == WorkZone.WORK_ZONE ->
                Item(z.id, z.type, false, z.speedKph, !isBothWays(z.direction), z.direction, z.geom, z.start, z.end)
            z.speedKph != null -> Item(z.id, z.type, false, z.speedKph, !isBothWays(z.direction), z.direction, z.geom, z.start, z.end)
            else -> null
        }
    }

    private fun toItem(i: Incident): Item? {
        val geom = i.geom ?: return null
        if (geom.size < 2) return null
        if (i.type != Incident.CLOSURE && i.type != Incident.LANE_CLOSURE && i.type != Incident.WORK_ZONE) return null
        val end = if (i.expiresAt > 0) i.expiresAt else null
        val start = if (i.createdAt > 0) i.createdAt else null
        // HERE and TomTom report each direction separately, with the shape drawn in the direction of travel.
        return Item(i.id, i.type, i.type == Incident.CLOSURE, null, true, null, geom, start, end)
    }

    /**
     * Edges [geom] runs along: samples every [STEP_M], keeps edges within [MATCH_RADIUS_M] whose
     * heading agrees with the geometry's (or its reverse when [directional] is false), and returns
     * those hit at [MIN_CONSECUTIVE_HITS] consecutive samples, ascending.
     *
     * A sample only counts for the nearest such edge in each heading (within [TIE_M]): the block
     * carrying on past a zone's end, or a ramp leaving the road at a shallow angle, lies within
     * reach of a sample or two near the junction but is never the road the sample is on.
     */
    fun match(geom: List<LatLon>, direction: String?, directional: Boolean): IntArray {
        if (geom.size < 2 || !overlapsGraph(geom)) return IntArray(0)
        val pts = orient(geom, direction)
        val run = HashMap<Int, Int>()
        val lastSample = HashMap<Int, Int>()
        val out = HashSet<Int>()
        val near = HashSet<Int>()
        var sample = 0
        fun visit(lat: Double, lon: Double, bearing: Double) {
            val hits = graph.nearestEdges(lat, lon, MATCH_RADIUS_M, NEAREST_LIMIT)
            // Hits come nearest first, so the first one seen in each heading is that heading's nearest.
            var nearestWith = Double.NaN
            var nearestAgainst = Double.NaN
            for (h in hits) {
                val d = abs(RoadGeo.deltaDeg(bearing, h.bearingDeg))
                val nearest = when {
                    d <= MAX_BEARING_DELTA_DEG -> {
                        if (nearestWith.isNaN()) nearestWith = h.distanceM
                        nearestWith
                    }
                    !directional && d >= 180 - MAX_BEARING_DELTA_DEG -> {
                        if (nearestAgainst.isNaN()) nearestAgainst = h.distanceM
                        nearestAgainst
                    }
                    else -> continue
                }
                near.add(h.edge)
                if (h.distanceM > nearest + TIE_M) continue
                val n = if (lastSample[h.edge] == sample - 1) (run[h.edge] ?: 0) + 1 else 1
                run[h.edge] = n
                lastSample[h.edge] = sample
                if (n >= MIN_CONSECUTIVE_HITS) out.add(h.edge)
            }
            sample++
        }
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val len = RoadGeo.haversineM(a.lat, a.lon, b.lat, b.lon)
            if (len <= 0.0) continue
            val bearing = RoadGeo.bearingDeg(a.lat, a.lon, b.lat, b.lon)
            val steps = max(1, ceil(len / STEP_M).toInt())
            // Each segment's end is the next one's start, so it is sampled there (or after the loop).
            for (k in 0 until steps) {
                if (sample >= MAX_SAMPLES) break
                val t = k.toDouble() / steps
                visit(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon), bearing)
            }
        }
        val last = pts.last()
        val prev = pts[pts.size - 2]
        visit(last.lat, last.lon, RoadGeo.bearingDeg(prev.lat, prev.lon, last.lat, last.lon))
        // An edge shorter than a sample step (two junctions close together) can be nobody's nearest
        // at any sample; inside the zone it joins a matched edge to the next one.
        if (out.isNotEmpty()) {
            val ends = out.mapTo(HashSet()) { graph.edgeTo(it) }
            val starts = out.mapTo(HashSet()) { graph.edgeFrom(it) }
            for (e in near) {
                if (e !in out && graph.edgeLengthM(e) <= 2 * STEP_M && graph.edgeFrom(e) in ends && graph.edgeTo(e) in starts) out.add(e)
            }
        }
        return out.sorted().toIntArray()
    }

    /**
     * Feeds should draw a directional event in its direction of travel, but some draw it the other
     * way; when the geometry runs against its stated cardinal direction it is reversed.
     */
    private fun orient(geom: List<LatLon>, direction: String?): List<LatLon> {
        val want = CARDINAL_DEG[direction?.trim()?.lowercase()] ?: return geom
        val a = geom.first()
        val b = geom.last()
        if (RoadGeo.haversineM(a.lat, a.lon, b.lat, b.lon) < 1.0) return geom
        val got = RoadGeo.bearingDeg(a.lat, a.lon, b.lat, b.lon)
        return if (abs(RoadGeo.deltaDeg(want, got)) > 100) geom.reversed() else geom
    }

    /** True when [geom]'s box overlaps the graph's (padded by the match radius). */
    private fun overlapsGraph(geom: List<LatLon>): Boolean {
        var a = 90.0; var b = 180.0; var c = -90.0; var d = -180.0
        for (p in geom) { a = min(a, p.lat); b = min(b, p.lon); c = max(c, p.lat); d = max(d, p.lon) }
        val pad = 0.001
        return c >= graph.minLat - pad && a <= graph.maxLat + pad && d >= graph.minLon - pad && b <= graph.maxLon + pad
    }
}
