package app.clairix.nav

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Writes a small AGR1 graph exactly as the format spec lays it out: a 5×5 grid of residential
 * streets about 200 m apart, a traffic signal at the junction of 1st Street and C Avenue,
 * 3rd Street one-way eastbound, and a tolled one-way motorway (I-71) running east 200 m south
 * of the grid, entered by a ramp at the south-west corner and left by a ramp at the south-east one.
 */
object TestCity {
    const val ROWS = 5
    const val COLS = 5
    const val LAT0 = 40.0
    const val LON0 = -83.0
    const val DLAT = 0.0018
    const val DLON = 0.00235
    const val HW = ROWS * COLS          // motorway west end, below node(0, 0)
    const val HE = ROWS * COLS + 1      // motorway east end, below node(0, 4)
    const val ROW_WAY_BASE = 100L
    const val COL_WAY_BASE = 200L
    const val MOTORWAY_WAY = 300L
    const val ON_RAMP_WAY = 301L
    const val OFF_RAMP_WAY = 302L
    const val ONEWAY_ROW = 2
    const val SIGNAL_ROW = 0
    const val SIGNAL_COL = 2

    val rowNames = listOf("1st Street", "2nd Street", "3rd Street", "4th Street", "5th Street")
    val colNames = listOf("A Avenue", "B Avenue", "C Avenue", "D Avenue", "E Avenue")

    fun node(r: Int, c: Int) = r * COLS + c
    fun lat(r: Int) = LAT0 + r * DLAT
    fun lon(c: Int) = LON0 + c * DLON

    private class EdgeRec(
        val from: Int, val to: Int, val wayId: Long, val name: String?, val shape: List<LatLon>,
        val maxspeed: Int, val roadClass: Int, var flags: Int,
    )

    fun write(dir: File): File {
        val nodes = ArrayList<LatLon>()
        for (r in 0 until ROWS) for (c in 0 until COLS) nodes.add(LatLon(lat(r), lon(c)))
        nodes.add(LatLon(LAT0 - DLAT, lon(0)))
        nodes.add(LatLon(LAT0 - DLAT, lon(COLS - 1)))

        val edges = ArrayList<EdgeRec>()
        fun way(id: Long, name: String?, seq: List<Int>, oneway: Boolean, cls: Int, maxspeed: Int, flags: Int,
                shapes: Map<Int, List<LatLon>> = emptyMap()) {
            for (i in 0 until seq.size - 1) {
                val shape = shapes[i] ?: emptyList()
                edges.add(EdgeRec(seq[i], seq[i + 1], id, name, shape, maxspeed, cls, flags or EdgeFlags.FORWARD))
                if (!oneway) edges.add(EdgeRec(seq[i + 1], seq[i], id, name, shape.reversed(), maxspeed, cls, flags))
            }
        }
        for (r in 0 until ROWS) {
            way(ROW_WAY_BASE + r, rowNames[r], (0 until COLS).map { node(r, it) }, r == ONEWAY_ROW, 6, 0, 0)
        }
        for (c in 0 until COLS) {
            way(COL_WAY_BASE + c, colNames[c], (0 until ROWS).map { node(it, c) }, false, 6, 0, 0)
        }
        val bend = LatLon(LAT0 - DLAT - 0.0002, (lon(0) + lon(COLS - 1)) / 2)
        way(MOTORWAY_WAY, "I-71", listOf(HW, HE), true, 0, 113, EdgeFlags.TOLL, mapOf(0 to listOf(bend)))
        way(ON_RAMP_WAY, null, listOf(node(0, 0), HW), true, 0, 80, EdgeFlags.LINK)
        way(OFF_RAMP_WAY, null, listOf(HE, node(0, COLS - 1)), true, 0, 80, EdgeFlags.LINK)
        val signalNode = node(SIGNAL_ROW, SIGNAL_COL)
        for (e in edges) if (e.to == signalNode) e.flags = e.flags or EdgeFlags.SIGNALS

        edges.sortWith(compareBy<EdgeRec> { it.from }.thenBy { it.to })
        val names = edges.mapNotNull { it.name }.distinct()
        val shapePoints = ArrayList<LatLon>()
        val shapeStart = IntArray(edges.size)
        for ((i, e) in edges.withIndex()) {
            shapeStart[i] = shapePoints.size
            shapePoints.addAll(e.shape)
        }
        val all = nodes + shapePoints
        val nameBytes = names.map { it.toByteArray(Charsets.UTF_8) }
        val size = 128 + nodes.size * 8 + (nodes.size + 1) * 4 + edges.size * 32 + shapePoints.size * 8 +
            nameBytes.sumOf { 2 + it.size }
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("AGR1".toByteArray(Charsets.US_ASCII))
        b.putInt(2); b.putInt(nodes.size); b.putInt(edges.size); b.putInt(shapePoints.size); b.putInt(names.size)
        b.putInt(e7(all.minOf { it.lat })); b.putInt(e7(all.minOf { it.lon }))
        b.putInt(e7(all.maxOf { it.lat })); b.putInt(e7(all.maxOf { it.lon }))
        b.putLong(1_760_000_000L)
        val slug = "test-city".toByteArray(Charsets.US_ASCII)
        b.put(slug); repeat(80 - slug.size) { b.put(0) }
        for (n in nodes) { b.putInt(e7(n.lat)); b.putInt(e7(n.lon)) }
        var offset = 0
        for (n in nodes.indices) {
            b.putInt(offset)
            offset += edges.count { it.from == n }
        }
        b.putInt(offset)
        for ((i, e) in edges.withIndex()) {
            val pts = listOf(nodes[e.from]) + e.shape + listOf(nodes[e.to])
            var len = 0.0
            for (k in 1 until pts.size) len += RoadGeo.haversineM(pts[k - 1].lat, pts[k - 1].lon, pts[k].lat, pts[k].lon)
            b.putInt(e.to)
            b.putInt((len * 10).roundToLong().toInt())
            b.putLong(e.wayId)
            b.putInt(e.name?.let { names.indexOf(it) } ?: -1)
            b.putInt(shapeStart[i])
            b.putShort(e.shape.size.toShort())
            b.put(e.maxspeed.toByte())
            b.put(e.roadClass.toByte())
            b.put(e.flags.toByte())
            b.put(0)
            b.putShort(0)
        }
        for (p in shapePoints) { b.putInt(e7(p.lat)); b.putInt(e7(p.lon)) }
        for (n in nameBytes) { b.putShort(n.size.toShort()); b.put(n) }
        check(b.position() == size)
        val file = File(dir, "test-city.agr")
        file.writeBytes(b.array())
        return file
    }

    private fun e7(v: Double) = (v * 1e7).roundToInt()

    /** The edge from node [a] to node [b]. */
    fun edge(g: RoadGraph, a: Int, b: Int): Int =
        (g.firstEdge(a) until g.lastEdgeExclusive(a)).first { g.edgeTo(it) == b }

    /** A point [metres] east (+) or west (-) of node (r, c). */
    fun eastOf(r: Int, c: Int, metres: Double): LatLon =
        LatLon(lat(r), lon(c) + metres / (RoadGeo.M_PER_DEG_LAT * Math.cos(Math.toRadians(lat(r)))))
}
