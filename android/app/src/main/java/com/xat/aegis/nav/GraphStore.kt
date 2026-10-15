package com.xat.aegis.nav

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A downloadable map region: its Geofabrik path ("north-america/us/ohio") and a display name. */
data class MapRegion(val path: String, val name: String) {
    val slug: String get() = GraphStore.slugOf(path)
}

/** A road graph present on this phone. */
data class InstalledRegion(val slug: String, val file: File, val sizeBytes: Long, val builtAtEpochSec: Long) {
    val name: String get() = GraphStore.US_STATES.firstOrNull { it.slug == slug }?.name ?: slug
}

/** Where a region download stands. */
sealed interface GraphDownload {
    data object Idle : GraphDownload
    data class Downloading(val slug: String, val bytes: Long, val totalBytes: Long?) : GraphDownload {
        val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    }
    data class Installing(val slug: String) : GraphDownload
    data class Done(val slug: String) : GraphDownload
    data class Failed(val slug: String, val message: String) : GraphDownload
}

/**
 * Road graphs on disk at filesDir/nav/<slug>.agr, downloaded from the maps release the
 * GitHub workflow publishes (spec section 6). A download is only installed once it opens
 * as a valid graph, and it replaces any older copy in one rename.
 */
class GraphStore(context: Context) {

    companion object {
        private const val RELEASE_BASE = "https://github.com/jakes1345/Aegis/releases/download"
        private const val HEADER_BYTES = 64
        private const val PROGRESS_STEP_BYTES = 256 * 1024L

        fun slugOf(path: String): String = path.trim('/').replace('/', '-')

        fun downloadUrl(slug: String): String = "$RELEASE_BASE/maps-$slug/graph-$slug.agr.gz"

        private fun us(id: String, name: String) = MapRegion("north-america/us/$id", name)

        val US_STATES: List<MapRegion> = listOf(
            us("alabama", "Alabama"), us("alaska", "Alaska"), us("arizona", "Arizona"),
            us("arkansas", "Arkansas"), us("california", "California"), us("colorado", "Colorado"),
            us("connecticut", "Connecticut"), us("delaware", "Delaware"),
            us("district-of-columbia", "District of Columbia"), us("florida", "Florida"),
            us("georgia", "Georgia"), us("hawaii", "Hawaii"), us("idaho", "Idaho"),
            us("illinois", "Illinois"), us("indiana", "Indiana"), us("iowa", "Iowa"),
            us("kansas", "Kansas"), us("kentucky", "Kentucky"), us("louisiana", "Louisiana"),
            us("maine", "Maine"), us("maryland", "Maryland"), us("massachusetts", "Massachusetts"),
            us("michigan", "Michigan"), us("minnesota", "Minnesota"), us("mississippi", "Mississippi"),
            us("missouri", "Missouri"), us("montana", "Montana"), us("nebraska", "Nebraska"),
            us("nevada", "Nevada"), us("new-hampshire", "New Hampshire"), us("new-jersey", "New Jersey"),
            us("new-mexico", "New Mexico"), us("new-york", "New York"),
            us("north-carolina", "North Carolina"), us("north-dakota", "North Dakota"),
            us("ohio", "Ohio"), us("oklahoma", "Oklahoma"), us("oregon", "Oregon"),
            us("pennsylvania", "Pennsylvania"), us("puerto-rico", "Puerto Rico"),
            us("rhode-island", "Rhode Island"), us("south-carolina", "South Carolina"),
            us("south-dakota", "South Dakota"), us("tennessee", "Tennessee"), us("texas", "Texas"),
            us("utah", "Utah"), us("vermont", "Vermont"), us("virginia", "Virginia"),
            us("washington", "Washington"), us("west-virginia", "West Virginia"),
            us("wisconsin", "Wisconsin"), us("wyoming", "Wyoming"),
        )
    }

