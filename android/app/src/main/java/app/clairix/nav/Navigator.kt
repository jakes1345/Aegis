package app.clairix.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioAttributes
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** An incident on the route ahead, [distanceM] along the route from the car. */
data class IncidentAhead(val incident: Incident, val distanceM: Double)

/** What turn-by-turn navigation is doing. */
sealed interface NavState {
    /** Not navigating; [error] says why the last attempt ended, if it failed. */
    data class Idle(val error: String? = null) : NavState

    /** Waiting for a fix and computing the first route. */
    data class Routing(val destLat: Double, val destLon: Double, val profile: RouteProfile) : NavState

    data class Navigating(
        val route: Route,
        val destLat: Double,
        val destLon: Double,
        val profile: RouteProfile,
        val nextManeuver: Maneuver?,
        val distanceToManeuverM: Double,
        /** Distance driven along [route] so far. */
        val progressM: Double,
        val remainingDistanceM: Double,
        /** Time to the destination at current live (else typical, else posted) speeds. */
        val remainingSec: Double,
        val etaEpochMs: Long,
        val currentStreet: String?,
        /** Posted limit of the road being driven, when the map has one. */
        val speedLimitKph: Int?,
        val offRoute: Boolean,
        val rerouting: Boolean,
        /** Incidents on the route within 5 km ahead, nearest first. */
        val incidentsAhead: List<IncidentAhead>,
        /** The car's position snapped to the route (or the raw fix while off it). */
        val position: LatLon?,
        val bearingDeg: Float?,
        val speedMps: Float?,
    ) : NavState
}

/**
 * One Drive navigation session at a time: routing, following the car along the route, live ETA,
 * rerouting, voice guidance, incident alerts and anonymous speed probes.
 */
