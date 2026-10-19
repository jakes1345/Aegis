package app.clairix.nav

import kotlin.math.abs

/** Turns a routed edge sequence into spoken-style driving instructions. */
object Maneuvers {
    // Bearings are measured this far from the junction so short shape stubs do not skew them.
    private const val BEARING_SPAN_M = 15.0
    private const val STRAIGHT_DEG = 20.0
    private const val SLIGHT_DEG = 45.0
    private const val TURN_DEG = 135.0
    private const val UTURN_DEG = 170.0

    fun build(graph: RoadGraph, edges: IntArray, geometry: List<LatLon>, edgeStartIndex: IntArray): List<Maneuver> {
        if (edges.isEmpty() || geometry.isEmpty()) return emptyList()
        val cum = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cum[i] = cum[i - 1] + RoadGeo.haversineM(geometry[i - 1].lat, geometry[i - 1].lon, geometry[i].lat, geometry[i].lon)
        }
        val last = geometry.size - 1
        fun startOf(i: Int) = if (i < edgeStartIndex.size) edgeStartIndex[i] else last

        val out = ArrayList<Maneuver>()
        val firstName = graph.edgeName(edges[0])
        val headB = bearingOut(geometry, cum, 0, startOf(1))
        val dir = cardinal(headB)
        out.add(Maneuver(ManeuverType.DEPART, 0, 0.0, firstName, null,
            if (firstName != null) "Head $dir on $firstName" else "Head $dir"))

        var i = 1
        while (i < edges.size) {
            val prev = edges[i - 1]
            val cur = edges[i]
            val idx = startOf(i)
            val node = graph.edgeTo(prev)
            val prevFrom = graph.edgeFrom(prev)
            val prevFlags = graph.edgeFlags(prev)
            val curFlags = graph.edgeFlags(cur)
            val inB = bearingIn(geometry, cum, idx, startOf(i - 1))
            val outB = bearingOut(geometry, cum, idx, startOf(i + 1))
            val delta = RoadGeo.deltaDeg(inB, outB)
            val prevName = graph.edgeName(prev)
            val curName = graph.edgeName(cur)
            val dist = cum[idx]

            val others = ArrayList<Double>()
            // A rival carriageway (not a ramp) splitting off close to straight ahead: a real fork.
            var highwayFork = false
            for (o in graph.firstEdge(node) until graph.lastEdgeExclusive(node)) {
                if (o == cur || graph.isReverseOf(prev, prevFrom, o)) continue
                val d = RoadGeo.deltaDeg(inB, graph.startBearing(o, node))
                others.add(d)
                if (graph.edgeRoadClass(o) <= 1 && graph.edgeFlags(o) and EdgeFlags.LINK == 0 && abs(d) <= SLIGHT_DEG) {
                    highwayFork = true
                }
            }

            if (curFlags and EdgeFlags.ROUNDABOUT != 0 && prevFlags and EdgeFlags.ROUNDABOUT == 0) {
                var j = i
                var exits = 0
                var exitEdge = -1
                while (j < edges.size && graph.edgeFlags(edges[j]) and EdgeFlags.ROUNDABOUT != 0) {
                    val t = graph.edgeTo(edges[j])
                    if (j + 1 < edges.size && graph.edgeFlags(edges[j + 1]) and EdgeFlags.ROUNDABOUT == 0) {
                        exits++
                        exitEdge = edges[j + 1]
                        break
                    }
                    if (j + 1 < edges.size && hasExit(graph, t)) exits++
                    j++
                }
                val exitName = if (exitEdge >= 0) graph.edgeName(exitEdge) else null
                val text = when {
                    exitEdge < 0 -> "Enter the roundabout"
                    exitName != null -> "Take exit $exits at the roundabout onto $exitName"
                    else -> "Take exit $exits at the roundabout"
                }
                out.add(Maneuver(ManeuverType.ROUNDABOUT, idx, dist, exitName, if (exitEdge >= 0) exits else null, text))
                // The exit transition is covered by this instruction.
                i = if (exitEdge >= 0) j + 2 else edges.size
                continue
            }
            if (curFlags and EdgeFlags.ROUNDABOUT != 0) { i++; continue }

            val prevLink = prevFlags and EdgeFlags.LINK != 0
            val curLink = curFlags and EdgeFlags.LINK != 0
            val prevHighway = graph.edgeRoadClass(prev) <= 1
            val curHighway = graph.edgeRoadClass(cur) <= 1
            val leftmost = others.all { it > delta }
            val rightmost = others.all { it < delta }
            val sideRight = when {
                others.isEmpty() -> delta >= 0
                leftmost && !rightmost -> false
                rightmost && !leftmost -> true
                else -> delta >= 0
            }
            val side = if (sideRight) "right" else "left"

            val m: Maneuver? = when {
                !prevLink && curLink -> {
                    if (others.isEmpty() && abs(delta) <= STRAIGHT_DEG) null
                    else {
                        val toward = towardName(graph, edges, i)
                        val what = if (prevHighway) "exit" else "ramp"
                        val text = "Take the $what on the $side" + (toward?.let { " toward $it" } ?: "")
                        Maneuver(if (sideRight) ManeuverType.RAMP_RIGHT else ManeuverType.RAMP_LEFT, idx, dist, toward, null, text)
                    }
                }
                prevLink && !curLink && curHighway ->
                    Maneuver(ManeuverType.MERGE, idx, dist, curName, null, if (curName != null) "Merge onto $curName" else "Merge")
                // Driving past an exit ramp or a side road is not a fork; only a split into two
                // carriageways is, or else every interchange would say "Keep left".
                others.isNotEmpty() && ((prevLink && curLink) ||
                    (prevHighway && curHighway && !prevLink && !curLink && highwayFork)) -> {
                    val type = if (sideRight) ManeuverType.KEEP_RIGHT else ManeuverType.KEEP_LEFT
                    if (curLink) {
                        val toward = towardName(graph, edges, i)
                        Maneuver(type, idx, dist, toward, null, "Keep $side" + (toward?.let { " toward $it" } ?: ""))
                    } else {
                        Maneuver(type, idx, dist, curName, null, "Keep $side" + (curName?.let { " onto $it" } ?: " at the fork"))
                    }
                }
                else -> generalTurn(graph, prev, prevFrom, cur, delta, others, prevName, curName, idx, dist)
            }
            if (m != null) out.add(m)
            i++
        }

