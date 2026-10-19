package app.clairix.nav

import kotlin.math.abs
import kotlin.math.min

/**
 * Snaps GPS fixes to the road being driven, one fix at a time.
 *
 * Each candidate edge within 40 m is scored by squared, normalised distance (GPS error), heading
 * agreement (when the car is moving fast enough for the GPS course to mean anything) and
 * continuity with the previously matched edge, a cheap stand-in for an HMM transition model.
 * Heading is what separates the two directions of a two-way street, which share geometry.
 */
class MapMatcher(private val graph: RoadGraph) {
    private companion object {
        const val SEARCH_M = 40.0
        const val GPS_SIGMA_M = 10.0
        const val HEADING_SIGMA_DEG = 35.0
        const val MIN_HEADING_SPEED_MPS = 2.0f
        const val CONTINUITY_WINDOW_MS = 30_000L
        const val SAME_EDGE = 0.0
        const val NEXT_EDGE = 0.3
        const val TWO_HOPS = 1.0
        const val REVERSE_EDGE = 3.0
        const val UNCONNECTED = 2.5
        const val BACKWARDS_M = 25.0
        const val BACKWARDS_COST = 2.0
    }

    private var prevEdge = -1
    private var prevFraction = 0.0
    private var prevTimeMs = 0L

    @Synchronized
    fun match(lat: Double, lon: Double, bearingDeg: Float?, speedMps: Float?, timeMs: Long): EdgeHit? {
        val candidates = graph.nearestEdges(lat, lon, SEARCH_M, 12)
        if (candidates.isEmpty()) return null
        val useHeading = bearingDeg != null && (speedMps == null || speedMps >= MIN_HEADING_SPEED_MPS)
        val continuity = prevEdge >= 0 && timeMs - prevTimeMs in 0..CONTINUITY_WINDOW_MS

        var prevTo = -1
        var prevFrom = -1
        var successors: IntArray? = null
        if (continuity) {
            prevTo = graph.edgeTo(prevEdge)
            prevFrom = graph.edgeFrom(prevEdge)
            successors = IntArray(graph.lastEdgeExclusive(prevTo) - graph.firstEdge(prevTo)) { graph.firstEdge(prevTo) + it }
        }

        var best: EdgeHit? = null
        var bestScore = Double.MAX_VALUE
        for (c in candidates) {
            val d = c.distanceM / GPS_SIGMA_M
            var score = d * d
            if (useHeading) {
                val diff = abs(RoadGeo.deltaDeg(bearingDeg!!.toDouble(), c.bearingDeg)) / HEADING_SIGMA_DEG
                score += min(diff * diff, 30.0)
            }
            if (continuity) score += transitionCost(c, prevTo, prevFrom, successors!!)
            if (score < bestScore) {
                bestScore = score
                best = c
            }
        }
        val chosen = best ?: return null
        prevEdge = chosen.edge
        prevFraction = chosen.fraction
        prevTimeMs = timeMs
        return chosen
    }

    private fun transitionCost(c: EdgeHit, prevTo: Int, prevFrom: Int, successors: IntArray): Double {
        val e = c.edge
        if (e == prevEdge) {
            // Sliding back along the same edge by more than GPS noise means this is probably the wrong one.
            val backM = (prevFraction - c.fraction) * graph.edgeLengthM(e)
            return if (backM > BACKWARDS_M) SAME_EDGE + BACKWARDS_COST else SAME_EDGE
        }
        if (graph.isReverseOf(prevEdge, prevFrom, e)) return REVERSE_EDGE
        for (s in successors) if (s == e) return NEXT_EDGE
        for (s in successors) {
            val mid = graph.edgeTo(s)
            for (s2 in graph.firstEdge(mid) until graph.lastEdgeExclusive(mid)) if (s2 == e) return TWO_HOPS
        }
        // Edges leading into the previous one: a fix that lagged behind the last match.
        if (graph.edgeTo(e) == prevFrom) return TWO_HOPS
        return UNCONNECTED
    }

    @Synchronized
    fun reset() {
        prevEdge = -1
        prevFraction = 0.0
        prevTimeMs = 0L
    }
}
