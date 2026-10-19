package app.clairix.nav

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Read-only view of an AGR1 road graph file. The file is memory-mapped, so opening a
 * state-sized graph costs a validation pass and the name table offsets, not a copy.
 * Every accessor uses absolute ByteBuffer reads and is safe to call from several threads.
 */
class RoadGraph private constructor(
    private val buf: ByteBuffer,
    val region: String,
    val nodeCount: Int,
    val edgeCount: Int,
    private val shapeTotal: Int,
    private val nameCount: Int,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val builtAtEpochSec: Long,
    private val nameOffsets: IntArray,
) {
    private val nodesOff = HEADER_BYTES
    private val firstEdgeOff = nodesOff + nodeCount * 8
    private val edgesOff = firstEdgeOff + (nodeCount + 1) * 4
    private val shapeOff = edgesOff + edgeCount * EDGE_BYTES

    companion object {
        private const val HEADER_BYTES = 128
        private const val EDGE_BYTES = 32
        private const val NO_NAME = -1 // 0xFFFFFFFF read as a signed Int
        private const val TARGET_CELL_M = 250.0
        private const val MAX_CELLS = 4_000_000

        fun open(file: File): RoadGraph {
            val mapped = RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                if (len > Int.MAX_VALUE) throw IOException("Road graph ${file.name} is larger than 2 GB")
                if (len < HEADER_BYTES) throw IOException("Road graph ${file.name} is truncated")
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, len)
            }
            val buf: ByteBuffer = mapped.order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { for (i in 0 until 4) it[i] = buf.get(i) }
            if (String(magic, Charsets.US_ASCII) != "AGR1") throw IOException("${file.name} is not an AGR1 road graph")
            val version = buf.getInt(4)
            if (version != 2) throw IOException("Unsupported road graph version $version")
            val nodeCount = buf.getInt(8)
            val edgeCount = buf.getInt(12)
            val shapeCount = buf.getInt(16)
            val nameCount = buf.getInt(20)
            if (nodeCount < 0 || edgeCount < 0 || shapeCount < 0 || nameCount < 0) {
                throw IOException("Road graph counts out of range")
            }
            val size = buf.capacity().toLong()
            val fixedEnd = HEADER_BYTES.toLong() + nodeCount * 8L + (nodeCount + 1L) * 4L +
                edgeCount.toLong() * EDGE_BYTES + shapeCount * 8L
            if (fixedEnd > size) throw IOException("Road graph is truncated (${size} < $fixedEnd bytes)")

            val nameOffsets = IntArray(nameCount)
            var p = fixedEnd.toInt()
            for (i in 0 until nameCount) {
                if (p + 2 > size) throw IOException("Road graph name table is truncated")
                nameOffsets[i] = p
                p += 2 + (buf.getShort(p).toInt() and 0xFFFF)
                if (p > size) throw IOException("Road graph name table is truncated")
            }

            val regionBytes = ByteArray(64).also { for (i in 0 until 64) it[i] = buf.get(48 + i) }
            val regionLen = regionBytes.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) 64 else it }
            val g = RoadGraph(
                buf = buf,
                region = String(regionBytes, 0, regionLen, Charsets.US_ASCII),
                nodeCount = nodeCount,
                edgeCount = edgeCount,
                shapeTotal = shapeCount,
                nameCount = nameCount,
                minLat = buf.getInt(24) / 1e7,
                minLon = buf.getInt(28) / 1e7,
                maxLat = buf.getInt(32) / 1e7,
                maxLon = buf.getInt(36) / 1e7,
                builtAtEpochSec = buf.getLong(40),
                nameOffsets = nameOffsets,
            )
            g.validateStructure()
            return g
        }
    }

    // A corrupt or half-downloaded graph must fail here, not as an index error mid-route.
    private fun validateStructure() {
        if (firstEdge(0) != 0) throw IOException("Road graph CSR does not start at 0")
        var prev = 0
        for (n in 1..nodeCount) {
            val v = buf.getInt(firstEdgeOff + n * 4)
            if (v < prev) throw IOException("Road graph CSR offsets decrease at node $n")
            prev = v
        }
        if (prev != edgeCount) throw IOException("Road graph CSR ends at $prev, expected $edgeCount")
        for (e in 0 until edgeCount) {
            val base = edgesOff + e * EDGE_BYTES
            val to = buf.getInt(base)
            if (to < 0 || to >= nodeCount) throw IOException("Edge $e points at missing node $to")
            val name = buf.getInt(base + 16)
            if (name != NO_NAME && (name < 0 || name >= nameCount)) throw IOException("Edge $e has bad name $name")
            val start = buf.getInt(base + 20).toLong() and 0xFFFFFFFFL
            val count = buf.getShort(base + 24).toInt() and 0xFFFF
            if (start + count > shapeTotal) throw IOException("Edge $e shape runs past the shape table")
        }
    }

    fun nodeLat(n: Int): Double = buf.getInt(nodesOff + n * 8) / 1e7
    fun nodeLon(n: Int): Double = buf.getInt(nodesOff + n * 8 + 4) / 1e7

    fun firstEdge(n: Int): Int = buf.getInt(firstEdgeOff + n * 4)
    fun lastEdgeExclusive(n: Int): Int = buf.getInt(firstEdgeOff + (n + 1) * 4)

    fun edgeFrom(e: Int): Int {
        require(e in 0 until edgeCount) { "edge $e out of range" }
        // Largest n with firstEdge(n) <= e; nodes without edges share an offset with their successor.
        var lo = 0
        var hi = nodeCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (firstEdge(mid) <= e) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun edgeTo(e: Int): Int = buf.getInt(edgesOff + e * EDGE_BYTES)
    fun edgeLengthM(e: Int): Double = (buf.getInt(edgesOff + e * EDGE_BYTES + 4).toLong() and 0xFFFFFFFFL) / 10.0
    fun edgeWayId(e: Int): Long = buf.getLong(edgesOff + e * EDGE_BYTES + 8)

    fun edgeName(e: Int): String? {
        val idx = buf.getInt(edgesOff + e * EDGE_BYTES + 16)
        if (idx == NO_NAME) return null
        val off = nameOffsets[idx]
        val len = buf.getShort(off).toInt() and 0xFFFF
        val bytes = ByteArray(len)
        for (i in 0 until len) bytes[i] = buf.get(off + 2 + i)
        return String(bytes, Charsets.UTF_8)
    }

    private fun edgeShapeStart(e: Int): Int = buf.getInt(edgesOff + e * EDGE_BYTES + 20)
    internal fun edgeShapeCount(e: Int): Int = buf.getShort(edgesOff + e * EDGE_BYTES + 24).toInt() and 0xFFFF
    fun edgeMaxspeedKph(e: Int): Int = buf.get(edgesOff + e * EDGE_BYTES + 26).toInt() and 0xFF
    fun edgeRoadClass(e: Int): Int = buf.get(edgesOff + e * EDGE_BYTES + 27).toInt() and 0xFF
    fun edgeFlags(e: Int): Int = buf.get(edgesOff + e * EDGE_BYTES + 28).toInt() and 0xFF

    fun segmentKey(e: Int): String =
        edgeWayId(e).toString() + ":" + (if (edgeFlags(e) and EdgeFlags.FORWARD != 0) "1" else "0")

    fun edgeGeometry(e: Int): List<LatLon> {
        val s = PolyScratch()
        loadGeometry(e, s)
        return List(s.n) { LatLon(s.lat[it], s.lon[it]) }
    }

    /** Fills [s] with the edge's polyline (from node, shape points, to node). [from] saves the CSR search when known. */
    internal fun loadGeometry(e: Int, s: PolyScratch, from: Int = edgeFrom(e)) {
        val shapes = edgeShapeCount(e)
        s.ensure(shapes + 2)
        s.lat[0] = nodeLat(from); s.lon[0] = nodeLon(from)
        val start = edgeShapeStart(e)
        for (i in 0 until shapes) {
            val off = shapeOff + (start + i) * 8
            s.lat[i + 1] = buf.getInt(off) / 1e7
            s.lon[i + 1] = buf.getInt(off + 4) / 1e7
        }
        val to = edgeTo(e)
        s.lat[shapes + 1] = nodeLat(to); s.lon[shapes + 1] = nodeLon(to)
        s.n = shapes + 2
    }

    /** Direction of travel as the edge leaves its from node. */
    internal fun startBearing(e: Int, from: Int): Double {
        val lat0 = nodeLat(from); val lon0 = nodeLon(from)
        return if (edgeShapeCount(e) > 0) {
            val off = shapeOff + edgeShapeStart(e) * 8
            RoadGeo.bearingDeg(lat0, lon0, buf.getInt(off) / 1e7, buf.getInt(off + 4) / 1e7)
        } else {
            val to = edgeTo(e)
            RoadGeo.bearingDeg(lat0, lon0, nodeLat(to), nodeLon(to))
        }
    }

    /** Direction of travel as the edge arrives at its to node. */
    internal fun endBearing(e: Int, from: Int): Double {
        val to = edgeTo(e)
        val shapes = edgeShapeCount(e)
        val pLat: Double
        val pLon: Double
        if (shapes > 0) {
            val off = shapeOff + (edgeShapeStart(e) + shapes - 1) * 8
            pLat = buf.getInt(off) / 1e7; pLon = buf.getInt(off + 4) / 1e7
        } else {
            pLat = nodeLat(from); pLon = nodeLon(from)
        }
        return RoadGeo.bearingDeg(pLat, pLon, nodeLat(to), nodeLon(to))
    }

    /**
     * True when [b] runs back along [a]: the U-turn twin on a two-way way. The twin always has the
     * opposite direction bit; without that check, carrying on around a closed way that has only two
     * graph nodes (a small roundabout, a loop at the end of a cul-de-sac) looks like a U-turn.
     */
    internal fun isReverseOf(a: Int, aFrom: Int, b: Int): Boolean =
        edgeTo(b) == aFrom && edgeWayId(b) == edgeWayId(a) &&
            (edgeFlags(a) xor edgeFlags(b)) and EdgeFlags.FORWARD != 0

    // ── Spatial index ────────────────────────────────────────────────────────

    private class Grid(
        val lat0: Double, val lon0: Double, val cellLat: Double, val cellLon: Double,
        val rows: Int, val cols: Int, val cellStart: IntArray, val cellEdges: IntArray,
    ) {
        fun row(lat: Double) = floor((lat - lat0) / cellLat).toInt().coerceIn(0, rows - 1)
        fun col(lon: Double) = floor((lon - lon0) / cellLon).toInt().coerceIn(0, cols - 1)
    }

    @Volatile private var grid: Grid? = null

    private fun grid(): Grid = grid ?: synchronized(this) { grid ?: buildGrid().also { grid = it } }

    /** The empty grid sized to this graph's bounding box; [buildGrid] fills it. */
    private fun gridShape(): Grid {
        val midLat = (minLat + maxLat) / 2
        val mPerDegLat = RoadGeo.M_PER_DEG_LAT
        val mPerDegLon = RoadGeo.M_PER_DEG_LAT * max(0.01, cos(midLat * PI / 180))
        val spanLatM = max(1.0, (maxLat - minLat) * mPerDegLat)
        val spanLonM = max(1.0, (maxLon - minLon) * mPerDegLon)
        var cellM = TARGET_CELL_M
        while ((ceil(spanLatM / cellM) + 1) * (ceil(spanLonM / cellM) + 1) > MAX_CELLS) cellM *= 1.25
        val rows = (ceil(spanLatM / cellM).toInt() + 1).coerceAtLeast(1)
        val cols = (ceil(spanLonM / cellM).toInt() + 1).coerceAtLeast(1)
        return Grid(minLat, minLon, cellM / mPerDegLat, cellM / mPerDegLon, rows, cols, IntArray(0), IntArray(0))
    }

    /**
     * Peak heap the spatial index takes while [buildGrid] runs: three cell-sized IntArrays (counts,
     * lastEdge, cursor) plus one Int per (edge, cell) pair. The pair count is only known after a pass
     * over the geometry; it is estimated as one per polyline segment, which is what short urban
     * segments produce and what consecutive segments of a long edge share. 0 once the index exists.
     */
    fun estimatedIndexBytes(): Long {
        if (grid != null) return 0L
        val shape = gridShape()
        val cells = shape.rows.toLong() * shape.cols
        val segments = edgeCount.toLong() + shapeTotal
        return 3 * 4L * (cells + 1) + 4L * segments
    }

    private fun buildGrid(): Grid {
        val probe = gridShape()
        val rows = probe.rows
        val cols = probe.cols
        val cellLat = probe.cellLat
        val cellLon = probe.cellLon
        val cells = rows * cols

        val counts = IntArray(cells + 1)
        val lastEdge = IntArray(cells) { -1 }
        val s = PolyScratch()
        // Two passes over identical geometry: count, then fill a CSR of edge ids per cell.
        for (pass in 0..1) {
            val cursor = if (pass == 1) counts.copyOf() else counts
            if (pass == 1) lastEdge.fill(-1)
            val fill = if (pass == 1) IntArray(counts[cells]) else IntArray(0)
            for (n in 0 until nodeCount) {
                for (e in firstEdge(n) until lastEdgeExclusive(n)) {
                    loadGeometry(e, s, n)
                    for (i in 0 until s.n - 1) {
                        val r0 = probe.row(min(s.lat[i], s.lat[i + 1])); val r1 = probe.row(max(s.lat[i], s.lat[i + 1]))
                        val c0 = probe.col(min(s.lon[i], s.lon[i + 1])); val c1 = probe.col(max(s.lon[i], s.lon[i + 1]))
                        for (r in r0..r1) for (c in c0..c1) {
                            val cell = r * cols + c
                            if (lastEdge[cell] == e) continue
                            lastEdge[cell] = e
                            if (pass == 0) counts[cell + 1]++ else fill[cursor[cell]++] = e
                        }
                    }
                }
            }
            if (pass == 0) {
                for (i in 0 until cells) counts[i + 1] += counts[i]
            } else {
                return Grid(minLat, minLon, cellLat, cellLon, rows, cols, counts, fill)
            }
        }
        error("unreachable")
    }

    fun nearestEdges(lat: Double, lon: Double, radiusM: Double, limit: Int): List<EdgeHit> {
        if (limit <= 0 || edgeCount == 0) return emptyList()
        val g = grid()
        val mPerDegLat = RoadGeo.M_PER_DEG_LAT
        val mPerDegLon = RoadGeo.M_PER_DEG_LAT * max(0.01, cos(lat * PI / 180))
        val dLat = radiusM / mPerDegLat
        val dLon = radiusM / mPerDegLon
        if (lat + dLat < g.lat0 || lat - dLat > g.lat0 + g.rows * g.cellLat ||
            lon + dLon < g.lon0 || lon - dLon > g.lon0 + g.cols * g.cellLon
        ) return emptyList()
        val r0 = g.row(lat - dLat); val r1 = g.row(lat + dLat)
        val c0 = g.col(lon - dLon); val c1 = g.col(lon + dLon)

        val seen = HashSet<Int>()
        val hits = ArrayList<EdgeHit>()
        val s = PolyScratch()
        for (r in r0..r1) for (c in c0..c1) {
            val cell = r * g.cols + c
            for (k in g.cellStart[cell] until g.cellStart[cell + 1]) {
                val e = g.cellEdges[k]
                if (!seen.add(e)) continue
                loadGeometry(e, s)
                // Local equirectangular frame centred on the query point: metres east (x) and north (y).
                var best = Double.MAX_VALUE
                var bestAlong = 0.0
                var bestBearing = 0.0
                var along = 0.0
                for (i in 0 until s.n - 1) {
                    val ax = (s.lon[i] - lon) * mPerDegLon; val ay = (s.lat[i] - lat) * mPerDegLat
                    val bx = (s.lon[i + 1] - lon) * mPerDegLon; val by = (s.lat[i + 1] - lat) * mPerDegLat
                    val dx = bx - ax; val dy = by - ay
                    val segLen2 = dx * dx + dy * dy
                    val segLen = sqrt(segLen2)
                    val t = if (segLen2 > 0) ((-ax * dx - ay * dy) / segLen2).coerceIn(0.0, 1.0) else 0.0
                    val px = ax + t * dx; val py = ay + t * dy
                    val d = sqrt(px * px + py * py)
                    if (d < best && segLen2 > 0) {
                        best = d
                        bestAlong = along + t * segLen
                        bestBearing = (atan2(dx, dy) * 180 / PI + 360) % 360
                    } else if (d < best) {
                        best = d
                        bestAlong = along
                    }
                    along += segLen
                }
                if (best <= radiusM) {
                    val fraction = if (along > 0) (bestAlong / along).coerceIn(0.0, 1.0) else 0.0
                    hits.add(EdgeHit(e, best, fraction, bestBearing))
                }
            }
        }
        hits.sortWith(compareBy<EdgeHit> { it.distanceM }.thenBy { it.edge })
        return if (hits.size > limit) hits.subList(0, limit).toList() else hits
    }
}

/** Reusable polyline buffer so hot loops do not allocate a list per edge. */
internal class PolyScratch {
    var lat = DoubleArray(16)
    var lon = DoubleArray(16)
    var n = 0

    fun ensure(size: Int) {
        if (lat.size < size) {
            val cap = max(size, lat.size * 2)
            lat = DoubleArray(cap); lon = DoubleArray(cap)
        }
    }
}

internal object RoadGeo {
    const val EARTH_RADIUS_M = 6_371_008.8
    const val M_PER_DEG_LAT = EARTH_RADIUS_M * PI / 180

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * PI / 180; val p2 = lat2 * PI / 180
        val dp = p2 - p1; val dl = (lon2 - lon1) * PI / 180
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /** Initial great-circle bearing from point 1 to point 2, degrees clockwise from north in [0, 360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * PI / 180; val p2 = lat2 * PI / 180
        val dl = (lon2 - lon1) * PI / 180
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180 / PI + 360) % 360
    }

    /** Signed change of heading from [from] to [to] in (-180, 180]; positive turns right. */
    fun deltaDeg(from: Double, to: Double): Double {
        var d = (to - from) % 360
        if (d <= -180) d += 360
        if (d > 180) d -= 360
        return d
    }
}
