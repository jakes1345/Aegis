package com.xat.aegis.nav

import com.xat.aegis.nav.TestCity.COLS
import com.xat.aegis.nav.TestCity.edge
import com.xat.aegis.nav.TestCity.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoadClosuresTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var graph: RoadGraph
    private lateinit var closures: RoadClosures
    private val now = 1_760_500_000L

    private val start = TestCity.eastOf(0, 0, 40.0)
    private val end = TestCity.eastOf(0, COLS - 1, -40.0)

    @Before
    fun setUp() {
        graph = RoadGraph.open(TestCity.write(tmp.root))
        closures = RoadClosures(graph)
    }

    /** The I-71 motorway as a feed would draw it: west end, the bend, east end. */
    private fun motorwayGeom(): List<LatLon> {
        val g = graph.edgeGeometry(edge(graph, TestCity.HW, TestCity.HE))
        return g.map { LatLon(it.lat, it.lon) }
    }

    /** 1st Street from just east of A Avenue to just west of D Avenue, eastward. */
    private fun firstStreetGeom(): List<LatLon> =
        listOf(TestCity.eastOf(0, 0, 20.0), TestCity.eastOf(0, 1, 0.0), TestCity.eastOf(0, 2, 0.0), TestCity.eastOf(0, 3, -20.0))

    private fun zone(
        id: String, type: String, impact: String, geom: List<LatLon>, direction: String? = null,
        speedKph: Double? = null, start: Long? = now - 3600, end: Long? = now + 3600,
    ) = WorkZone(id, type, impact, speedKph, "Road", direction, "Work", start, end, geom, "Test DOT")

    private fun fastest(router: Router, profile: RouteProfile = RouteProfile.FASTEST) =
        router.route(start.lat, start.lon, end.lat, end.lon, profile, now)!!

    @Test
    fun aClosureOnTheFastestPathForcesTheAlternative() {
        val router = Router(graph, SpeedSource.NONE, restrictions = closures)
        val before = fastest(router)
        val motorway = edge(graph, TestCity.HW, TestCity.HE)
        assertTrue(before.edges.contains(motorway))

        closures.update(listOf(zone("oh:1", WorkZone.CLOSURE, WorkZone.IMPACT_ALL_CLOSED, motorwayGeom(), "eastbound")), emptyList(), now)
        assertTrue(closures.isClosed(motorway))

        val after = fastest(router)
        assertFalse(after.edges.contains(motorway))
        assertEquals(0.0, after.tollM, 0.0)
        assertTrue(after.durationSec > before.durationSec)
        for (r in router.alternatives(start.lat, start.lon, end.lat, end.lon, now)) assertFalse(r.edges.contains(motorway))
    }

    @Test
    fun aProviderClosureWithGeometryClosesTheRoadToo() {
        val router = Router(graph, SpeedSource.NONE, restrictions = closures)
        val inc = Incident(
            id = "here:42", type = Incident.CLOSURE, lat = motorwayGeom()[0].lat, lon = motorwayGeom()[0].lon,
            seg = null, source = "here", confirmations = 0, createdAt = 0, expiresAt = now + 600,
            description = "Closed", geom = motorwayGeom(), origin = Incident.Origin.PROVIDER,
        )
        closures.update(emptyList(), listOf(inc), now)
        assertFalse(fastest(router).edges.contains(edge(graph, TestCity.HW, TestCity.HE)))
    }

    @Test
    fun aSpeedCapIncreasesTheEta() {
        val router = Router(graph, SpeedSource.NONE, restrictions = closures)
        val before = fastest(router, RouteProfile.NO_TOLLS)
        closures.update(
            listOf(zone("oh:2", WorkZone.LANE_CLOSURE, WorkZone.IMPACT_SOME_CLOSED, firstStreetGeom(), speedKph = 8.0)),
            emptyList(), now,
        )
        val capped = edge(graph, node(0, 1), node(0, 2))
        assertEquals(8.0, closures.speedCapKph(capped)!!, 1e-3)
        assertFalse(closures.isClosed(capped))
        val after = fastest(router, RouteProfile.NO_TOLLS)
        assertTrue("ETA ${after.durationSec} should exceed ${before.durationSec}", after.durationSec > before.durationSec + 30)
    }

    @Test
    fun laneClosuresAndWorkZonesWithoutASpeedUseFreeFlowShares() {
        val laneEdge = edge(graph, node(0, 1), node(0, 2))
        closures.update(listOf(zone("oh:3", WorkZone.LANE_CLOSURE, WorkZone.IMPACT_SOME_CLOSED, firstStreetGeom())), emptyList(), now)
        assertEquals(freeFlowKph(graph, laneEdge) * 0.6, closures.speedCapKph(laneEdge)!!, 1e-3)
        closures.update(listOf(zone("oh:4", WorkZone.WORK_ZONE, WorkZone.IMPACT_SHOULDER, firstStreetGeom())), emptyList(), now)
        assertEquals(freeFlowKph(graph, laneEdge) * 0.85, closures.speedCapKph(laneEdge)!!, 1e-3)
    }

    @Test
    fun anExpiredWorkZoneHasNoEffect() {
        val router = Router(graph, SpeedSource.NONE, restrictions = closures)
        val before = fastest(router)
        val set = closures.update(
            listOf(zone("oh:5", WorkZone.CLOSURE, WorkZone.IMPACT_ALL_CLOSED, motorwayGeom(), "eastbound", start = now - 7200, end = now - 60)),
            emptyList(), now,
        )
        assertEquals(0, set.size)
        val after = fastest(router)
        assertTrue(after.edges.contentEquals(before.edges))
        assertEquals(before.durationSec, after.durationSec, 1e-9)
    }

    @Test
    fun aZoneThatHasNotStartedHasNoEffect() {
        val set = closures.update(
            listOf(zone("oh:6", WorkZone.CLOSURE, WorkZone.IMPACT_ALL_CLOSED, motorwayGeom(), start = now + 3600, end = now + 7200)),
            emptyList(), now,
        )
        assertEquals(0, set.size)
    }

    @Test
    fun directionLimitsTheMatchToOneSideOfTheRoad() {
        val east = (0 until 3).map { edge(graph, node(0, it), node(0, it + 1)) }
        val west = (0 until 3).map { edge(graph, node(0, it + 1), node(0, it)) }

        val oneWay = closures.match(firstStreetGeom(), "eastbound", directional = true).toSet()
        assertTrue(oneWay.containsAll(east))
        assertTrue(west.none { it in oneWay })
        // Avenues crossing 1st Street are perpendicular to the work and never match.
        assertTrue(oneWay.all { graph.edgeWayId(it) == TestCity.ROW_WAY_BASE })

        val both = closures.match(firstStreetGeom(), null, directional = false).toSet()
        assertTrue(both.containsAll(east) && both.containsAll(west))
        assertTrue(both.all { graph.edgeWayId(it) == TestCity.ROW_WAY_BASE })
    }

    @Test
    fun aGeometryDrawnAgainstItsStatedDirectionIsReversed() {
        val drawnWest = firstStreetGeom().reversed()
        val m = closures.match(drawnWest, "eastbound", directional = true).toSet()
        assertTrue(m.contains(edge(graph, node(0, 1), node(0, 2))))
        assertFalse(m.contains(edge(graph, node(0, 2), node(0, 1))))
    }

    @Test
    fun aGeometryCrossingARoadDoesNotMatchIt() {
        // 22 m running north across 2nd Street: within reach of the street at every sample, but at right angles to it.
        val c = TestCity.eastOf(1, 1, 100.0)
        val geom = listOf(LatLon(c.lat - 0.0001, c.lon), LatLon(c.lat + 0.0001, c.lon))
        assertEquals(0, closures.match(geom, null, directional = false).size)
    }

    @Test
    fun aZoneEndingAtJunctionsDoesNotSpillOntoTheNextBlocks() {
        // 1st Street exactly from B Avenue to C Avenue: the blocks either side carry on straight from its ends.
        val geom = listOf(TestCity.eastOf(0, 1, 0.0), TestCity.eastOf(0, 2, 0.0))
        val block = edge(graph, node(0, 1), node(0, 2))
        assertEquals(setOf(block), closures.match(geom, "eastbound", directional = true).toSet())
        assertEquals(
            setOf(block, edge(graph, node(0, 2), node(0, 1))),
            closures.match(geom, null, directional = false).toSet(),
        )
    }

    @Test
    fun aClosedMainlineDoesNotCloseARampLeavingItAtAShallowAngle() {
        val m = MiniGraph()
        val west = m.node(40.0, -83.01)
        val gore = m.node(40.0, -83.0)
        val east = m.node(40.0, -82.99)
        // The exit ramp leaves the mainline 10 degrees to the right and runs 300 m.
        val rampEnd = m.node(40.0 - 300 * Math.sin(Math.toRadians(10.0)) / RoadGeo.M_PER_DEG_LAT,
            -83.0 + 300 * Math.cos(Math.toRadians(10.0)) / (RoadGeo.M_PER_DEG_LAT * Math.cos(Math.toRadians(40.0))))
        m.edge(west, gore, 1, "I-80", 0, EdgeFlags.FORWARD, maxspeed = 113)
        m.edge(gore, east, 1, "I-80", 0, EdgeFlags.FORWARD, maxspeed = 113)
        m.edge(gore, rampEnd, 2, null, 0, EdgeFlags.FORWARD or EdgeFlags.LINK, maxspeed = 80)
        val g = m.open(tmp.root, "ramp.agr")
        val matched = RoadClosures(g).match(listOf(LatLon(40.0, -83.01), LatLon(40.0, -82.99)), "eastbound", directional = true).toSet()
        assertEquals(setOf(MiniGraph.find(g, west, gore, 1), MiniGraph.find(g, gore, east, 1)), matched)
    }

    @Test
    fun aShortBlockBetweenSamplesInsideAZoneIsClosedToo() {
        // A 6 m edge (two junctions close together) inside a long closure falls between 15 m samples.
        val m = MiniGraph()
        val mPerDegLon = RoadGeo.M_PER_DEG_LAT * Math.cos(Math.toRadians(40.0))
        val xs = listOf(0.0, 200.0, 206.0, 400.0)
        val ns = xs.map { m.node(40.0, -83.0 + it / mPerDegLon) }
        for (i in 0 until ns.size - 1) m.edge(ns[i], ns[i + 1], 1, "Main", 6, EdgeFlags.FORWARD)
        val side1 = m.node(40.001, -83.0 + 200.0 / mPerDegLon)
        val side2 = m.node(39.999, -83.0 + 206.0 / mPerDegLon)
        m.edge(ns[1], side1, 2, "North", 6, EdgeFlags.FORWARD)
        m.edge(ns[2], side2, 3, "South", 6, EdgeFlags.FORWARD)
        val g = m.open(tmp.root, "short.agr")
        val geom = listOf(LatLon(40.0, -83.0 + 7.0 / mPerDegLon), LatLon(40.0, -83.0 + 393.0 / mPerDegLon))
        val matched = RoadClosures(g).match(geom, "eastbound", directional = true).toSet()
        assertEquals((0 until 3).map { MiniGraph.find(g, ns[it], ns[it + 1], 1) }.toSet(), matched)
    }

    @Test
    fun aDestinationOnlyReachableThroughAClosureStillGetsARoute() {
        // Every way into the 5th Street / C Avenue junction is closed; the destination is on the open
        // eastbound side of 5th Street just past it, so it can only be reached by driving a closed edge.
        val into = listOf(edge(graph, node(4, 1), node(4, 2)), edge(graph, node(4, 3), node(4, 2)), edge(graph, node(3, 2), node(4, 2)))
        val closed = EdgeRestrictionSet.Builder().apply { into.forEach { close(it) } }.build()
        val router = Router(graph, SpeedSource.NONE, restrictions = closed)
        val target = TestCity.eastOf(4, 2, 100.0)
        val route = router.route(start.lat, start.lon, target.lat, target.lon, RouteProfile.FASTEST, now)
        assertNotNull("a destination behind closures must fall back to a route through them", route)
        assertEquals(1, route!!.edges.count { it in into })
        assertFalse(router.alternatives(start.lat, start.lon, target.lat, target.lon, now).isEmpty())

        // With one way in left open, the closures are respected as before.
        val partial = EdgeRestrictionSet.Builder().apply { into.drop(1).forEach { close(it) } }.build()
        val via = Router(graph, SpeedSource.NONE, restrictions = partial)
            .route(start.lat, start.lon, target.lat, target.lon, RouteProfile.FASTEST, now)!!
        assertTrue(via.edges.none { it in into.drop(1) })
    }

    @Test
    fun aCarOnARoadClosedInItsDirectionCarriesOnInsteadOfTurningBack() {
        // The car is 40 m along 1st Street eastbound, which is closed eastbound only (westbound open),
        // heading for a point further east on 1st Street.
        val east = edge(graph, node(0, 0), node(0, 1))
        val west = edge(graph, node(0, 1), node(0, 0))
        val closed = EdgeRestrictionSet.Builder().apply { close(east) }.build()
        val router = Router(graph, SpeedSource.NONE, restrictions = closed)
        val target = TestCity.eastOf(0, 2, 100.0)

        val routes = listOf(router.route(start.lat, start.lon, target.lat, target.lon, RouteProfile.FASTEST, now)!!) +
            router.alternatives(start.lat, start.lon, target.lat, target.lon, now)
        for (r in routes) {
            assertEquals("starts on the edge the car is on", east, r.edges[0])
            assertFalse("no U-turn onto the open opposite direction", r.edges.contains(west))
            assertEquals("leaves at the next junction", node(0, 1), graph.edgeFrom(r.edges[1]))
            assertTrue(r.edges.drop(1).none { closed.isClosed(it) })
        }
    }

    @Test
    fun aDetourGeometryClosesNothing() {
        val set = closures.update(listOf(zone("oh:7", WorkZone.DETOUR, WorkZone.IMPACT_UNKNOWN, firstStreetGeom())), emptyList(), now)
        assertEquals(0, set.size)
    }

    @Test
    fun closedWinsOverACapAndTheLowestCapWins() {
        val b = EdgeRestrictionSet.Builder()
        b.cap(5, 40.0); b.cap(5, 20.0); b.cap(5, 30.0)
        b.cap(9, 50.0); b.close(9)
        val set = b.build()
        assertEquals(20.0, set.speedCapKph(5)!!, 1e-6)
        assertTrue(set.isClosed(9))
        assertNull(set.speedCapKph(9))
        assertFalse(set.isClosed(5))
        assertNull(set.speedCapKph(7))
        assertNotNull(EdgeRestrictionSet.EMPTY)
        assertFalse(EdgeRestrictionSet.EMPTY.isClosed(0))
    }
}
