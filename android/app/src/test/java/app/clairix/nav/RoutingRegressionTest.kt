package app.clairix.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoutingRegressionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val depart = 1_760_500_000L
    private val fwd = EdgeFlags.FORWARD
    private val residentialMps = 30.0 / 3.6

    private fun maneuvers(g: RoadGraph, edges: IntArray): List<Maneuver> {
        val geometry = ArrayList<LatLon>()
        val starts = IntArray(edges.size)
        for ((i, e) in edges.withIndex()) {
            val pts = g.edgeGeometry(e)
            if (geometry.isEmpty()) { starts[i] = 0; geometry.addAll(pts) } else { starts[i] = geometry.size - 1; geometry.addAll(pts.drop(1)) }
        }
        return Maneuvers.build(g, edges, geometry, starts)
    }

    /**
     * A closed two-way loop with only two graph nodes (A, B): the north arc A→B and the south arc
     * B→A are both forward segments of the same way. Carrying on around the loop at B used to be
     * mistaken for a U-turn because the edges share way id and end nodes.
     */
    @Test
    fun continuingAroundATwoNodeLoopIsNotAUturn() {
        val m = MiniGraph()
        val s = m.node(40.0, -83.002)
        val a = m.node(40.0, -83.001)
        val b = m.node(40.0, -82.999)
        val north = listOf(LatLon(40.001, -83.0))
        val south = listOf(LatLon(39.999, -83.0))
        m.edge(a, b, 10, "Loop Court", 6, fwd, north)
        m.edge(b, a, 10, "Loop Court", 6, 0, north.reversed())
        m.edge(b, a, 10, "Loop Court", 6, fwd, south)
        m.edge(a, b, 10, "Loop Court", 6, 0, south.reversed())
        m.edge(s, a, 11, "Access Road", 6, fwd)
        m.edge(a, s, 11, "Access Road", 6, 0)
        val g = m.open(tmp.root)

        val northFwd = MiniGraph.find(g, a, b, 10, true)
        val northBack = MiniGraph.find(g, b, a, 10, false)
        val southFwd = MiniGraph.find(g, b, a, 10, true)
        assertTrue(g.isReverseOf(northFwd, a, northBack))
        assertFalse(g.isReverseOf(northFwd, a, southFwd))

        val types = maneuvers(g, intArrayOf(MiniGraph.find(g, s, a, 11), northFwd, southFwd)).map { it.type }
        assertFalse(types.toString(), ManeuverType.UTURN in types)

        // From the top of the loop to the bottom: half an arc each way round. Via B there is no turn
        // choice at all, so the trip must cost exactly its driving time with no 30 s U-turn added.
        val route = Router(g, SpeedSource.NONE).route(40.001, -83.0, 39.999, -83.0, RouteProfile.FASTEST, depart)
        assertNotNull(route)
        assertTrue(route!!.edges.contentToString(), southFwd in route.edges)
        val expected = (g.edgeLengthM(northFwd) + g.edgeLengthM(southFwd)) / 2 / residentialMps
        assertEquals(expected, route.durationSec, 1.0)
    }

    private fun motorway(withFork: Boolean, name: String): Triple<RoadGraph, IntArray, Int> {
        val m = MiniGraph()
        val m0 = m.node(40.0, -83.02)
        val m1 = m.node(40.0, -83.0)
        val m2 = m.node(39.998, -82.98)
        val ramp = m.node(39.995, -82.99)
        val side = m.node(40.002, -83.0)
        m.edge(m0, m1, 1, "I-71", 0, fwd, maxspeed = 113)
        m.edge(m1, m2, 1, "I-71", 0, fwd, maxspeed = 113)
        m.edge(m1, ramp, 2, null, 0, fwd or EdgeFlags.LINK, maxspeed = 80)
        // A side road leaving at right angles, as on any at-grade trunk road.
        m.edge(m1, side, 3, "Farm Lane", 6, fwd)
        if (withFork) {
            val f = m.node(40.003, -82.99)
            m.edge(m1, f, 4, "I-670", 0, fwd, maxspeed = 113)
        }
        val g = m.open(tmp.root, name)
        return Triple(g, intArrayOf(MiniGraph.find(g, m0, m1, 1), MiniGraph.find(g, m1, m2, 1)), m1)
    }

    /** Staying on the motorway past an exit ramp (or a side road) used to say "Keep left onto I-71". */
    @Test
    fun drivingPastAnExitGivesNoKeepInstruction() {
        val (g, edges, _) = motorway(withFork = false, name = "exit.agr")
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), maneuvers(g, edges).map { it.type })
    }

    @Test
    fun aRealMotorwaySplitStillSaysKeep() {
        val (g, edges, _) = motorway(withFork = true, name = "fork.agr")
        val m = maneuvers(g, edges)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.KEEP_RIGHT, ManeuverType.ARRIVE), m.map { it.type })
        assertEquals("Keep right onto I-71", m[1].instruction)
    }

    /**
     * A hairpin where one way hands over to the next (no other road at the apex) is a bend: it
     * used to cost the 30 s U-turn penalty and be announced as "Make a U-turn".
     */
    @Test
    fun aHairpinWithNoOtherWayOnIsABendNotAUturn() {
        val m = MiniGraph()
        val p0 = m.node(40.0, -83.0)
        val p1 = m.node(40.009, -83.0)
        val p2 = m.node(40.0, -82.9988)
        m.edge(p0, p1, 1, "Hill Road", 6, fwd)
        m.edge(p1, p2, 2, "Hill Road", 6, fwd)
        val g = m.open(tmp.root)
        val e1 = MiniGraph.find(g, p0, p1, 1)
        val e2 = MiniGraph.find(g, p1, p2, 2)
        assertTrue(kotlin.math.abs(RoadGeo.deltaDeg(g.endBearing(e1, p0), g.startBearing(e2, p1))) > 170)

        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), maneuvers(g, intArrayOf(e1, e2)).map { it.type })

        val route = Router(g, SpeedSource.NONE).route(40.0045, -83.0, 40.0045, -82.9994, RouteProfile.FASTEST, depart)
        assertNotNull(route)
        assertEquals(listOf(e1, e2), route!!.edges.toList())
        val expected = (g.edgeLengthM(e1) + g.edgeLengthM(e2)) / 2 / residentialMps
        assertEquals(expected, route.durationSec, 1.0)
    }

    /** A NaN live speed used to slip through coerceIn and make the edge's cost NaN. */
    @Test
    fun nanLiveSpeedFallsBackToFreeFlow() {
        val m = MiniGraph()
        val p0 = m.node(40.0, -83.0)
        val p1 = m.node(40.0, -82.99)
        m.edge(p0, p1, 1, "Long Road", 6, fwd)
        val g = m.open(tmp.root)
        val nan = object : SpeedSource {
            override fun liveKph(segmentKey: String) = Double.NaN
            override fun typicalKph(segmentKey: String, epochSec: Long): Double? = null
        }
        val route = Router(g, nan).route(40.0, -82.998, 40.0, -82.992, RouteProfile.FASTEST, depart)
        assertNotNull(route)
        assertFalse(route!!.durationSec.isNaN())
        assertEquals(route.distanceM / residentialMps, route.durationSec, 0.5)
    }
}