        val lastName = graph.edgeName(edges[edges.size - 1])
        out.add(Maneuver(ManeuverType.ARRIVE, last, cum[last], lastName, null, "Arrive at your destination"))
        return out
    }

    private fun generalTurn(
        graph: RoadGraph, prev: Int, prevFrom: Int, cur: Int, delta: Double, others: List<Double>,
        prevName: String?, curName: String?, idx: Int, dist: Double,
    ): Maneuver? {
        // A hairpin with no other way on is a bend in the road, not a U-turn the driver chooses.
        val uturn = graph.isReverseOf(prev, prevFrom, cur) || (abs(delta) > UTURN_DEG && others.isNotEmpty())
        val type = when {
            uturn -> ManeuverType.UTURN
            abs(delta) > UTURN_DEG -> if (delta > 0) ManeuverType.SHARP_RIGHT else ManeuverType.SHARP_LEFT
            else -> classify(delta)
        }
        val renamed = curName != null && curName != prevName
        if (type == ManeuverType.STRAIGHT) {
            return if (renamed) Maneuver(type, idx, dist, curName, null, "Continue onto $curName") else null
        }
        if (!uturn) {
            // The road simply bends here: nothing else to take, or the same street carries on with
            // no rival branch that is closer to straight ahead.
            if (others.isEmpty() && !renamed) return null
            val slight = type == ManeuverType.SLIGHT_LEFT || type == ManeuverType.SLIGHT_RIGHT
            if (slight && !renamed && others.none { abs(it) < abs(delta) }) return null
        }
        val verb = when (type) {
            ManeuverType.SLIGHT_LEFT -> "Slight left"
            ManeuverType.LEFT -> "Turn left"
            ManeuverType.SHARP_LEFT -> "Sharp left"
            ManeuverType.SLIGHT_RIGHT -> "Slight right"
            ManeuverType.RIGHT -> "Turn right"
            ManeuverType.SHARP_RIGHT -> "Sharp right"
            else -> "Make a U-turn"
        }
        return Maneuver(type, idx, dist, curName, null, if (curName != null) "$verb onto $curName" else verb)
    }

    /** Turn type for a signed heading change (positive = right). */
    internal fun classify(delta: Double): ManeuverType {
        val a = abs(delta)
        return when {
            a <= STRAIGHT_DEG -> ManeuverType.STRAIGHT
            a > UTURN_DEG -> ManeuverType.UTURN
            delta > 0 -> when {
                a <= SLIGHT_DEG -> ManeuverType.SLIGHT_RIGHT
                a <= TURN_DEG -> ManeuverType.RIGHT
                else -> ManeuverType.SHARP_RIGHT
            }
            else -> when {
                a <= SLIGHT_DEG -> ManeuverType.SLIGHT_LEFT
                a <= TURN_DEG -> ManeuverType.LEFT
                else -> ManeuverType.SHARP_LEFT
            }
        }
    }

    private fun hasExit(graph: RoadGraph, node: Int): Boolean {
        for (o in graph.firstEdge(node) until graph.lastEdgeExclusive(node)) {
            if (graph.edgeFlags(o) and EdgeFlags.ROUNDABOUT == 0) return true
        }
        return false
    }

    /** Name of the road a chain of ramps starting at [i] leads to, or the ramp's own name. */
    private fun towardName(graph: RoadGraph, edges: IntArray, i: Int): String? {
        var j = i
        while (j < edges.size && graph.edgeFlags(edges[j]) and EdgeFlags.LINK != 0) j++
        if (j < edges.size) graph.edgeName(edges[j])?.let { return it }
        return graph.edgeName(edges[i])
    }

    private fun bearingIn(geometry: List<LatLon>, cum: DoubleArray, idx: Int, lowerBound: Int): Double {
        var j = idx - 1
        while (j > lowerBound && cum[idx] - cum[j] < BEARING_SPAN_M) j--
        if (j < 0) j = 0
        if (j == idx) return 0.0
        return RoadGeo.bearingDeg(geometry[j].lat, geometry[j].lon, geometry[idx].lat, geometry[idx].lon)
    }

    private fun bearingOut(geometry: List<LatLon>, cum: DoubleArray, idx: Int, upperBound: Int): Double {
        val last = geometry.size - 1
        val bound = upperBound.coerceIn(idx, last)
        var j = idx + 1
        while (j < bound && cum[j] - cum[idx] < BEARING_SPAN_M) j++
        if (j > last) j = last
        if (j == idx) return 0.0
        return RoadGeo.bearingDeg(geometry[idx].lat, geometry[idx].lon, geometry[j].lat, geometry[j].lon)
    }

    private fun cardinal(b: Double): String {
        val names = arrayOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
        return names[(((b + 22.5) % 360) / 45).toInt().coerceIn(0, 7)]
    }
}
