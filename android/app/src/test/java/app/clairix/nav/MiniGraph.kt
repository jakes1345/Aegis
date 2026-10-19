package app.clairix.nav

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Writes an arbitrary small AGR1 graph laid out exactly as the format spec says. */
class MiniGraph {
    class Edge(
        val from: Int, val to: Int, val wayId: Long, val name: String?, val shape: List<LatLon>,
        val maxspeed: Int, val roadClass: Int, val flags: Int,
    )

    val nodes = ArrayList<LatLon>()
    private val edges = ArrayList<Edge>()

    fun node(lat: Double, lon: Double): Int { nodes.add(LatLon(lat, lon)); return nodes.size - 1 }

    fun edge(
        from: Int, to: Int, wayId: Long, name: String?, roadClass: Int, flags: Int,
        shape: List<LatLon> = emptyList(), maxspeed: Int = 0,
    ) { edges.add(Edge(from, to, wayId, name, shape, maxspeed, roadClass, flags)) }

    /** Writes the graph to [dir]/[fileName] and opens it. */
    fun open(dir: File, fileName: String = "mini.agr"): RoadGraph {
        val sorted = edges.sortedWith(compareBy<Edge> { it.from }.thenBy { it.to })
        val names = sorted.mapNotNull { it.name }.distinct()
        val shapePoints = ArrayList<LatLon>()
        val shapeStart = IntArray(sorted.size)
        for ((i, e) in sorted.withIndex()) { shapeStart[i] = shapePoints.size; shapePoints.addAll(e.shape) }
        val all = nodes + shapePoints
        val nameBytes = names.map { it.toByteArray(Charsets.UTF_8) }
        val size = 128 + nodes.size * 8 + (nodes.size + 1) * 4 + sorted.size * 32 + shapePoints.size * 8 +
            nameBytes.sumOf { 2 + it.size }
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("AGR1".toByteArray(Charsets.US_ASCII))
        b.putInt(2); b.putInt(nodes.size); b.putInt(sorted.size); b.putInt(shapePoints.size); b.putInt(names.size)
        b.putInt(e7(all.minOf { it.lat })); b.putInt(e7(all.minOf { it.lon }))
        b.putInt(e7(all.maxOf { it.lat })); b.putInt(e7(all.maxOf { it.lon }))
        b.putLong(1_760_000_000L)
        repeat(80) { b.put(0) }
        for (n in nodes) { b.putInt(e7(n.lat)); b.putInt(e7(n.lon)) }
        var offset = 0
        for (n in nodes.indices) { b.putInt(offset); offset += sorted.count { it.from == n } }
        b.putInt(offset)
        for ((i, e) in sorted.withIndex()) {
            val pts = listOf(nodes[e.from]) + e.shape + listOf(nodes[e.to])
            var len = 0.0
            for (k in 1 until pts.size) len += RoadGeo.haversineM(pts[k - 1].lat, pts[k - 1].lon, pts[k].lat, pts[k].lon)
            b.putInt(e.to)
            b.putInt((len * 10).roundToLong().toInt())
            b.putLong(e.wayId)
            b.putInt(e.name?.let { names.indexOf(it) } ?: -1)
            b.putInt(shapeStart[i])
            b.putShort(e.shape.size.toShort())
            b.put(e.maxspeed.toByte()); b.put(e.roadClass.toByte()); b.put(e.flags.toByte())
            b.put(0); b.putShort(0)
        }
        for (p in shapePoints) { b.putInt(e7(p.lat)); b.putInt(e7(p.lon)) }
        for (n in nameBytes) { b.putShort(n.size.toShort()); b.put(n) }
        check(b.position() == size)
        val file = File(dir, fileName)
        file.writeBytes(b.array())
        return RoadGraph.open(file)
    }

    private fun e7(v: Double) = (v * 1e7).roundToInt()

    companion object {
        /** The edge from [a] to [b] on way [wayId] travelling with ([forward]) or against the way. */
        fun find(g: RoadGraph, a: Int, b: Int, wayId: Long, forward: Boolean = true): Int =
            (g.firstEdge(a) until g.lastEdgeExclusive(a)).first {
                g.edgeTo(it) == b && g.edgeWayId(it) == wayId && (g.edgeFlags(it) and EdgeFlags.FORWARD != 0) == forward
            }
    }
}
