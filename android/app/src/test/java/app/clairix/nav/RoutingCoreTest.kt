package app.clairix.nav

import app.clairix.nav.TestCity.COLS
import app.clairix.nav.TestCity.edge
import app.clairix.nav.TestCity.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.math.abs

class RoutingCoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var graph: RoadGraph
    private val depart = 1_760_500_000L

    private class MapSpeeds(val live: MutableMap<String, Double> = HashMap()) : SpeedSource {
        override fun liveKph(segmentKey: String) = live[segmentKey]
        override fun typicalKph(segmentKey: String, epochSec: Long): Double? = null
    }

    @Before
    fun setUp() {
        graph = RoadGraph.open(TestCity.write(tmp.root))
    }

    // ── RoadGraph ──────────────────────────────────────────────────────────

    @Test
    fun headerAndSectionsParse() {
        assertEquals("test-city", graph.region)
        assertEquals(TestCity.ROWS * COLS + 2, graph.nodeCount)
        // 5 rows × 4 links, 3rd Street one-way; 5 columns × 4 links two-way; motorway and two ramps.
        assertEquals(4 * 4 * 2 + 4 + 5 * 4 * 2 + 3, graph.edgeCount)
        assertEquals(1_760_000_000L, graph.builtAtEpochSec)
        assertEquals(TestCity.LAT0 - TestCity.DLAT - 0.0002, graph.minLat, 1e-7)
        assertEquals(TestCity.lat(TestCity.ROWS - 1), graph.maxLat, 1e-7)
        assertEquals(TestCity.lat(3), graph.nodeLat(node(3, 1)), 1e-7)
        assertEquals(TestCity.lon(1), graph.nodeLon(node(3, 1)), 1e-7)
        for (e in 0 until graph.edgeCount) {
            val from = graph.edgeFrom(e)
            assertTrue(e >= graph.firstEdge(from) && e < graph.lastEdgeExclusive(from))
        }
    }

    @Test
    fun edgeAttributesParse() {
        val east = edge(graph, node(0, 1), node(0, 2))
        val west = edge(graph, node(0, 2), node(0, 1))
        assertEquals("1st Street", graph.edgeName(east))
        assertEquals("${TestCity.ROW_WAY_BASE}:1", graph.segmentKey(east))
        assertEquals("${TestCity.ROW_WAY_BASE}:0", graph.segmentKey(west))
        assertTrue(graph.edgeFlags(east) and EdgeFlags.SIGNALS != 0)
        assertTrue(graph.edgeFlags(west) and EdgeFlags.SIGNALS == 0)
        assertEquals(6, graph.edgeRoadClass(east))
        assertEquals(200.2, graph.edgeLengthM(east), 0.5)

        val motorway = edge(graph, TestCity.HW, TestCity.HE)
        assertEquals("I-71", graph.edgeName(motorway))
        assertEquals(113, graph.edgeMaxspeedKph(motorway))
        assertEquals(0, graph.edgeRoadClass(motorway))
        assertTrue(graph.edgeFlags(motorway) and EdgeFlags.TOLL != 0)
        assertEquals(3, graph.edgeGeometry(motorway).size)
        assertEquals(TestCity.LAT0 - TestCity.DLAT - 0.0002, graph.edgeGeometry(motorway)[1].lat, 1e-7)

        val ramp = edge(graph, node(0, 0), TestCity.HW)
        assertEquals(null, graph.edgeName(ramp))
        assertTrue(graph.edgeFlags(ramp) and EdgeFlags.LINK != 0)

        // 3rd Street is one-way eastbound: no edge leads west along it.
        val r = TestCity.ONEWAY_ROW
        assertTrue((graph.firstEdge(node(r, 2)) until graph.lastEdgeExclusive(node(r, 2))).none { graph.edgeTo(it) == node(r, 1) })
    }

    @Test(expected = IOException::class)
    fun aTruncatedFileIsRejected() {
        val good = TestCity.write(tmp.newFolder())
        val bad = File(tmp.root, "bad.agr")
        bad.writeBytes(good.readBytes().copyOf(200))
        RoadGraph.open(bad)
    }

    @Test
    fun nearestEdgesProjectsOntoTheRightSegment() {
        val p = TestCity.eastOf(0, 1, 100.0)
        val north5 = p.lat + 5.0 / RoadGeo.M_PER_DEG_LAT
        val hits = graph.nearestEdges(north5, p.lon, 30.0, 4)
        assertEquals(2, hits.size)
        val ids = hits.map { it.edge }.toSet()
        assertEquals(setOf(edge(graph, node(0, 1), node(0, 2)), edge(graph, node(0, 2), node(0, 1))), ids)
        for (h in hits) {
            assertEquals(5.0, h.distanceM, 0.1)
            assertEquals(0.5, h.fraction, 0.01)
        }
        val east = hits.first { graph.edgeTo(it.edge) == node(0, 2) }
        val west = hits.first { graph.edgeTo(it.edge) == node(0, 1) }
        assertEquals(90.0, east.bearingDeg, 0.5)
        assertEquals(270.0, west.bearingDeg, 0.5)
        assertTrue(graph.nearestEdges(north5 + 0.01, p.lon, 30.0, 4).isEmpty())
    }

    // ── Router ─────────────────────────────────────────────────────────────

    private val start = TestCity.eastOf(0, 0, 40.0)
    private val end = TestCity.eastOf(0, COLS - 1, -40.0)

    private fun route(router: Router, profile: RouteProfile) =
        router.route(start.lat, start.lon, end.lat, end.lon, profile, depart)

    @Test
    fun everyProfileFindsARoute() {
        val router = Router(graph, MapSpeeds())
        for (p in RouteProfile.values()) {
            val r = route(router, p)
            assertNotNull("profile $p", r)
            r!!
            assertEquals(p, r.profile)
            assertTrue(r.distanceM > 700)
            assertTrue(r.durationSec > 0 && r.fuelLiters > 0)
            assertEquals(start.lat, r.geometry.first().lat, 1e-6)
            assertEquals(start.lon, r.geometry.first().lon, 1e-6)
            assertEquals(end.lon, r.geometry.last().lon, 1e-6)
            assertEquals(ManeuverType.DEPART, r.maneuvers.first().type)
            assertEquals(ManeuverType.ARRIVE, r.maneuvers.last().type)
            for (i in 1 until r.edges.size) assertEquals(graph.edgeTo(r.edges[i - 1]), graph.edgeFrom(r.edges[i]))
        }
    }

    @Test
    fun fastestTakesTheTolledMotorway() {
        val r = route(Router(graph, MapSpeeds()), RouteProfile.FASTEST)!!
        assertTrue(r.edges.contains(edge(graph, TestCity.HW, TestCity.HE)))
        assertEquals(graph.edgeLengthM(edge(graph, TestCity.HW, TestCity.HE)), r.tollM, 0.01)
        assertTrue(r.maneuvers.any { it.type == ManeuverType.MERGE && it.instruction == "Merge onto I-71" })
        assertTrue(r.maneuvers.any { it.type == ManeuverType.RAMP_LEFT && it.instruction == "Take the ramp on the left toward I-71" })
        assertTrue(r.maneuvers.any { it.type == ManeuverType.LEFT && it.instruction == "Turn left onto 1st Street" })
    }

    @Test
    fun noTollsAvoidsTheTollWhenAStreetRouteExists() {
        val r = route(Router(graph, MapSpeeds()), RouteProfile.NO_TOLLS)!!
        assertEquals(0.0, r.tollM, 0.0)
        assertTrue(r.edges.none { graph.edgeFlags(it) and EdgeFlags.TOLL != 0 })
        // Along 1st Street, through the signal.
        assertEquals(TestCity.DLON * 4 * 85_200 - 80, r.distanceM, 5.0)
        assertTrue(r.durationSec >= 15.0 + r.distanceM / (30 / 3.6) - 1)
    }

    @Test
    fun noHighwaysStaysOnStreets() {
        val r = route(Router(graph, MapSpeeds()), RouteProfile.NO_HIGHWAYS)!!
        assertTrue(r.edges.none { graph.edgeRoadClass(it) <= 1 })
    }

    @Test
    fun shortestIsNoLongerThanFastest() {
        val router = Router(graph, MapSpeeds())
        val fastest = route(router, RouteProfile.FASTEST)!!
        val shortest = route(router, RouteProfile.SHORTEST)!!
        assertTrue(shortest.distanceM <= fastest.distanceM)
        assertTrue(shortest.durationSec >= fastest.durationSec)
    }

    @Test
    fun aJamOnTheMotorwayFlipsTheFastestRoute() {
        val speeds = MapSpeeds()
        val router = Router(graph, speeds)
        val before = route(router, RouteProfile.FASTEST)!!
        assertTrue(before.tollM > 0)
        speeds.live[graph.segmentKey(edge(graph, TestCity.HW, TestCity.HE))] = 8.0
        val after = route(router, RouteProfile.FASTEST)!!
        assertEquals(0.0, after.tollM, 0.0)
        assertTrue(after.durationSec > before.durationSec)
        // Free-flow time ignores the jam, so it no longer equals the live estimate on the motorway.
        assertTrue(before.freeFlowDurationSec <= before.durationSec + 1e-6)
    }

    @Test
    fun oneWayStreetForcesALoop() {
        val r = TestCity.ONEWAY_ROW
        val from = TestCity.eastOf(r, COLS - 1, -40.0)
        val to = TestCity.eastOf(r, 0, 40.0)
        val route = Router(graph, MapSpeeds()).route(from.lat, from.lon, to.lat, to.lon, RouteProfile.SHORTEST, depart)!!
        // East to the corner, around a neighbouring street, back down and east again.
        assertTrue(route.distanceM > 1_200)
        for (e in route.edges) {
            if (graph.edgeWayId(e) == TestCity.ROW_WAY_BASE + r) {
                assertTrue(graph.edgeFlags(e) and EdgeFlags.FORWARD != 0)
            }
        }
    }

    @Test
    fun alternativesAreDistinctAndSortedByDuration() {
        val alts = Router(graph, MapSpeeds()).alternatives(start.lat, start.lon, end.lat, end.lon, depart)
        assertEquals(2, alts.size)
        assertTrue(alts[0].durationSec <= alts[1].durationSec)
        assertTrue(alts[0].tollM > 0)
        assertEquals(0.0, alts[1].tollM, 0.0)
    }

    @Test
    fun sameEdgeTripIsAPartialEdge() {
        val a = TestCity.eastOf(1, 1, 30.0)
        val b = TestCity.eastOf(1, 1, 150.0)
        val r = Router(graph, MapSpeeds()).route(a.lat, a.lon, b.lat, b.lon, RouteProfile.FASTEST, depart)!!
        assertEquals(1, r.edges.size)
        assertEquals(120.0, r.distanceM, 1.0)
    }

    // ── VehicleModel ───────────────────────────────────────────────────────

    @Test
    fun vehicleCurveIsRealistic() {
        val v = VehicleModel.DEFAULT
        val f = v.litersPer100kmAt
        val min = (20..140).minByOrNull { f(it.toDouble()) }!!
        assertTrue("minimum at $min", min in 70..80)
        assertEquals(7.35, f(77.7), 0.01)
        assertTrue(f(130.0) > f(100.0))
        assertTrue(f(20.0) > f(50.0))
        assertEquals(0.8, v.idleLph, 0.0)
        assertTrue(v.stopPenaltyLiters in 0.01..0.03)
    }

    // ── Maneuvers ──────────────────────────────────────────────────────────

    private fun maneuversFor(vararg nodes: Int): List<Maneuver> {
        val edges = IntArray(nodes.size - 1) { edge(graph, nodes[it], nodes[it + 1]) }
        val geometry = ArrayList<LatLon>()
        val starts = IntArray(edges.size)
        for ((i, e) in edges.withIndex()) {
            val g = graph.edgeGeometry(e)
            if (geometry.isEmpty()) { starts[i] = 0; geometry.addAll(g) } else { starts[i] = geometry.size - 1; geometry.addAll(g.drop(1)) }
        }
        return Maneuvers.build(graph, edges, geometry, starts)
    }

    @Test
    fun eastboundThenNorthIsALeftTurn() {
        val m = maneuversFor(node(1, 1), node(1, 2), node(2, 2))
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.LEFT, ManeuverType.ARRIVE), m.map { it.type })
        assertEquals("Head east on 2nd Street", m[0].instruction)
        assertEquals("Turn left onto C Avenue", m[1].instruction)
        assertEquals("C Avenue", m[1].streetName)
        assertEquals(1, m[1].atIndex)
        assertEquals(200.2, m[1].distanceFromStartM, 0.5)
    }

    @Test
    fun westboundThenNorthIsARightTurn() {
        val m = maneuversFor(node(1, 3), node(1, 2), node(2, 2))
        assertEquals(ManeuverType.RIGHT, m[1].type)
        assertEquals("Turn right onto C Avenue", m[1].instruction)
    }

    @Test
    fun goingStraightThroughAJunctionNeedsNoInstruction() {
        val m = maneuversFor(node(1, 0), node(1, 1), node(1, 2))
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), m.map { it.type })
    }

    @Test
    fun turnClassificationBoundaries() {
        assertEquals(ManeuverType.STRAIGHT, Maneuvers.classify(10.0))
        assertEquals(ManeuverType.SLIGHT_RIGHT, Maneuvers.classify(30.0))
        assertEquals(ManeuverType.RIGHT, Maneuvers.classify(90.0))
        assertEquals(ManeuverType.SHARP_RIGHT, Maneuvers.classify(150.0))
        assertEquals(ManeuverType.SLIGHT_LEFT, Maneuvers.classify(-30.0))
        assertEquals(ManeuverType.LEFT, Maneuvers.classify(-90.0))
        assertEquals(ManeuverType.SHARP_LEFT, Maneuvers.classify(-150.0))
        assertEquals(ManeuverType.UTURN, Maneuvers.classify(178.0))
    }

    // ── MapMatcher ─────────────────────────────────────────────────────────

    @Test
    fun headingPicksTheDirectionOfATwoWayStreet() {
        val p = TestCity.eastOf(0, 1, 100.0)
        val lat = p.lat + 3.0 / RoadGeo.M_PER_DEG_LAT
        val matcher = MapMatcher(graph)
        val east = matcher.match(lat, p.lon, 90f, 12f, 1_000L)!!
        assertEquals(node(0, 2), graph.edgeTo(east.edge))
        matcher.reset()
        val west = matcher.match(lat, p.lon, 268f, 12f, 1_000L)!!
        assertEquals(node(0, 1), graph.edgeTo(west.edge))
        assertEquals(3.0, west.distanceM, 0.1)
    }

    @Test
    fun continuityHoldsTheEdgeWhenHeadingIsUnreliable() {
        val matcher = MapMatcher(graph)
        val a = TestCity.eastOf(0, 1, 60.0)
        val first = matcher.match(a.lat, a.lon, 270f, 12f, 1_000L)!!
        assertEquals(node(0, 1), graph.edgeTo(first.edge))
        // Crawling at walking pace: the GPS course is noise, so the previous match should carry over.
        val b = TestCity.eastOf(0, 1, 58.0)
        val second = matcher.match(b.lat, b.lon, 90f, 0.5f, 2_000L)!!
        assertEquals(first.edge, second.edge)
        assertFalse(abs(second.fraction - first.fraction) > 0.05)
        assertTrue(matcher.match(b.lat + 0.01, b.lon, null, null, 3_000L) == null)
    }
}
