package app.clairix.nav

import android.content.Context
import app.clairix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A geocoding result. [name] is the short label; [displayName] the full address line. */
data class Place(val name: String, val displayName: String, val lat: Double, val lon: Double)

/**
 * Destination search and reverse lookup through OpenStreetMap's Nominatim. Its usage policy asks
 * for an identifying User-Agent and at most one request per second, which this enforces across
 * every instance in the app.
 */
class Geocoder(context: Context) {

    private companion object {
        const val BASE = "https://nominatim.openstreetmap.org"
        const val MIN_INTERVAL_MS = 1_100L
        const val LIMIT = 8
        val gate = Mutex()
        @Volatile var lastRequestMs = 0L
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }

    private val userAgent: String = run {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            null
        } ?: BuildConfig.VERSION_NAME
        "Clairix/$version (github.com/jakes1345/Clairix)"
    }

    /**
     * Places matching [query], preferring (not limited to) those inside [graph]'s area so a
     * search for "Main St" finds the one in the downloaded region first.
     */
    suspend fun search(query: String, graph: RoadGraph? = null): List<Place> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url = BASE.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", LIMIT.toString())
            .addQueryParameter("q", q)
            .apply {
                if (graph != null) {
                    // Nominatim's viewbox is left,top,right,bottom; bounded=0 only biases toward it.
                    addQueryParameter("viewbox", "${graph.minLon},${graph.maxLat},${graph.maxLon},${graph.minLat}")
                    addQueryParameter("bounded", "0")
                }
            }
            .build()
        val text = get(url)
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parsePlace(it) } }
        } catch (e: JSONException) {
            throw IOException("Unexpected search response: ${e.message}")
        }
    }

    /** The address at a point (for a long-press on the map), or null when there is nothing there. */
    suspend fun reverse(lat: Double, lon: Double): Place? {
        val url = BASE.toHttpUrl().newBuilder()
            .addPathSegment("reverse")
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("lat", lat.toString())
            .addQueryParameter("lon", lon.toString())
            .addQueryParameter("zoom", "18")
            .build()
        val text = get(url)
        return try {
            val o = JSONObject(text)
            if (o.has("error")) null else parsePlace(o)
        } catch (e: JSONException) {
            throw IOException("Unexpected reverse lookup response: ${e.message}")
        }
    }

    private fun parsePlace(o: JSONObject): Place? {
        val lat = o.optString("lat").toDoubleOrNull() ?: return null
        val lon = o.optString("lon").toDoubleOrNull() ?: return null
        val display = o.optString("display_name")
        val name = o.optString("name").takeIf { it.isNotBlank() }
            ?: o.optJSONObject("address")?.let { a ->
                val road = a.optString("road")
                val number = a.optString("house_number")
                when {
                    road.isNotBlank() && number.isNotBlank() -> "$number $road"
                    road.isNotBlank() -> road
                    else -> null
                }
            }
            ?: display.substringBefore(',').trim()
        if (name.isEmpty() && display.isEmpty()) return null
        return Place(name.ifEmpty { display }, display.ifEmpty { name }, lat, lon)
    }

    private suspend fun get(url: HttpUrl): String = gate.withLock {
        val wait = lastRequestMs + MIN_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            withContext(Dispatchers.IO) { execute(url) }
        } finally {
            lastRequestMs = System.currentTimeMillis()
        }
    }

    private suspend fun execute(url: HttpUrl): String {
        val call = http.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Accept-Language", Locale.getDefault().toLanguageTag())
                .build()
        )
        val response = suspendCancellableCoroutine<Response> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
                // A response that arrives after cancellation must still be closed, or its connection leaks.
                override fun onResponse(call: Call, response: Response) { cont.resume(response) { response.close() } }
            })
        }
        return response.use { res ->
            if (!res.isSuccessful) throw IOException("Place search failed: HTTP ${res.code}")
            res.body?.string() ?: throw IOException("Place search returned nothing")
        }
    }
}