    private val dir = File(context.applicationContext.filesDir, "nav")

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true) // release assets redirect to GitHub's object storage
        .build()

    private val _progress = MutableStateFlow<GraphDownload>(GraphDownload.Idle)
    val progress: StateFlow<GraphDownload> = _progress.asStateFlow()

    fun fileFor(slug: String): File = File(dir, "$slug.agr")

    fun isInstalled(slug: String): Boolean = fileFor(slug).isFile

    /** Opens an installed region's graph. */
    fun open(slug: String): RoadGraph = RoadGraph.open(fileFor(slug))

    /** Installed regions, newest build first; files that are not graphs are ignored. */
    fun installed(): List<InstalledRegion> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".agr") } ?: return emptyList()
        return files.mapNotNull { f ->
            val built = readBuiltAt(f) ?: return@mapNotNull null
            InstalledRegion(f.name.removeSuffix(".agr"), f, f.length(), built)
        }.sortedByDescending { it.builtAtEpochSec }
    }

    private fun readBuiltAt(f: File): Long? = try {
        RandomAccessFile(f, "r").use { raf ->
            if (raf.length() < HEADER_BYTES) return null
            val h = ByteArray(HEADER_BYTES)
            raf.readFully(h)
            if (h[0] != 'A'.code.toByte() || h[1] != 'G'.code.toByte() || h[2] != 'R'.code.toByte() || h[3] != '1'.code.toByte()) return null
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (h[40 + i].toLong() and 0xFF)
            v
        }
    } catch (e: IOException) {
        null
    }

    /** Removes a region's graph and anything left over from an unfinished download of it. */
    fun delete(slug: String): Boolean {
        File(dir, "$slug.agr.gz.part").delete()
        File(dir, "$slug.agr.tmp").delete()
        val f = fileFor(slug)
        return !f.exists() || f.delete()
    }

    /**
     * Downloads and installs [region], reporting through [progress]. Returns the installed
     * file; any failure leaves the previous copy (if there was one) untouched.
     */
    suspend fun download(region: MapRegion): Result<File> = download(region.slug)

    suspend fun download(slug: String): Result<File> = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val part = File(dir, "$slug.agr.gz.part")
        val tmp = File(dir, "$slug.agr.tmp")
        val target = fileFor(slug)
        try {
            _progress.value = GraphDownload.Downloading(slug, 0, null)
            fetch(downloadUrl(slug), part, slug)
            currentCoroutineContext().ensureActive()
            _progress.value = GraphDownload.Installing(slug)
            gunzip(part, tmp)
            part.delete()
            val graph = try {
                RoadGraph.open(tmp)
            } catch (e: Exception) {
                throw IOException("The downloaded map is not a valid road graph: ${e.message}")
            }
            if (graph.region.isEmpty() || !slug.startsWith(graph.region)) {
                throw IOException("The downloaded map is for ${graph.region}, not $slug")
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            _progress.value = GraphDownload.Done(slug)
            Result.success(target)
        } catch (e: kotlinx.coroutines.CancellationException) {
            part.delete(); tmp.delete()
            _progress.value = GraphDownload.Idle
            throw e
        } catch (e: Exception) {
            part.delete(); tmp.delete()
            _progress.value = GraphDownload.Failed(slug, e.message ?: e.javaClass.simpleName)
            Result.failure(e)
        }
    }

    private suspend fun fetch(url: String, out: File, slug: String) {
        val call = http.newCall(Request.Builder().url(url).header("Accept", "application/octet-stream").build())
        val response = suspendCancellableCoroutine<Response> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) { cont.resume(response) }
            })
        }
        response.use { res ->
            if (res.code == 404) throw IOException("No map has been published for $slug yet")
            if (!res.isSuccessful) throw IOException("Map download failed: HTTP ${res.code}")
            val body = res.body ?: throw IOException("Map download returned no data")
            val total = body.contentLength().takeIf { it > 0 }
            val ctx = currentCoroutineContext()
            body.byteStream().use { input ->
                out.outputStream().buffered().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var reported = 0L
                    while (true) {
                        ctx.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (done - reported >= PROGRESS_STEP_BYTES) {
                            reported = done
                            _progress.value = GraphDownload.Downloading(slug, done, total)
                        }
                    }
                    if (total != null && done != total) throw IOException("Map download was cut short")
                    _progress.value = GraphDownload.Downloading(slug, done, total)
                }
            }
        }
    }

    private suspend fun gunzip(src: File, dst: File) {
        val ctx = currentCoroutineContext()
        GZIPInputStream(src.inputStream().buffered(), 64 * 1024).use { input ->
            dst.outputStream().buffered().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    ctx.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                }
            }
        }
    }
}