class Navigator(
    context: Context,
    val graph: RoadGraph,
    private val router: Router,
    val traffic: TrafficClient,
    private val probe: ProbeReporter,
) {
    private companion object {
        const val TAG = "Navigator"
        const val FIX_INTERVAL_MS = 1_000L
        const val FIRST_FIX_TIMEOUT_MS = 30_000L
        const val OFF_ROUTE_M = 40.0
        const val OFF_ROUTE_FIXES = 3
        /** A fix this imprecise says nothing about whether the car left the route. */
        const val MAX_ACCURACY_FOR_OFF_ROUTE_M = 40f
        const val REROUTE_COOLDOWN_MS = 5_000L
        /** Reroutes that do not get the car back on a route back off up to this long (parking lots, unmapped roads). */
        const val REROUTE_MAX_COOLDOWN_MS = 60_000L
        /** Probes stop this far from the destination, so the relay never sees where a trip ends. */
        const val PROBE_END_ZONE_M = 500.0
        const val FASTER_CHECK_MS = 120_000L
        const val FASTER_SAVING_SEC = 180.0
        const val ARRIVED_M = 30.0
        const val LOOKAHEAD_M = 1_500.0
        const val LOOKBEHIND_POINTS = 3
        const val INCIDENTS_AHEAD_M = 5_000.0
        const val INCIDENT_ALERT_M = 2_000.0
        const val INCIDENT_OFF_ROUTE_M = 30.0
        const val MANEUVER_PASSED_M = 3.0
        val ANNOUNCE_AT_M = doubleArrayOf(100.0, 500.0, 2_000.0)
        const val MIN_KPH = 2.0
        const val MAX_KPH = 130.0
        const val SIGNAL_SEC = 15.0
        const val STOP_SEC = 5.0
        const val LEFT_SEC = 8.0
        const val RIGHT_SEC = 3.0
        const val UTURN_SEC = 30.0
        val CLASS_KPH = doubleArrayOf(105.0, 85.0, 65.0, 55.0, 45.0, 40.0, 30.0, 15.0)
        val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM", "GB")
    }

    /** A route prepared for following: cumulative distances and where each edge starts along it. */
    private inner class Track(val route: Route) {
        val geometry = route.geometry
        val cum = DoubleArray(geometry.size)
        val total: Double
        val edges = route.edges
        val segKeys = Array(edges.size) { graph.segmentKey(edges[it]) }
        /** Geometry index where each route edge starts. */
        val startIdx = IntArray(edges.size)
        /** Distance along the route where each edge starts; the last entry is the total length. */
        val startDist = DoubleArray(edges.size + 1)

        init {
            for (i in 1 until geometry.size) {
                cum[i] = cum[i - 1] + RoadGeo.haversineM(geometry[i - 1].lat, geometry[i - 1].lon, geometry[i].lat, geometry[i].lon)
            }
            total = if (geometry.isEmpty()) 0.0 else cum[geometry.size - 1]
            // Each edge after the first starts at its from-node, which the router placed in the
            // geometry exactly; find it scanning forward from the previous edge's start.
            for (i in 1 until edges.size) {
                val n = graph.edgeFrom(edges[i])
                val nLat = graph.nodeLat(n)
                val nLon = graph.nodeLon(n)
                var found = -1
                var j = startIdx[i - 1] + 1
                while (j < geometry.size) {
                    if (RoadGeo.haversineM(geometry[j].lat, geometry[j].lon, nLat, nLon) < 0.5) { found = j; break }
                    j++
                }
                startIdx[i] = if (found >= 0) found else startIdx[i - 1]
            }
            for (i in edges.indices) startDist[i] = cum[startIdx[i]]
            startDist[edges.size] = total
        }

        /** Index of the route edge containing [alongM]. */
        fun edgeIndexAt(alongM: Double): Int {
            if (edges.isEmpty()) return -1
            var lo = 0
            var hi = edges.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (startDist[mid] <= alongM) lo = mid else hi = mid - 1
            }
            return lo
        }
    }

    private class Projection(val alongM: Double, val offM: Double, val segment: Int, val lat: Double, val lon: Double)

    private val appContext = context.applicationContext
    private val fused: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val matcher = MapMatcher(graph)

    private val _state = MutableStateFlow<NavState>(NavState.Idle())
    val state: StateFlow<NavState> = _state.asStateFlow()

    /** Voice guidance on or off; alerts and instructions are still shown when muted. */
    @Volatile var muted = false

    private val lastFix = MutableStateFlow<Location?>(null)
    private val sessionLock = Any()
    private var scope: CoroutineScope? = null
    private var fixes: Channel<Location>? = null
    @Volatile private var generation = 0
    private var updatesOn = false

    // Session state below is only touched while holding [routeLock].
    private val routeLock = Mutex()
    private var track: Track? = null
    private var destLat = 0.0
    private var destLon = 0.0
    private var profile = RouteProfile.FASTEST
    private var progressM = 0.0
    private var progressSeg = 0
    private var offCount = 0
    private var lastRerouteMs = 0L
    /** Reroutes since the car was last on its route; each one doubles the cooldown before the next. */
    private var reroutesOffRoute = 0
    /** Held while any route search for this session (reroute or faster-route check) runs. */
    private val searching = AtomicBoolean(false)
    /** True only while recovering from leaving the route, for display. */
    @Volatile private var rerouting = false
    /** (maneuver index, threshold) pairs already spoken for the current track. */
    private val announced = HashSet<Long>()
    private val alertedIncidents = HashSet<String>()
    private var incidentsSeen: List<Incident>? = null
    private var incidentPos: Map<String, Double> = emptyMap()

    // ── Voice ───────────────────────────────────────────────────────────

    @Volatile private var ttsReady = false
    @Volatile private var tts: TextToSpeech? = null

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                val lang = engine.isLanguageAvailable(Locale.getDefault())
                engine.language = if (lang >= TextToSpeech.LANG_AVAILABLE) Locale.getDefault() else Locale.US
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ttsReady = true
            } else {
                Log.w(TAG, "Text-to-speech unavailable ($status)")
            }
        }
    }

    private var utterance = 0

    private fun speak(text: String, flush: Boolean = false) {
        if (muted || !ttsReady) return
        val engine = tts ?: return
        engine.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "nav-${utterance++}")
    }

    private val imperial: Boolean get() = Locale.getDefault().country in IMPERIAL_COUNTRIES

    private fun spokenDistance(m: Double): String {
        if (imperial) {
            val ft = m * 3.28084
            if (ft < 1000) {
                val r = maxOf(50, (ft / 50).roundToInt() * 50)
                return "$r feet"
            }
            val mi = m / 1609.344
            return if (mi < 10) {
                val r = (mi * 10).roundToInt() / 10.0
                if (r == 1.0) "1 mile" else "${trimDecimal(r)} miles"
            } else "${mi.roundToInt()} miles"
        }
        if (m < 1000) return "${maxOf(50, (m / 50).roundToInt() * 50)} meters"
        val km = (m / 100).roundToInt() / 10.0
        return if (km == 1.0) "1 kilometer" else "${trimDecimal(km)} kilometers"
    }

    private fun trimDecimal(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // ── Session control ─────────────────────────────────────────────────

    /** Starts navigating from the current location to the destination. Replaces any running session. */
    fun start(destLat: Double, destLon: Double, profile: RouteProfile) {
        stop()
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            _state.value = NavState.Idle("Location permission is needed to navigate")
            return
        }
        ensureTts()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val channel = Channel<Location>(Channel.CONFLATED)
        val gen: Int
        synchronized(sessionLock) {
            gen = ++generation
            scope = s
            fixes = channel
        }
        _state.value = NavState.Routing(destLat, destLon, profile)
        startUpdates()
        s.launch {
            val fix = withTimeoutOrNull(FIRST_FIX_TIMEOUT_MS) { lastFix.filterNotNull().first { isRecent(it) } }
                ?: lastKnownLocation()
            if (fix == null) {
                endSession(gen, "Could not get a location fix")
                return@launch
            }
            val route = router.route(fix.latitude, fix.longitude, destLat, destLon, profile, nowSec())
            if (route == null || route.geometry.size < 2) {
                endSession(gen, "No route found to that destination")
                return@launch
            }
            routeLock.withLock {
                if (generation != gen) return@launch
                this@Navigator.destLat = destLat
                this@Navigator.destLon = destLon
                this@Navigator.profile = profile
                alertedIncidents.clear()
                installTrack(Track(route))
                publish(fix, null, 0.0, route.geometry.first().lat, route.geometry.first().lon)
            }
            traffic.startLive(s)
            probe.start(s)
            s.launch { traffic.ensureTypical(route.edges.map { graph.segmentKey(it) }.toSet()) }
            route.maneuvers.firstOrNull()?.let { speak(it.instruction, flush = true) }
            s.launch { for (loc in channel) process(loc, gen) }
            s.launch {
                while (isActive) {
                    delay(FASTER_CHECK_MS)
                    checkFaster(gen)
                }
            }
        }
    }

    /** Ends navigation. Unsent probe samples are discarded. */
    fun stop() {
        endSession(null, null)
    }

    /** Stops and frees the voice engine; the navigator can still be started again afterwards. */
    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun endSession(onlyGen: Int?, error: String?) {
        val s: CoroutineScope?
        synchronized(sessionLock) {
            if (onlyGen != null && onlyGen != generation) return
            generation++
            s = scope
            scope = null
            fixes?.close()
            fixes = null
        }
        stopUpdates()
        NavigationService.stop(appContext)
        traffic.stopLive()
        probe.stop()
        matcher.reset()
        tts?.stop()
        s?.cancel()
        _state.value = NavState.Idle(error)
    }

    // ── Location ────────────────────────────────────────────────────────

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) onLocation(loc)
        }
    }

    @SuppressLint("MissingPermission") // checked in start()
    private fun startUpdates() {
        synchronized(sessionLock) {
            if (updatesOn) return
            updatesOn = true
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FIX_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FIX_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            // Without a foreground service, Android throttles fused location to a few fixes an
            // hour once Clairix leaves the screen (Drive Mode's own service may not be running).
            NavigationService.start(appContext)
        } catch (e: SecurityException) {
            synchronized(sessionLock) { updatesOn = false }
            endSession(null, "Location permission is needed to navigate")
        }
    }

    private fun stopUpdates() {
        synchronized(sessionLock) {
            if (!updatesOn) return
            updatesOn = false
        }
        fused.removeLocationUpdates(callback)
    }

    @SuppressLint("MissingPermission")
    private suspend fun lastKnownLocation(): Location? = try {
        suspendCancellableCoroutine { cont ->
            fused.lastLocation
                .addOnSuccessListener { cont.resume(it?.takeIf { l -> isRecent(l, 120_000L) }) }
                .addOnFailureListener { cont.resume(null) }
        }
    } catch (e: SecurityException) {
        null
    }

    private fun isRecent(l: Location, maxAgeMs: Long = 10_000L): Boolean =
        System.currentTimeMillis() - l.time <= maxAgeMs

    /** Feeds one fix into the session. Called by the location callback; may also be called directly. */
    fun onLocation(location: Location) {
        lastFix.value = location
        val channel = synchronized(sessionLock) { fixes } ?: return
        channel.trySend(location)
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    // ── Following the route ─────────────────────────────────────────────

    private suspend fun process(loc: Location, gen: Int) {
        val speed = if (loc.hasSpeed() && !(loc.hasSpeedAccuracy() && loc.speedAccuracyMetersPerSecond > 5f)) loc.speed else null
        val bearing = if (loc.hasBearing()) loc.bearing else null
        val hit = matcher.match(loc.latitude, loc.longitude, bearing, speed, loc.time)
        if (hit != null) {
            // The segment's midpoint from the map, never the fix itself.
            val a = graph.edgeFrom(hit.edge)
            val b = graph.edgeTo(hit.edge)
            probe.onMatched(
                graph.segmentKey(hit.edge),
                hit.fraction * graph.edgeLengthM(hit.edge),
                speed,
                loc.time,
                (graph.nodeLat(a) + graph.nodeLat(b)) / 2,
                (graph.nodeLon(a) + graph.nodeLon(b)) / 2,
            )
        } else {
            probe.onMatched(null, 0.0, speed, loc.time)
        }

        var arrived = false
        var reroute = false
        routeLock.withLock {
            if (generation != gen) return
            val t = track ?: return
            val p = locate(t, loc.latitude, loc.longitude)
            val off = p.offM > OFF_ROUTE_M
            if (off) {
                if (!loc.hasAccuracy() || loc.accuracy <= MAX_ACCURACY_FOR_OFF_ROUTE_M) offCount++
            } else {
                offCount = 0
                reroutesOffRoute = 0
                progressM = p.alongM
                progressSeg = p.segment
            }
            val now = System.currentTimeMillis()
            val cooldown = minOf(REROUTE_MAX_COOLDOWN_MS, REROUTE_COOLDOWN_MS shl minOf(reroutesOffRoute, 4))
            if (offCount >= OFF_ROUTE_FIXES && !rerouting && now - lastRerouteMs > cooldown) {
                reroute = true
            }
            probe.nearDestination(t.total - progressM <= PROBE_END_ZONE_M)
            if (!off && t.total - progressM <= ARRIVED_M) {
                arrived = true
                return@withLock
            }
            val snappedLat = if (off) loc.latitude else p.lat
            val snappedLon = if (off) loc.longitude else p.lon
            publish(loc, hit, if (off) p.offM else 0.0, snappedLat, snappedLon)
            announce(t)
        }
        if (arrived) {
            speak("You have arrived at your destination", flush = true)
            endSession(gen, null)
            return
        }
        if (reroute) launchReroute(gen, loc)
    }

    /** Projects a point onto the route near the current progress, falling back to the whole route after a jump. */
    private fun locate(t: Track, lat: Double, lon: Double): Projection {
        val from = maxOf(0, progressSeg - LOOKBEHIND_POINTS)
        var to = progressSeg + 1
        while (to < t.geometry.size - 1 && t.cum[to] < progressM + LOOKAHEAD_M) to++
        val near = project(t, lat, lon, from, minOf(to, t.geometry.size - 1))
        if (near.offM <= OFF_ROUTE_M) return near
        val all = project(t, lat, lon, 0, t.geometry.size - 1)
        return if (all.offM < near.offM) all else near
    }

    /** Closest point on geometry segments [from, to) (segment i joins points i and i+1). */
    private fun project(t: Track, lat: Double, lon: Double, from: Int, to: Int): Projection {
        val g = t.geometry
        val kx = cos(lat * PI / 180) * RoadGeo.M_PER_DEG_LAT
        val ky = RoadGeo.M_PER_DEG_LAT
        var bestD = Double.MAX_VALUE
        var bestAlong = 0.0
        var bestSeg = from
        var bestLat = lat
        var bestLon = lon
        val end = maxOf(from + 1, to)
        for (i in from until minOf(end, g.size - 1)) {
            val a = g[i]
            val b = g[i + 1]
            val ax = (a.lon - lon) * kx; val ay = (a.lat - lat) * ky
            val dx = (b.lon - a.lon) * kx; val dy = (b.lat - a.lat) * ky
            val len2 = dx * dx + dy * dy
            val f = if (len2 <= 0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + f * dx; val py = ay + f * dy
            val d = sqrt(px * px + py * py)
            if (d < bestD) {
                bestD = d
                bestAlong = t.cum[i] + f * (t.cum[i + 1] - t.cum[i])
                bestSeg = i
                bestLat = a.lat + f * (b.lat - a.lat)
                bestLon = a.lon + f * (b.lon - a.lon)
            }
        }
        return Projection(bestAlong, bestD, bestSeg, bestLat, bestLon)
    }

    private fun nextManeuverIndex(t: Track): Int {
        val ms = t.route.maneuvers
        for (i in ms.indices) {
            val m = ms[i]
            if (m.type != ManeuverType.DEPART && m.distanceFromStartM > progressM + MANEUVER_PASSED_M) return i
        }
        return -1
    }

    private fun publish(loc: Location, hit: EdgeHit?, offM: Double, lat: Double, lon: Double) {
        val t = track ?: return
        val now = nowSec()
        val edgeIdx = t.edgeIndexAt(progressM)
        val routeEdge = if (edgeIdx >= 0) t.edges[edgeIdx] else -1
        val off = offM > OFF_ROUTE_M
        val streetEdge = if (off && hit != null) hit.edge else routeEdge
        val street = (if (streetEdge >= 0) graph.edgeName(streetEdge) else null)
            ?: hit?.let { graph.edgeName(it.edge) }
        val limitEdge = if (hit != null) hit.edge else routeEdge
        val limit = if (limitEdge >= 0) graph.edgeMaxspeedKph(limitEdge).takeIf { it > 0 } else null
        val mi = nextManeuverIndex(t)
        val next = if (mi >= 0) t.route.maneuvers[mi] else null
        val remaining = remainingSec(t, progressM, now)
        _state.value = NavState.Navigating(
            route = t.route,
            destLat = destLat,
            destLon = destLon,
            profile = profile,
            nextManeuver = next,
            distanceToManeuverM = next?.let { maxOf(0.0, it.distanceFromStartM - progressM) } ?: 0.0,
            progressM = progressM,
            remainingDistanceM = maxOf(0.0, t.total - progressM),
            remainingSec = remaining,
            etaEpochMs = System.currentTimeMillis() + (remaining * 1000).roundToLong(),
            currentStreet = street,
            speedLimitKph = limit,
            offRoute = off,
            rerouting = rerouting,
            incidentsAhead = incidentsAhead(t),
            position = LatLon(lat, lon),
            bearingDeg = if (loc.hasBearing()) loc.bearing else null,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
        )
    }

    private fun announce(t: Track) {
        val mi = nextManeuverIndex(t)
        if (mi >= 0) {
            val m = t.route.maneuvers[mi]
            val d = m.distanceFromStartM - progressM
            val stage = ANNOUNCE_AT_M.firstOrNull { d <= it }
            if (stage != null && announced.add(key(mi, stage))) {
                // A later, closer stage makes the farther ones moot (e.g. after a reroute right before a turn).
                for (s in ANNOUNCE_AT_M) if (s > stage) announced.add(key(mi, s))
                val text = if (stage == ANNOUNCE_AT_M[0]) m.instruction
                else "In ${spokenDistance(d)}, ${m.instruction.replaceFirstChar { it.lowercase(Locale.getDefault()) }}"
                speak(text)
            }
        }
        for (a in incidentsAhead(t)) {
            if (a.distanceM > INCIDENT_ALERT_M) break
            val text = when (a.incident.type) {
                Incident.POLICE -> "Police reported ahead"
                Incident.CRASH -> "Crash reported ahead"
                else -> continue
            }
            if (alertedIncidents.add(a.incident.id)) speak(text)
        }
    }

    private fun key(maneuver: Int, stage: Double): Long = (maneuver.toLong() shl 20) or stage.toLong()

    private fun incidentsAhead(t: Track): List<IncidentAhead> {
        val list = traffic.incidents.value
        if (list !== incidentsSeen) {
            incidentsSeen = list
            incidentPos = placeIncidents(t, list)
        }
        if (incidentPos.isEmpty()) return emptyList()
        val now = nowSec()
        val out = ArrayList<IncidentAhead>()
        for (inc in list) {
            if (inc.expiresAt in 1 until now) continue
            val along = incidentPos[inc.id] ?: continue
            val ahead = along - progressM
            if (ahead > 0 && ahead <= INCIDENTS_AHEAD_M) out.add(IncidentAhead(inc, ahead))
        }
        out.sortBy { it.distanceM }
        return out
    }

    /**
     * Where each incident lies along the route. One reported on a segment counts only if the route
     * drives that segment in that direction; one without a segment counts when it is on the line.
     */
    private fun placeIncidents(t: Track, list: List<Incident>): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (inc in list) {
            val seg = inc.seg
            if (seg != null) {
                val j = t.segKeys.indexOf(seg)
                if (j < 0) continue
                val endIdx = if (j + 1 < t.edges.size) t.startIdx[j + 1] else t.geometry.size - 1
                val p = project(t, inc.lat, inc.lon, t.startIdx[j], maxOf(t.startIdx[j] + 1, endIdx))
                out[inc.id] = p.alongM
            } else {
                val p = project(t, inc.lat, inc.lon, 0, t.geometry.size - 1)
                if (p.offM <= INCIDENT_OFF_ROUTE_M) out[inc.id] = p.alongM
            }
        }
        return out
    }

    /** Must hold [routeLock]. */
    private fun installTrack(t: Track) {
        track = t
        progressM = 0.0
        progressSeg = 0
        offCount = 0
        announced.clear()
        incidentsSeen = null
        incidentPos = emptyMap()
    }

    // ── Time estimates ──────────────────────────────────────────────────

    /** Same precedence as the router: live, then typical for the hour, then posted limit, then road class. */
    private fun edgeKph(e: Int, epochSec: Long): Double {
        val key = graph.segmentKey(e)
        val kph = traffic.liveKph(key)
            ?: traffic.typicalKph(key, epochSec)
            ?: graph.edgeMaxspeedKph(e).takeIf { it > 0 }?.let { it * 0.9 }
            ?: CLASS_KPH[graph.edgeRoadClass(e).coerceIn(0, CLASS_KPH.size - 1)]
        return kph.coerceIn(MIN_KPH, MAX_KPH)
    }

    /** Seconds to drive the rest of [t] from [fromM] at current speeds, with junction and turn delays. */
    private fun remainingSec(t: Track, fromM: Double, nowSec: Long): Double {
        if (t.edges.isEmpty()) return 0.0
        var sec = 0.0
        val last = t.edges.size - 1
        for (j in maxOf(0, t.edgeIndexAt(fromM))..last) {
            val start = maxOf(t.startDist[j], fromM)
            val len = t.startDist[j + 1] - start
            if (len <= 0) continue
            val e = t.edges[j]
            sec += len / (edgeKph(e, nowSec + sec.toLong()) / 3.6)
            if (j < last) {
                val flags = graph.edgeFlags(e)
                if (flags and EdgeFlags.SIGNALS != 0) sec += SIGNAL_SEC
                if (flags and EdgeFlags.STOP != 0) sec += STOP_SEC
            }
        }
        for (m in t.route.maneuvers) {
            if (m.distanceFromStartM <= fromM) continue
            sec += when (m.type) {
                ManeuverType.LEFT, ManeuverType.SHARP_LEFT -> LEFT_SEC
                ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT -> RIGHT_SEC
                ManeuverType.UTURN -> UTURN_SEC
                else -> 0.0
            }
        }
        return sec
    }

    // ── Rerouting ───────────────────────────────────────────────────────

    private fun launchReroute(gen: Int, from: Location) {
        val s = synchronized(sessionLock) { if (generation == gen) scope else null } ?: return
        if (!searching.compareAndSet(false, true)) return
        rerouting = true
        s.launch {
            try {
                val (lat, lon, prof) = routeLock.withLock {
                    lastRerouteMs = System.currentTimeMillis()
                    reroutesOffRoute++
                    Triple(destLat, destLon, profile)
                }
                val route = router.route(from.latitude, from.longitude, lat, lon, prof, nowSec())
                if (route == null || route.geometry.size < 2) {
                    Log.i(TAG, "Reroute found no route; keeping the old one")
                    return@launch
                }
                val t = Track(route)
                routeLock.withLock {
                    if (generation != gen) return@launch
                    installTrack(t)
                    val fix = lastFix.value ?: from
                    val p = project(t, fix.latitude, fix.longitude, 0, minOf(t.geometry.size - 1, 50))
                    progressM = p.alongM
                    progressSeg = p.segment
                    tts?.stop()
                    publish(fix, null, 0.0, p.lat, p.lon)
                }
                launch { traffic.ensureTypical(route.edges.map { graph.segmentKey(it) }.toSet()) }
            } finally {
                rerouting = false
                searching.set(false)
            }
        }
    }

    /** Switches to a fresh route from the current position when it beats the remaining one by 3 minutes. */
    private suspend fun checkFaster(gen: Int) {
        val fix = lastFix.value ?: return
        val (lat, lon, prof) = routeLock.withLock {
            if (generation != gen || track == null || offCount > 0 || rerouting) return
            Triple(destLat, destLon, profile)
        }
        // SHORTEST asked for the shortest drive, not the quickest one; time savings do not apply.
        if (prof == RouteProfile.SHORTEST) return
        if (!searching.compareAndSet(false, true)) return
        try {
            val now = nowSec()
            val alt = router.route(fix.latitude, fix.longitude, lat, lon, prof, now) ?: return
            if (alt.geometry.size < 2) return
            val altTrack = Track(alt)
            val altSec = remainingSec(altTrack, 0.0, now)
            routeLock.withLock {
                if (generation != gen) return
                val cur = track ?: return
                val saving = remainingSec(cur, progressM, now) - altSec
                if (saving < FASTER_SAVING_SEC) return
                installTrack(altTrack)
                val p = project(altTrack, fix.latitude, fix.longitude, 0, minOf(altTrack.geometry.size - 1, 50))
                progressM = p.alongM
                progressSeg = p.segment
                val minutes = (saving / 60).roundToInt()
                speak("Faster route found. Saves $minutes minutes.", flush = true)
                publish(fix, null, 0.0, p.lat, p.lon)
            }
            traffic.ensureTypical(alt.edges.map { graph.segmentKey(it) }.toSet())
        } finally {
            searching.set(false)
        }
    }
}
