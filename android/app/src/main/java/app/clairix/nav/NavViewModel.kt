package app.clairix.nav

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

/** How a stretch of road is flowing compared with its free-flow speed. */
enum class TrafficLevel { FREE, SLOW, HEAVY, STOPPED, UNKNOWN }

/** Consecutive route edges with the same [level], drawn as one polyline. */
data class TrafficRun(val points: List<LatLon>, val level: TrafficLevel)

/** Whether a road graph is loaded for navigation. */
sealed interface GraphState {
    data object None : GraphState
    data class Loading(val slug: String, val name: String) : GraphState
    data class Ready(val slug: String, val name: String, val graph: RoadGraph) : GraphState
    data class Failed(val slug: String, val name: String, val message: String) : GraphState
}

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<Place> = emptyList(),
    val error: String? = null,
)

/** The route choice shown before navigating to [dest]. */
data class RouteOptions(
    val dest: Place,
    val loading: Boolean = true,
    val routes: List<Route> = emptyList(),
    val selected: Int = 0,
    val error: String? = null,
) {
    val selectedRoute: Route? get() = routes.getOrNull(selected)
}

/**
 * Owns one region's navigation stack (graph, traffic, router, probes, navigator) for the Drive
 * tab, plus the destination search and route preview that come before navigating.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val PREFS = "nav"
        const val KEY_REGION = "region"
        const val KEY_FUEL_PRICE = "fuel_price_per_gal"
        const val KEY_MUTED = "muted"
        const val DEFAULT_FUEL_PRICE = 3.50f
        const val RECOLOR_INTERVAL_MS = 30_000L
        const val IDLE_TRAFFIC_INTERVAL_MS = 60_000L
        const val LOCATION_INTERVAL_MS = 2_000L
        const val FIRST_FIX_WAIT_MS = 20_000L
        const val LIVE_TRAFFIC_WAIT_MS = 4_000L
        /** Share of the free heap a region's routing arrays and spatial index may take. */
        const val HEAP_BUDGET_SHARE = 0.6
        const val REPORT_SNAP_M = 35.0
        const val REPORT_HEADING_TOLERANCE_DEG = 60.0
        /** Destinations this far outside the map's box cannot be snapped to any road in it. */
        const val OUTSIDE_MARGIN_DEG = 0.05
        val CLASS_KPH = doubleArrayOf(105.0, 85.0, 65.0, 55.0, 45.0, 40.0, 30.0, 15.0)
    }

    /** Everything built from one installed graph. */
    private class Session(
        val slug: String,
        val graph: RoadGraph,
        val traffic: TrafficClient,
        val router: Router,
        val navigator: Navigator,
    )

    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val store = GraphStore(app)
    private val geocoder = Geocoder(app)

    private val session = MutableStateFlow<Session?>(null)

    private val _graph = MutableStateFlow<GraphState>(GraphState.None)
    val graph: StateFlow<GraphState> = _graph.asStateFlow()

    private val _installed = MutableStateFlow<List<InstalledRegion>>(emptyList())
    val installed: StateFlow<List<InstalledRegion>> = _installed.asStateFlow()

    val download: StateFlow<GraphDownload> = store.progress
    val regions: List<MapRegion> = GraphStore.US_STATES

    private val _location = MutableStateFlow<Location?>(null)
    /** The latest fix from this screen's own location updates (used before and between drives). */
    val location: StateFlow<Location?> = _location.asStateFlow()

    private val _hasPermission = MutableStateFlow(checkPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _options = MutableStateFlow<RouteOptions?>(null)
    val options: StateFlow<RouteOptions?> = _options.asStateFlow()

    val navState: StateFlow<NavState> = session
        .flatMapLatest { it?.navigator?.state ?: flowOf(NavState.Idle()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NavState.Idle())

    val incidents: StateFlow<List<Incident>> = session
        .flatMapLatest { it?.traffic?.incidents ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val trafficAvailable: StateFlow<Boolean> = session
        .flatMapLatest { it?.traffic?.available ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _muted = MutableStateFlow(prefs.getBoolean(KEY_MUTED, false))
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _fuelPrice = MutableStateFlow(prefs.getFloat(KEY_FUEL_PRICE, DEFAULT_FUEL_PRICE).toDouble())
    /** US dollars per gallon, for the fuel cost shown on each route. */
    val fuelPricePerGal: StateFlow<Double> = _fuelPrice.asStateFlow()

    private val _answered = MutableStateFlow<Set<String>>(emptySet())
    /** Incidents already answered (or dismissed) at the "Still there?" prompt this session. */
    val answeredIncidents: StateFlow<Set<String>> = _answered.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private val tick: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(RECOLOR_INTERVAL_MS)
        }
    }

    /** Traffic colouring of the previewed route. */
    val previewRuns: StateFlow<List<TrafficRun>> = runsFor(_options.map { it?.selectedRoute })

    /** Traffic colouring of the route being driven. */
    val navRuns: StateFlow<List<TrafficRun>> = runsFor(navState.map { (it as? NavState.Navigating)?.route })

    private val fused = LocationServices.getFusedLocationProviderClient(app)
    private var locationActive = false

    private var loadJob: Job? = null
    private var downloadJob: Job? = null
    private var pendingLoadJob: Job? = null
    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private var reverseJob: Job? = null

    init {
        viewModelScope.launch {
            refreshInstalled()
            val saved = prefs.getString(KEY_REGION, null)
            val pick = _installed.value.firstOrNull { it.slug == saved } ?: _installed.value.firstOrNull()
            // A region chosen or downloaded meanwhile wins over the remembered one.
            if (pick != null && _graph.value == GraphState.None) load(pick.slug)
        }
        // While not navigating, keep incidents on the map current; the navigator refreshes them itself while driving.
        viewModelScope.launch {
            while (isActive) {
                val s = session.value
                if (s != null && navState.value !is NavState.Navigating && locationActive) s.traffic.refreshLive()
                delay(IDLE_TRAFFIC_INTERVAL_MS)
            }
        }
        viewModelScope.launch {
            navState.collect { st ->
                if (st is NavState.Idle && st.error != null) messageChannel.trySend(st.error)
            }
        }
    }

    private fun runsFor(routes: Flow<Route?>): StateFlow<List<TrafficRun>> =
        combine(routes.distinctUntilChanged(), session, tick) { r, s, _ -> r to s }
            .mapLatest { (r, s) ->
                if (r == null || s == null) emptyList() else withContext(Dispatchers.Default) { trafficRuns(s, r) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Regions ─────────────────────────────────────────────────────────

    /** Lists the graphs on disk (reads each file's header) off the main thread. */
    private suspend fun refreshInstalled() {
        _installed.value = withContext(Dispatchers.IO) { store.installed() }
    }

    private fun regionName(slug: String): String = regions.firstOrNull { it.slug == slug }?.name ?: slug

    /** Switches navigation to an installed region. */
    fun selectRegion(slug: String) {
        // An explicit choice overrides a map that was waiting for the drive to end.
        pendingLoadJob?.cancel()
        if ((session.value?.slug == slug) && _graph.value is GraphState.Ready) return
        load(slug)
    }

    private fun load(slug: String) {
        loadJob?.cancel()
        closeSession()
        prefs.edit().putString(KEY_REGION, slug).apply()
        val name = regionName(slug)
        _graph.value = GraphState.Loading(slug, name)
        loadJob = viewModelScope.launch {
            try {
                val s = withContext(Dispatchers.IO) {
                    val graph = store.open(slug)
                    val rt = Runtime.getRuntime()
                    val headroom = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
                    // The rest of the app and each search's frontier need the remaining 40%.
                    if (Router.estimatedHeapBytes(graph) > headroom * HEAP_BUDGET_SHARE) {
                        throw IllegalStateException("This map is too large for this phone's memory")
                    }
                    // Builds the spatial index now rather than on the first tap or route request.
                    graph.nearestEdges((graph.minLat + graph.maxLat) / 2, (graph.minLon + graph.maxLon) / 2, 50.0, 1)
                    val traffic = TrafficClient(ctx, slug)
                    val router = Router(graph, traffic)
                    val probe = ProbeReporter(ctx, slug)
                    val navigator = Navigator(ctx, graph, router, traffic, probe)
                    navigator.muted = _muted.value
                    Session(slug, graph, traffic, router, navigator)
                }
                session.value = s
                _graph.value = GraphState.Ready(slug, name, s.graph)
                if (locationActive) launch { s.traffic.refreshLive() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _graph.value = GraphState.Failed(slug, name, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun closeSession() {
        val s = session.value ?: return
        session.value = null
        s.navigator.release()
        s.traffic.stopLive()
        routeJob?.cancel()
        _options.value = null
    }

    fun downloadRegion(region: MapRegion) {
        if (downloadJob?.isActive == true) return
        val active = session.value
        if (active?.slug == region.slug && active.navigator.state.value !is NavState.Idle) {
            messageChannel.trySend("End the drive before updating the ${region.name} map")
            return
        }
        downloadJob = viewModelScope.launch {
            store.download(region).onSuccess {
                refreshInstalled()
                // A drive may have started while the file was downloading; swapping graphs would end it.
                val driving = session.value?.navigator?.state?.value.let { it != null && it !is NavState.Idle }
                if (driving) {
                    messageChannel.trySend("${region.name} map downloaded; it will be used after this drive")
                    pendingLoadJob?.cancel()
                    pendingLoadJob = viewModelScope.launch {
                        navState.first { it is NavState.Idle }
                        load(region.slug)
                    }
                } else {
                    load(region.slug)
                }
            }.onFailure { e ->
                messageChannel.trySend("${region.name}: ${e.message ?: "download failed"}")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun deleteRegion(slug: String) {
        val active = session.value
        if (active?.slug == slug && active.navigator.state.value !is NavState.Idle) {
            messageChannel.trySend("End the drive before deleting the ${regionName(slug)} map")
            return
        }
        if (active?.slug == slug) {
            loadJob?.cancel()
            closeSession()
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { store.delete(slug) }
            if (!ok) messageChannel.trySend("Could not delete the ${regionName(slug)} map")
            refreshInstalled()
            val current = _graph.value
            val currentSlug = when (current) {
                is GraphState.Ready -> current.slug
                is GraphState.Loading -> current.slug
                is GraphState.Failed -> current.slug
                GraphState.None -> null
            }
            if (currentSlug == slug || currentSlug == null) {
                val next = _installed.value.firstOrNull()
                if (next != null) load(next.slug) else {
                    prefs.edit().remove(KEY_REGION).apply()
                    _graph.value = GraphState.None
                }
            }
        }
    }

    // ── Location ────────────────────────────────────────────────────────

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { _location.value = it }
        }
    }

    private fun checkPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Called after the permission dialog closes. */
    fun onPermissionResult() {
        _hasPermission.value = checkPermission()
        if (locationActive) {
            locationActive = false
            setLocationActive(true)
        }
    }

    /** Location updates for the map while the screen is visible. */
    @SuppressLint("MissingPermission") // checked just before
    fun setLocationActive(active: Boolean) {
        if (active == locationActive) return
        if (!active) {
            locationActive = false
            fused.removeLocationUpdates(locationCallback)
            return
        }
        locationActive = true
        _hasPermission.value = checkPermission()
        if (!_hasPermission.value) return
        try {
            fused.lastLocation.addOnSuccessListener { l -> if (l != null && _location.value == null) _location.value = l }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS / 2)
                .build()
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            _hasPermission.value = false
        }
    }

    /** Best known position: the navigator's snapped one while driving, else the latest fix. */
    private fun currentPosition(): Triple<Double, Double, Float?>? {
        val nav = navState.value
        if (nav is NavState.Navigating) nav.position?.let { return Triple(it.lat, it.lon, nav.bearingDeg) }
        val l = _location.value ?: return null
        return Triple(l.latitude, l.longitude, if (l.hasBearing()) l.bearing else null)
    }

    // ── Search ──────────────────────────────────────────────────────────

    fun setQuery(q: String) {
        _search.value = _search.value.copy(query = q, error = null)
    }

    /**
     * Runs the typed search. Only on submit: Nominatim's usage policy forbids search-as-you-type.
     */
    fun submitSearch() {
        val q = _search.value.query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        _search.value = _search.value.copy(loading = true, error = null, results = emptyList())
        searchJob = viewModelScope.launch {
            try {
                val results = geocoder.search(q, session.value?.graph)
                _search.value = _search.value.copy(
                    loading = false,
                    results = results,
                    error = if (results.isEmpty()) "No places found for \"$q\"" else null,
                )
            } catch (e: IOException) {
                _search.value = _search.value.copy(loading = false, error = e.message ?: "Search failed")
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = SearchState()
    }

    // ── Route preview ───────────────────────────────────────────────────

    fun chooseDestination(place: Place) {
        searchJob?.cancel()
        _search.value = SearchState(query = place.name)
        computeRoutes(place, RouteProfile.FASTEST)
    }

    /** Long-press on the map: route there at once, and fill in the address when the lookup returns. */
    fun pickPoint(lat: Double, lon: Double) {
        val label = String.format(Locale.US, "%.5f, %.5f", lat, lon)
        val pin = Place("Dropped pin", label, lat, lon)
        _search.value = SearchState()
        computeRoutes(pin, RouteProfile.FASTEST)
        reverseJob?.cancel()
        reverseJob = viewModelScope.launch {
            val found = try { geocoder.reverse(lat, lon) } catch (e: IOException) { null } ?: return@launch
            // Keep the pin's exact coordinates: the user chose that spot, not the address centroid.
            val named = Place(found.name, found.displayName, lat, lon)
            val cur = _options.value ?: return@launch
            if (cur.dest.lat == lat && cur.dest.lon == lon) _options.value = cur.copy(dest = named)
        }
    }

    fun closeOptions() {
        routeJob?.cancel()
        reverseJob?.cancel()
        _options.value = null
    }

    private fun computeRoutes(dest: Place, preferred: RouteProfile) {
        routeJob?.cancel()
        _options.value = RouteOptions(dest)
        val s = session.value
        if (s == null) {
            _options.value = RouteOptions(dest, loading = false, error = "No road map is loaded")
            return
        }
        val g = s.graph
        if (dest.lat < g.minLat - OUTSIDE_MARGIN_DEG || dest.lat > g.maxLat + OUTSIDE_MARGIN_DEG ||
            dest.lon < g.minLon - OUTSIDE_MARGIN_DEG || dest.lon > g.maxLon + OUTSIDE_MARGIN_DEG
        ) {
            _options.value = RouteOptions(dest, loading = false, error = "This place is outside the ${regionName(s.slug)} map")
            return
        }
        routeJob = viewModelScope.launch {
            if (currentPosition() == null && _hasPermission.value) {
                withTimeoutOrNull(FIRST_FIX_WAIT_MS) { _location.filterNotNull().first() }
            }
            val from = currentPosition() ?: run {
                _options.value = RouteOptions(
                    dest,
                    loading = false,
                    error = if (_hasPermission.value) "Your location is not known yet; try again in a moment" else "Location permission is needed to plan a route",
                )
                return@launch
            }
            // Offline routing must not wait on a stalled connection; stale traffic is better than none.
            val liveRefresh = launch { s.traffic.refreshLive() }
            withTimeoutOrNull(LIVE_TRAFFIC_WAIT_MS) { liveRefresh.join() }
            val now = System.currentTimeMillis() / 1000
            var routes = withContext(Dispatchers.Default) { s.router.alternatives(from.first, from.second, dest.lat, dest.lon, now) }
            if (routes.isEmpty()) {
                _options.value = RouteOptions(dest, loading = false, error = "No route found to this place")
                return@launch
            }
            publishRoutes(dest, routes, preferred)
            // Typical speeds for these roads may only now be fetched; the second pass prices them in.
            s.traffic.ensureTypical(routes.flatMap { r -> r.edges.map { g.segmentKey(it) } }.toSet())
            if (s.traffic.available.value) {
                val again = withContext(Dispatchers.Default) { s.router.alternatives(from.first, from.second, dest.lat, dest.lon, now) }
                if (again.isNotEmpty()) {
                    routes = again
                    val shown = _options.value
                    publishRoutes(dest, routes, shown?.selectedRoute?.profile ?: preferred)
                }
            }
        }
    }

    private fun publishRoutes(dest: Place, routes: List<Route>, preferred: RouteProfile) {
        val idx = routes.indexOfFirst { it.profile == preferred }.coerceAtLeast(0)
        val cur = _options.value
        // Keep a name the reverse lookup filled in meanwhile.
        val d = if (cur != null && cur.dest.lat == dest.lat && cur.dest.lon == dest.lon) cur.dest else dest
        _options.value = RouteOptions(d, loading = false, routes = routes, selected = idx)
    }

    fun selectRoute(index: Int) {
        val cur = _options.value ?: return
        if (index in cur.routes.indices) _options.value = cur.copy(selected = index)
    }

    /**
     * Selects the route for [profile]. Alternatives that matched another profile's route were
     * merged away, so a missing one is computed now and joins the list unless it is a duplicate.
     */
    fun selectProfile(profile: RouteProfile) {
        val cur = _options.value ?: return
        val have = cur.routes.indexOfFirst { it.profile == profile }
        if (have >= 0) {
            _options.value = cur.copy(selected = have)
            return
        }
        val s = session.value ?: return
        routeJob?.cancel()
        _options.value = cur.copy(loading = true)
        routeJob = viewModelScope.launch {
            val from = currentPosition()
            val r = if (from == null) null else withContext(Dispatchers.Default) {
                s.router.route(from.first, from.second, cur.dest.lat, cur.dest.lon, profile, System.currentTimeMillis() / 1000)
            }
            val latest = _options.value ?: return@launch
            if (r == null) {
                _options.value = latest.copy(loading = false)
                messageChannel.trySend("No ${profileLabel(profile).lowercase(Locale.US)} route found")
                return@launch
            }
            val same = latest.routes.indexOfFirst { it.edges.contentEquals(r.edges) }
            if (same >= 0) {
                // Same roads as an existing option: show it under the requested profile so GO navigates with it.
                val list = latest.routes.toMutableList().also { it[same] = r }
                _options.value = latest.copy(loading = false, routes = list, selected = same)
            } else {
                val list = (latest.routes + r).sortedBy { it.durationSec }
                _options.value = latest.copy(loading = false, routes = list, selected = list.indexOf(r))
            }
        }
    }

    // ── Navigation ──────────────────────────────────────────────────────

    fun startNavigation() {
        val opts = _options.value ?: return
        val route = opts.selectedRoute ?: return
        val s = session.value ?: return
        _answered.value = emptySet()
        s.navigator.muted = _muted.value
        s.navigator.start(opts.dest.lat, opts.dest.lon, route.profile)
        routeJob?.cancel()
        _options.value = null
        _search.value = SearchState()
    }

    fun stopNavigation() {
        session.value?.navigator?.stop()
    }

    fun setMuted(m: Boolean) {
        _muted.value = m
        prefs.edit().putBoolean(KEY_MUTED, m).apply()
        session.value?.navigator?.muted = m
    }

    fun setFuelPrice(dollarsPerGal: Double) {
        if (dollarsPerGal.isNaN() || dollarsPerGal <= 0 || dollarsPerGal > 50) return
        _fuelPrice.value = dollarsPerGal
        prefs.edit().putFloat(KEY_FUEL_PRICE, dollarsPerGal.toFloat()).apply()
    }

    // ── Reports ─────────────────────────────────────────────────────────

    /** Reports an incident where the car is, tied to the road segment being driven when it is clear which one. */
    fun report(type: String) {
        val s = session.value ?: run { messageChannel.trySend("No road map is loaded"); return }
        val pos = currentPosition() ?: run { messageChannel.trySend("Your location is not known yet"); return }
        viewModelScope.launch {
            val seg = withContext(Dispatchers.Default) { segmentAt(s.graph, pos.first, pos.second, pos.third) }
            s.traffic.report(type, pos.first, pos.second, seg)
                .onSuccess { inc ->
                    val n = inc.confirmations
                    messageChannel.trySend(
                        if (n > 1) "${incidentLabel(type)} confirmed ($n reports)" else "${incidentLabel(type)} reported"
                    )
                }
                .onFailure { e -> messageChannel.trySend("Report not sent: ${e.message ?: "relay unreachable"}") }
        }
    }

    private fun segmentAt(g: RoadGraph, lat: Double, lon: Double, bearing: Float?): String? {
        val hits = g.nearestEdges(lat, lon, REPORT_SNAP_M, 8)
        if (hits.isEmpty()) return null
        val hit = if (bearing != null) {
            hits.firstOrNull { abs(RoadGeo.deltaDeg(it.bearingDeg, bearing.toDouble())) <= REPORT_HEADING_TOLERANCE_DEG }
        } else {
            // Without a heading only an unambiguous road will do: one way, or a single direction of it.
            hits.takeIf { h -> h.size == 1 }?.first()
        }
        return hit?.let { g.segmentKey(it.edge) }
    }

    /** Answers "Still there?" for an incident ahead. */
    fun vote(incident: Incident, still: Boolean) {
        _answered.value = _answered.value + incident.id
        val s = session.value ?: return
        viewModelScope.launch {
            s.traffic.vote(incident.id, still)
                .onSuccess { messageChannel.trySend("Thanks for confirming") }
                .onFailure { e -> messageChannel.trySend("Vote not sent: ${e.message ?: "relay unreachable"}") }
        }
    }

    fun dismissIncident(incident: Incident) {
        _answered.value = _answered.value + incident.id
    }

    // ── Traffic colouring ───────────────────────────────────────────────

    private fun freeFlowKph(g: RoadGraph, e: Int): Double {
        val max = g.edgeMaxspeedKph(e)
        return if (max > 0) max * 0.9 else CLASS_KPH[g.edgeRoadClass(e).coerceIn(0, CLASS_KPH.size - 1)]
    }

    private fun levelOf(s: Session, e: Int, nowSec: Long): TrafficLevel {
        val key = s.graph.segmentKey(e)
        val kph = s.traffic.liveKph(key) ?: s.traffic.typicalKph(key, nowSec) ?: return TrafficLevel.UNKNOWN
        val ratio = kph / freeFlowKph(s.graph, e)
        return when {
            ratio >= 0.8 -> TrafficLevel.FREE
            ratio >= 0.5 -> TrafficLevel.SLOW
            ratio >= 0.25 -> TrafficLevel.HEAVY
            else -> TrafficLevel.STOPPED
        }
    }

    /** Splits [route]'s geometry at edge boundaries and merges neighbouring edges of equal level. */
    private fun trafficRuns(s: Session, route: Route): List<TrafficRun> {
        val geo = route.geometry
        val edges = route.edges
        if (geo.size < 2 || edges.isEmpty()) return emptyList()
        val g = s.graph
        val start = IntArray(edges.size)
        // Each later edge begins at its from-node, which the router placed in the geometry exactly.
        for (i in 1 until edges.size) {
            val n = g.edgeFrom(edges[i])
            val nLat = g.nodeLat(n)
            val nLon = g.nodeLon(n)
            var j = start[i - 1] + 1
            var found = -1
            while (j < geo.size) {
                if (RoadGeo.haversineM(geo[j].lat, geo[j].lon, nLat, nLon) < 0.5) { found = j; break }
                j++
            }
            start[i] = if (found >= 0) found else start[i - 1]
        }
        val now = System.currentTimeMillis() / 1000
        val runs = ArrayList<TrafficRun>()
        var curLevel: TrafficLevel? = null
        var curPts = ArrayList<LatLon>()
        for (i in edges.indices) {
            val from = start[i]
            val to = if (i + 1 < edges.size) start[i + 1] else geo.size - 1
            if (to <= from) continue
            val level = levelOf(s, edges[i], now)
            if (level != curLevel) {
                if (curLevel != null && curPts.size >= 2) runs.add(TrafficRun(curPts, curLevel))
                curLevel = level
                curPts = ArrayList()
                curPts.add(geo[from])
            }
            for (k in from + 1..to) curPts.add(geo[k])
        }
        if (curLevel != null && curPts.size >= 2) runs.add(TrafficRun(curPts, curLevel))
        return runs
    }

    override fun onCleared() {
        setLocationActive(false)
        loadJob?.cancel()
        downloadJob?.cancel()
        closeSession()
        super.onCleared()
    }
}

fun profileLabel(p: RouteProfile): String = when (p) {
    RouteProfile.FASTEST -> "Fastest"
    RouteProfile.NO_TOLLS -> "No tolls"
    RouteProfile.SHORTEST -> "Shortest"
    RouteProfile.ECO -> "Eco"
    RouteProfile.NO_HIGHWAYS -> "No highways"
}

fun incidentLabel(type: String): String = when (type) {
    Incident.POLICE -> "Police"
    Incident.CRASH -> "Crash"
    Incident.HAZARD -> "Hazard"
    Incident.CLOSURE -> "Closure"
    Incident.JAM -> "Traffic jam"
    else -> type.replaceFirstChar { it.titlecase(Locale.US) }
}
