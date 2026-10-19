package app.clairix.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.location.Location
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.clairix.BuildConfig
import app.clairix.R
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private const val M_PER_MILE = 1609.344
private const val FT_PER_M = 3.28084
private const val MPH_PER_MPS = 2.236936
private const val MPH_PER_KPH = 0.621371
private const val LITERS_PER_GALLON = 3.785411784
/** Over the posted limit by more than this, the speed readout turns red. */
private const val SPEEDING_MARGIN_MPH = 5
/** An incident this close ahead gets the "Still there?" prompt. */
private const val INCIDENT_PROMPT_M = 300.0
/** The SOS button floats over the Drive tab's bottom-right corner; panels keep clear of it. */
private val SOS_CLEARANCE = 72.dp

private val TRAFFIC_FREE = Color(0xFF2E9E4F)
private val TRAFFIC_SLOW = Color(0xFFF2A30F)
private val TRAFFIC_HEAVY = Color(0xFFE5392F)
private val TRAFFIC_STOPPED = Color(0xFF8E1010)
private val TRAFFIC_UNKNOWN = Color(0xFF2F6FEB)
private val ROUTE_ALT = Color(0xFF8A94A6)
private val ROUTE_CASING = Color(0xFF0D1B33)
private val DANGER = Color(0xFFE5392F)

private fun levelColor(level: TrafficLevel): Color = when (level) {
    TrafficLevel.FREE -> TRAFFIC_FREE
    TrafficLevel.SLOW -> TRAFFIC_SLOW
    TrafficLevel.HEAVY -> TRAFFIC_HEAVY
    TrafficLevel.STOPPED -> TRAFFIC_STOPPED
    TrafficLevel.UNKNOWN -> TRAFFIC_UNKNOWN
}

@DrawableRes
private fun incidentIcon(type: String): Int = when (type) {
    Incident.POLICE -> R.drawable.ic_incident_police
    Incident.CRASH -> R.drawable.ic_incident_crash
    Incident.CLOSURE -> R.drawable.ic_incident_closure
    Incident.JAM -> R.drawable.ic_incident_jam
    else -> R.drawable.ic_incident_hazard
}

// ── Formatting (US units) ──────────────────────────────────────────────

private fun fmtDistance(m: Double): String {
    val ft = m * FT_PER_M
    if (ft < 1000) return "${maxOf(0, (ft / 50).roundToInt() * 50)} ft"
    val mi = m / M_PER_MILE
    return if (mi < 10) String.format(Locale.US, "%.1f mi", mi) else "${mi.roundToInt()} mi"
}

private fun fmtDuration(sec: Double): String {
    val min = maxOf(0, (sec / 60).roundToInt())
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}

private fun fmtClock(context: Context, epochMs: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(Date(epochMs))

private fun limitMph(kph: Int): Int = ((kph * MPH_PER_KPH) / 5).roundToInt() * 5

private fun dp(v: Float): Float = v * Resources.getSystem().displayMetrics.density

// ── Screen ─────────────────────────────────────────────────────────────

/** Turn-by-turn navigation: offline routing on a downloaded state map, with crowd-sourced traffic. */
@Composable
fun NavScreen(modifier: Modifier = Modifier, vm: NavViewModel = viewModel()) {
    val graphState by vm.graph.collectAsStateWithLifecycle()
    val hasPermission by vm.hasPermission.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showRegions by rememberSaveable { mutableStateOf(false) }
    var askedPermission by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onPermissionResult()
    }
    val requestPermission = {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LaunchedEffect(graphState is GraphState.Ready, hasPermission) {
        if (graphState is GraphState.Ready && !hasPermission && !askedPermission) {
            askedPermission = true
            requestPermission()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.setLocationActive(true)
                Lifecycle.Event.ON_PAUSE -> vm.setLocationActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.setLocationActive(false)
        }
    }

    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val g = graphState) {
            is GraphState.Ready -> NavMapLayer(
                vm = vm,
                regionName = g.name,
                graph = g.graph,
                hasPermission = hasPermission,
                onRequestPermission = requestPermission,
                onShowRegions = { showRegions = true },
            )
            else -> RegionSetup(vm = vm, onClose = null)
        }
        if (showRegions && graphState is GraphState.Ready) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                RegionSetup(vm = vm, onClose = { showRegions = false })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).padding(top = 120.dp))
    }
}

@Composable
private fun NavMapLayer(
    vm: NavViewModel,
    regionName: String,
    graph: RoadGraph,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onShowRegions: () -> Unit,
) {
    val navState by vm.navState.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val previewRuns by vm.previewRuns.collectAsStateWithLifecycle()
    val navRuns by vm.navRuns.collectAsStateWithLifecycle()
    val incidents by vm.incidents.collectAsStateWithLifecycle()
    val muted by vm.muted.collectAsStateWithLifecycle()
    val fuelPrice by vm.fuelPricePerGal.collectAsStateWithLifecycle()
    val answered by vm.answeredIncidents.collectAsStateWithLifecycle()

    val nav = navState as? NavState.Navigating
    var following by remember { mutableStateOf(true) }
    var showReport by remember { mutableStateOf(false) }
    var showFuelDialog by remember { mutableStateOf(false) }

    LaunchedEffect(nav != null) { if (nav != null) following = true }

    Box(Modifier.fillMaxSize()) {
        NavMap(
            graph = graph,
            nav = nav,
            location = location,
            options = options,
            previewRuns = previewRuns,
            navRuns = navRuns,
            incidents = incidents,
            following = following,
            onUserPan = { following = false },
            onLongPress = { lat, lon -> if (nav == null) vm.pickPoint(lat, lon) },
            onSelectRoute = vm::selectRoute,
        )

        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                nav != null -> ManeuverBanner(nav)
                navState is NavState.Routing -> StatusCard("Finding your route…", busy = true, onCancel = vm::stopNavigation)
                else -> {
                    SearchBar(
                        state = search,
                        regionName = regionName,
                        onQuery = vm::setQuery,
                        onSubmit = vm::submitSearch,
                        onClear = vm::clearSearch,
                        onShowRegions = onShowRegions,
                    )
                    if (options == null && (search.loading || search.results.isNotEmpty() || search.error != null)) {
                        SearchResults(search, location, vm::chooseDestination)
                    }
                }
            }
            if (!hasPermission) PermissionCard(onRequestPermission)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val showRecenter = !following && (nav != null || location != null)
            if (showRecenter) {
                FilledTonalButton(
                    onClick = { following = true },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Re-center") }
            }
            if (nav != null) {
                val prompt = nav.incidentsAhead.firstOrNull {
                    it.distanceM <= INCIDENT_PROMPT_M && it.incident.id !in answered
                }
                if (prompt != null) {
                    IncidentPrompt(
                        ahead = prompt,
                        onVote = { still -> vm.vote(prompt.incident, still) },
                        onDismiss = { vm.dismissIncident(prompt.incident) },
                    )
                }
                NavBottomBar(
                    nav = nav,
                    muted = muted,
                    onMute = { vm.setMuted(!muted) },
                    onReport = { showReport = true },
                    onEnd = vm::stopNavigation,
                )
            } else {
                options?.let { opts ->
                    RouteOptionsSheet(
                        options = opts,
                        fuelPricePerGal = fuelPrice,
                        onProfile = vm::selectProfile,
                        onSelect = vm::selectRoute,
                        onGo = { idx -> vm.selectRoute(idx); vm.startNavigation() },
                        onClose = vm::closeOptions,
                        onEditFuel = { showFuelDialog = true },
                    )
                }
            }
        }
    }

    if (showReport) {
        ReportDialog(
            onReport = { type -> showReport = false; vm.report(type) },
            onDismiss = { showReport = false },
        )
    }
    if (showFuelDialog) {
        FuelPriceDialog(
            current = fuelPrice,
            onSave = { vm.setFuelPrice(it); showFuelDialog = false },
            onDismiss = { showFuelDialog = false },
        )
    }
}

// ── Map ────────────────────────────────────────────────────────────────

private var navOsmConfigured = false

/** Same osmdroid setup as the Map tab: identifying user agent and tiles kept a week past their expiry. */
private fun configureNavOsm(context: Context) {
    if (navOsmConfigured) return
    navOsmConfigured = true
    with(OsmConfig.getInstance()) {
        load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        userAgentValue = "Clairix/${BuildConfig.VERSION_NAME}"
        expirationExtendedDuration = 7L * 24 * 60 * 60 * 1000
    }
}

private fun LatLon.geo(): GeoPoint = GeoPoint(lat, lon)

private fun followZoom(speedMps: Float?): Double {
    val mph = (speedMps ?: 0f) * MPH_PER_MPS
    return when {
        mph < 30 -> 17.5
        mph < 55 -> 16.8
        else -> 16.0
    }
}

@SuppressLint("ClickableViewAccessibility") // the listener only observes drags; MapView still handles every touch
@Composable
private fun NavMap(
    graph: RoadGraph,
    nav: NavState.Navigating?,
    location: Location?,
    options: RouteOptions?,
    previewRuns: List<TrafficRun>,
    navRuns: List<TrafficRun>,
    incidents: List<Incident>,
    following: Boolean,
    onUserPan: () -> Unit,
    onLongPress: (Double, Double) -> Unit,
    onSelectRoute: (Int) -> Unit,
) {
    val context = LocalContext.current
    val longPress by rememberUpdatedState(onLongPress)
    val userPan by rememberUpdatedState(onUserPan)
    val selectRoute by rememberUpdatedState(onSelectRoute)

    val routeLayer = remember { FolderOverlay() }
    val incidentLayer = remember { FolderOverlay() }
    val markerLayer = remember { FolderOverlay() }

    val mapView = remember {
        configureNavOsm(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setTilesScaledToDpi(true)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isFlingEnabled = true
            setMinZoomLevel(4.0)
            controller.setZoom(9.0)
            controller.setCenter(GeoPoint((graph.minLat + graph.maxLat) / 2, (graph.minLon + graph.maxLon) / 2))
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false
                override fun longPressHelper(p: GeoPoint): Boolean {
                    longPress(p.latitude, p.longitude)
                    return true
                }
            }))
            overlays.add(routeLayer)
            overlays.add(incidentLayer)
            overlays.add(markerLayer)
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            setOnTouchListener(object : View.OnTouchListener {
                var downX = 0f
                var downY = 0f
                override fun onTouch(v: View, ev: MotionEvent): Boolean {
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
                        MotionEvent.ACTION_POINTER_DOWN -> userPan()
                        MotionEvent.ACTION_MOVE -> if (hypot(ev.x - downX, ev.y - downY) > slop) userPan()
                    }
                    return false
                }
            })
        }
    }

    val positionMarker = remember {
        Marker(mapView).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_nav_position)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            isFlat = true
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
        }
    }
    val destMarker = remember {
        Marker(mapView).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_nav_destination)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> true }
        }
    }

    // Routes: grey tappable alternatives under the selected or driven route, coloured by traffic.
    val previewRoutes = if (nav == null) options?.routes.orEmpty() else emptyList()
    val selectedIdx = options?.selected ?: -1
    LaunchedEffect(nav?.route, navRuns, previewRoutes, selectedIdx, previewRuns) {
        routeLayer.items.clear()
        val casingW = dp(11f)
        val lineW = dp(7f)
        fun addLine(points: List<LatLon>, color: Color, width: Float, onClick: (() -> Unit)?) {
            if (points.size < 2) return
            val line = Polyline().apply {
                outlinePaint.color = color.toArgbInt()
                outlinePaint.strokeWidth = width
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                setPoints(points.map { it.geo() })
                setOnClickListener { _, _, _ -> if (onClick != null) { onClick(); true } else false }
            }
            routeLayer.add(line)
        }
        fun addRoute(route: Route, runs: List<TrafficRun>) {
            addLine(route.geometry, ROUTE_CASING, casingW, null)
            if (runs.isEmpty()) addLine(route.geometry, TRAFFIC_UNKNOWN, lineW, null)
            else for (r in runs) addLine(r.points, levelColor(r.level), lineW, null)
        }
        val driving = nav?.route
        if (driving != null) {
            addRoute(driving, navRuns)
        } else {
            previewRoutes.forEachIndexed { i, r ->
                if (i != selectedIdx) {
                    addLine(r.geometry, ROUTE_CASING, casingW, null)
                    addLine(r.geometry, ROUTE_ALT, lineW) { selectRoute(i) }
                }
            }
            previewRoutes.getOrNull(selectedIdx)?.let { addRoute(it, previewRuns) }
        }
        mapView.invalidate()
    }

    // Fit the whole choice of routes on screen when a new set arrives.
    LaunchedEffect(previewRoutes) {
        if (previewRoutes.isEmpty()) return@LaunchedEffect
        val pts = previewRoutes.flatMap { it.geometry }.map { it.geo() }
        if (pts.size < 2) return@LaunchedEffect
        val box = BoundingBox.fromGeoPointsSafe(pts)
        val fit = { mapView.zoomToBoundingBox(box.increaseByScale(1.25f), true, dp(48f).toInt()) }
        if (mapView.isLayoutOccurred) fit() else mapView.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
    }

    LaunchedEffect(incidents) {
        incidentLayer.items.clear()
        val now = System.currentTimeMillis() / 1000
        for (inc in incidents) {
            if (inc.expiresAt in 1 until now) continue
            val m = Marker(mapView).apply {
                position = GeoPoint(inc.lat, inc.lon)
                icon = ContextCompat.getDrawable(context, incidentIcon(inc.type))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = incidentLabel(inc.type)
                snippet = buildString {
                    inc.description?.let { append(it).append(" · ") }
                    append(if (inc.confirmations == 1) "1 report" else "${inc.confirmations} reports")
                    if (inc.createdAt > 0) append(" · ").append(fmtDuration((now - inc.createdAt).toDouble())).append(" ago")
                }
            }
            incidentLayer.add(m)
        }
        mapView.invalidate()
    }

    // Car and destination markers.
    val pos: LatLon? = nav?.position ?: location?.let { LatLon(it.latitude, it.longitude) }
    val bearing: Float? = nav?.bearingDeg ?: location?.takeIf { it.hasBearing() }?.bearing
    val dest: LatLon? = when {
        nav != null -> LatLon(nav.destLat, nav.destLon)
        options != null -> LatLon(options.dest.lat, options.dest.lon)
        else -> null
    }
    LaunchedEffect(pos, bearing, dest) {
        markerLayer.items.clear()
        if (dest != null) {
            destMarker.position = dest.geo()
            markerLayer.add(destMarker)
        }
        if (pos != null) {
            positionMarker.position = pos.geo()
            // Flat markers turn with the map, so a negative rotation keeps the arrow on the heading.
            if (bearing != null) positionMarker.rotation = -bearing
            markerLayer.add(positionMarker)
        }
        mapView.invalidate()
    }

    // Camera: bearing-up follow while navigating, north-up otherwise.
    var centeredOnce by remember { mutableStateOf(false) }
    LaunchedEffect(nav != null) {
        if (nav == null) mapView.setMapOrientation(0f, true)
    }
    LaunchedEffect(pos, bearing, following, nav != null) {
        val p = pos ?: return@LaunchedEffect
        if (nav != null) {
            if (!following) return@LaunchedEffect
            val current = mapView.mapOrientation
            val target = if (bearing != null) ((360f - bearing) % 360f + 360f) % 360f else current
            val clockwise = ((target - current + 360f) % 360f) < 180f
            mapView.controller.animateTo(p.geo(), followZoom(nav.speedMps), 900L, target, clockwise)
        } else if (following && options == null) {
            if (!centeredOnce) {
                mapView.controller.setZoom(16.0)
                mapView.controller.setCenter(p.geo())
                centeredOnce = true
            } else {
                mapView.controller.animateTo(p.geo())
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            routeLayer.items.clear()
            incidentLayer.items.clear()
            markerLayer.items.clear()
            mapView.overlays.removeAll { it !is org.osmdroid.views.overlay.TilesOverlay }
            mapView.onDetach()
        }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

private fun Color.toArgbInt(): Int =
    android.graphics.Color.argb((alpha * 255).roundToInt(), (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())

// ── Search ─────────────────────────────────────────────────────────────

@Composable
private fun SearchBar(
    state: SearchState,
    regionName: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onShowRegions: () -> Unit,
) {
    val focus = LocalFocusManager.current
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search for a place or address") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSubmit() }),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.query.isNotEmpty()) {
                            TextButton(onClick = { focus.clearFocus(); onClear() }) { Text("Clear") }
                        }
                        TextButton(onClick = { focus.clearFocus(); onSubmit() }, enabled = state.query.isNotBlank()) { Text("Go") }
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Long-press the map to drop a pin",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onShowRegions) { Text("$regionName map") }
            }
        }
    }
}

@Composable
private fun SearchResults(state: SearchState, here: Location?, onPick: (Place) -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(state.results) { place ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(place) }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(place.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                place.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (here != null) {
                            val d = RoadGeo.haversineM(here.latitude, here.longitude, place.lat, place.lon)
                            Text(fmtDistance(d), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Navigation needs your precise location to plan routes and follow your drive.",
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onRequest) { Text("Allow") }
        }
    }
}

@Composable
private fun StatusCard(text: String, busy: Boolean, onCancel: (() -> Unit)?) {
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(text, Modifier.weight(1f))
            if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

// ── Route options ──────────────────────────────────────────────────────

private val PROFILE_CHIPS = listOf(
    RouteProfile.FASTEST, RouteProfile.NO_TOLLS, RouteProfile.SHORTEST, RouteProfile.ECO, RouteProfile.NO_HIGHWAYS,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteOptionsSheet(
    options: RouteOptions,
    fuelPricePerGal: Double,
    onProfile: (RouteProfile) -> Unit,
    onSelect: (Int) -> Unit,
    onGo: (Int) -> Unit,
    onClose: () -> Unit,
    onEditFuel: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(options.dest.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        options.dest.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            if (options.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            options.error?.let {
                Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error)
            }
            if (options.routes.isNotEmpty()) {
                val selectedProfile = options.selectedRoute?.profile
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (p in PROFILE_CHIPS) {
                        FilterChip(
                            selected = p == selectedProfile,
                            onClick = { onProfile(p) },
                            label = { Text(profileLabel(p)) },
                        )
                    }
                }
                Column(
                    Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.routes.forEachIndexed { i, r ->
                        RouteCard(
                            route = r,
                            selected = i == options.selected,
                            fuelPricePerGal = fuelPricePerGal,
                            onSelect = { onSelect(i) },
                            onGo = { onGo(i) },
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, end = SOS_CLEARANCE),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    String.format(Locale.US, "Fuel at $%.2f/gal", fuelPricePerGal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onEditFuel) { Text("Change") }
            }
        }
    }
}

@Composable
private fun RouteCard(
    route: Route,
    selected: Boolean,
    fuelPricePerGal: Double,
    onSelect: () -> Unit,
    onGo: () -> Unit,
) {
    val context = LocalContext.current
    val delayMin = ((route.durationSec - route.freeFlowDurationSec) / 60).roundToInt()
    val gallons = route.fuelLiters / LITERS_PER_GALLON
    val cost = gallons * fuelPricePerGal
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    Card(
        onClick = onSelect,
        border = border,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmtDuration(route.durationSec), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "arrive ${fmtClock(context, System.currentTimeMillis() + (route.durationSec * 1000).toLong())}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                Text(
                    buildString {
                        append(fmtDistance(route.distanceM))
                        append(" · ")
                        append(profileLabel(route.profile))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (delayMin >= 1) "+$delayMin min traffic" else "No traffic delay",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        delayMin >= 10 -> TRAFFIC_HEAVY
                        delayMin >= 3 -> TRAFFIC_SLOW
                        else -> TRAFFIC_FREE
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildString {
                        if (route.tollM >= 50) append("Tolls for ").append(fmtDistance(route.tollM)) else append("No tolls")
                        append(" · ")
                        append(String.format(Locale.US, "%.2f gal (%.1f L) · $%.2f fuel", gallons, route.fuelLiters, cost))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onGo,
                shape = CircleShape,
                modifier = Modifier.size(64.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TRAFFIC_FREE, contentColor = Color.White),
            ) { Text("GO", fontWeight = FontWeight.Black, fontSize = 18.sp) }
        }
    }
}

@Composable
private fun FuelPriceDialog(current: Double, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(String.format(Locale.US, "%.2f", current)) }
    val value = text.trim().removePrefix("$").toDoubleOrNull()
    val valid = value != null && value > 0 && value <= 50
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fuel price") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Dollars per gallon") },
                isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = { TextButton(onClick = { if (valid) onSave(value!!) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Driving ────────────────────────────────────────────────────────────

@Composable
private fun ManeuverBanner(nav: NavState.Navigating) {
    val m = nav.nextManeuver
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF14532D),
        contentColor = Color.White,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ManeuverIcon(m?.type ?: ManeuverType.STRAIGHT, Modifier.size(64.dp), Color.White)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (m != null) fmtDistance(nav.distanceToManeuverM) else fmtDistance(nav.remainingDistanceM),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        m?.instruction ?: "Continue to your destination",
                        fontSize = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                nav.rerouting -> BannerNote("Rerouting…")
                nav.offRoute -> BannerNote("Off route")
                nav.currentStreet != null -> BannerNote("On ${nav.currentStreet}")
            }
        }
    }
}

@Composable
private fun BannerNote(text: String) {
    Text(text, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun NavBottomBar(
    nav: NavState.Navigating,
    muted: Boolean,
    onMute: () -> Unit,
    onReport: () -> Unit,
    onEnd: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(fmtClock(context, nav.etaEpochMs), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TRAFFIC_FREE)
                    Text(
                        "${fmtDuration(nav.remainingSec)} · ${fmtDistance(nav.remainingDistanceM)}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                SpeedReadout(nav.speedMps, nav.speedLimitKph)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, end = SOS_CLEARANCE),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onEnd,
                    colors = ButtonDefaults.buttonColors(containerColor = DANGER, contentColor = Color.White),
                ) { Text("End") }
                OutlinedButton(onClick = onMute) { Text(if (muted) "Unmute" else "Mute") }
                FilledTonalButton(onClick = onReport) { Text("Report") }
            }
        }
    }
}

@Composable
private fun SpeedReadout(speedMps: Float?, limitKph: Int?) {
    val mph = speedMps?.let { (it * MPH_PER_MPS).roundToInt() }
    val limit = limitKph?.let { limitMph(it) }
    val speeding = mph != null && limit != null && mph > limit + SPEEDING_MARGIN_MPH
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(if (speeding) DANGER else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    mph?.toString() ?: "–",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (speeding) Color.White else MaterialTheme.colorScheme.onSurface,
                )
                Text("mph", fontSize = 10.sp, color = if (speeding) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (limit != null) {
            // US-style regulatory sign: black on white, rectangular.
            Column(
                Modifier
                    .width(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White)
                    .border(2.dp, Color.Black, RoundedCornerShape(6.dp))
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("SPEED\nLIMIT", fontSize = 7.sp, lineHeight = 8.sp, color = Color.Black, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                Text("$limit", fontSize = 20.sp, color = Color.Black, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun IncidentPrompt(ahead: IncidentAhead, onVote: (Boolean) -> Unit, onDismiss: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(incidentIcon(ahead.incident.type)), contentDescription = null, Modifier.size(40.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${incidentLabel(ahead.incident.type)} in ${fmtDistance(ahead.distanceM)}",
                    fontWeight = FontWeight.SemiBold,
                )
                Text("Still there?", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { onVote(true) }) { Text("Yes") }
            TextButton(onClick = { onVote(false) }) { Text("No") }
            TextButton(onClick = onDismiss) { Text("Skip") }
        }
    }
}

@Composable
private fun ReportDialog(onReport: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (row in Incident.TYPES.chunked(3)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (type in row) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { onReport(type) }
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Image(painterResource(incidentIcon(type)), contentDescription = null, Modifier.size(44.dp))
                                Spacer(Modifier.height(6.dp))
                                Text(incidentLabel(type), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text(
                    "Reports go to your relay with your current position and are shared with other drivers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Maneuver icons ─────────────────────────────────────────────────────

/** Unit vector for a heading [deg] clockwise from straight up, in screen coordinates. */
private fun dir(deg: Float): Offset {
    val r = deg * PI.toFloat() / 180f
    return Offset(sin(r), -cos(r))
}

/** Draws a stroked polyline through [pts] ending in a filled arrowhead at the last point. */
private fun DrawScope.arrow(pts: List<Offset>, color: Color, width: Float, head: Float) {
    if (pts.size < 2) return
    val tip = pts.last()
    val prev = pts[pts.size - 2]
    val len = hypot(tip.x - prev.x, tip.y - prev.y).coerceAtLeast(0.001f)
    val d = Offset((tip.x - prev.x) / len, (tip.y - prev.y) / len)
    val n = Offset(-d.y, d.x)
    val base = tip - d * head
    val shaft = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size - 1) lineTo(pts[i].x, pts[i].y)
        // The shaft stops inside the head so its round cap does not poke out of the tip.
        val end = tip - d * (head * 0.6f)
        lineTo(end.x, end.y)
    }
    drawPath(shaft, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val h = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + n.x * head * 0.75f, base.y + n.y * head * 0.75f)
        lineTo(base.x - n.x * head * 0.75f, base.y - n.y * head * 0.75f)
        close()
    }
    drawPath(h, color)
}

@Composable
private fun ManeuverIcon(type: ManeuverType, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.11f
        val head = w * 0.2f
        val faded = color.copy(alpha = 0.35f)
        val cx = w / 2
        val bottom = Offset(cx, h * 0.95f)
        val junction = Offset(cx, h * 0.52f)
        fun turn(deg: Float, length: Float = w * 0.38f) {
            arrow(listOf(bottom, junction, junction + dir(deg) * length), color, sw, head)
        }
        fun fork(ours: Float, other: Float) {
            val l = w * 0.4f
            drawLine(faded, junction, junction + dir(other) * l, strokeWidth = sw, cap = StrokeCap.Round)
            arrow(listOf(bottom, junction, junction + dir(ours) * l), color, sw, head)
        }
        when (type) {
            ManeuverType.STRAIGHT -> arrow(listOf(bottom, Offset(cx, h * 0.05f)), color, sw, head)
            ManeuverType.SLIGHT_LEFT -> turn(-40f)
            ManeuverType.LEFT -> turn(-90f)
            ManeuverType.SHARP_LEFT -> turn(-140f, w * 0.34f)
            ManeuverType.SLIGHT_RIGHT -> turn(40f)
            ManeuverType.RIGHT -> turn(90f)
            ManeuverType.SHARP_RIGHT -> turn(140f, w * 0.34f)
            ManeuverType.KEEP_LEFT -> fork(-28f, 28f)
            ManeuverType.KEEP_RIGHT -> fork(28f, -28f)
            ManeuverType.RAMP_LEFT -> {
                drawLine(faded, bottom, Offset(cx, h * 0.05f), strokeWidth = sw, cap = StrokeCap.Round)
                arrow(listOf(bottom, junction, junction + dir(-35f) * (w * 0.42f)), color, sw, head)
            }
            ManeuverType.RAMP_RIGHT -> {
                drawLine(faded, bottom, Offset(cx, h * 0.05f), strokeWidth = sw, cap = StrokeCap.Round)
                arrow(listOf(bottom, junction, junction + dir(35f) * (w * 0.42f)), color, sw, head)
            }
            ManeuverType.MERGE -> {
                val main = w * 0.64f
                drawLine(faded, Offset(main, h * 0.95f), Offset(main, h * 0.05f), strokeWidth = sw, cap = StrokeCap.Round)
                arrow(
                    listOf(Offset(w * 0.22f, h * 0.95f), Offset(w * 0.22f, h * 0.7f), Offset(main - sw, h * 0.38f), Offset(main - sw, h * 0.08f)),
                    color, sw, head,
                )
            }
            ManeuverType.UTURN -> {
                // Driving on the right, a U-turn swings left: up the right side, over, down the left.
                val right = w * 0.66f
                val left = w * 0.3f
                val r = (right - left) / 2
                val top = h * 0.32f
                val p = Path().apply {
                    moveTo(right, h * 0.95f)
                    lineTo(right, top)
                    arcTo(androidx.compose.ui.geometry.Rect(left, top - r, right, top + r), 0f, -180f, false)
                    lineTo(left, h * 0.5f)
                }
                drawPath(p, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                arrow(listOf(Offset(left, h * 0.5f), Offset(left, h * 0.8f)), color, sw, head)
            }
            ManeuverType.ROUNDABOUT -> {
                val r = w * 0.18f
                val c = Offset(cx, h * 0.5f)
                drawCircle(color, radius = r, center = c, style = Stroke(width = sw))
                drawLine(color, bottom, Offset(cx, c.y + r), strokeWidth = sw, cap = StrokeCap.Round)
                val exit = c + dir(45f) * r
                arrow(listOf(exit, exit + dir(45f) * (w * 0.3f)), color, sw, head)
            }
            ManeuverType.DEPART -> {
                drawCircle(color, radius = w * 0.09f, center = Offset(cx, h * 0.85f))
                arrow(listOf(Offset(cx, h * 0.72f), Offset(cx, h * 0.08f)), color, sw, head)
            }
            ManeuverType.ARRIVE -> {
                drawLine(color, bottom, Offset(cx, h * 0.55f), strokeWidth = sw, cap = StrokeCap.Round)
                drawCircle(color, radius = w * 0.2f, center = Offset(cx, h * 0.33f))
                drawCircle(Color(0xFF14532D), radius = w * 0.08f, center = Offset(cx, h * 0.33f))
            }
        }
    }
}

// ── Region setup ───────────────────────────────────────────────────────

@Composable
private fun RegionSetup(vm: NavViewModel, onClose: (() -> Unit)?) {
    val graphState by vm.graph.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<InstalledRegion?>(null) }

    val currentSlug = when (val g = graphState) {
        is GraphState.Ready -> g.slug
        is GraphState.Loading -> g.slug
        is GraphState.Failed -> g.slug
        GraphState.None -> null
    }
    val busy = download is GraphDownload.Downloading || download is GraphDownload.Installing
    val shown = vm.regions.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Offline road map", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (onClose != null) TextButton(onClick = onClose) { Text("Done") }
            }
            Text(
                "Routes are planned on your phone from a road map of one state. Download your state once; " +
                    "routing then works without a connection. Place search, live traffic and reports use the network. " +
                    "Large states are big downloads, so Wi-Fi is best.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (val g = graphState) {
            is GraphState.Loading -> item { StatusCard("Opening the ${g.name} map…", busy = true, onCancel = null) }
            is GraphState.Failed -> item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("The ${g.name} map could not be opened", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(g.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            "Delete it and download it again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            else -> Unit
        }

        item { DownloadStatus(download, vm.regions, onCancel = vm::cancelDownload) }

        if (installed.isNotEmpty()) {
            item { Text("On this phone", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            items(installed, key = { "installed-${it.slug}" }) { r ->
                Card {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                String.format(Locale.US, "%.0f MB · built %s", r.sizeBytes / 1_048_576.0,
                                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(r.builtAtEpochSec * 1000))),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (r.slug == currentSlug && graphState is GraphState.Ready) {
                            Text("In use", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        } else {
                            TextButton(onClick = { vm.selectRegion(r.slug); onClose?.invoke() }) { Text("Use") }
                        }
                        TextButton(onClick = { confirmDelete = r }, enabled = !busy) { Text("Delete") }
                    }
                }
            }
        }

        item {
            Text("Download a state", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true,
                placeholder = { Text("Filter states") },
            )
        }
        items(shown, key = { "region-${it.slug}" }) { region ->
            val have = installed.any { it.slug == region.slug }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy) { vm.downloadRegion(region) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(region.name, Modifier.weight(1f))
                Text(
                    if (have) "Update" else "Download",
                    color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        item { Spacer(Modifier.height(SOS_CLEARANCE + 16.dp)) }
    }

    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${r.name}?") },
            text = {
                Text(
                    String.format(Locale.US, "Frees %.0f MB. You can download it again later.", r.sizeBytes / 1_048_576.0),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteRegion(r.slug); confirmDelete = null }) { Text("Delete", color = DANGER) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DownloadStatus(download: GraphDownload, regions: List<MapRegion>, onCancel: () -> Unit) {
    fun name(slug: String) = regions.firstOrNull { it.slug == slug }?.name ?: slug
    when (download) {
        is GraphDownload.Downloading -> Card {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloading ${name(download.slug)}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                val f = download.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    buildString {
                        append(String.format(Locale.US, "%.1f MB", download.bytes / 1_048_576.0))
                        download.totalBytes?.let { append(String.format(Locale.US, " of %.1f MB", it / 1_048_576.0)) }
                    },
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        is GraphDownload.Installing -> StatusCard("Installing ${name(download.slug)}…", busy = true, onCancel = null)
        is GraphDownload.Failed -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                "${name(download.slug)}: ${download.message}",
                Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        is GraphDownload.Done, GraphDownload.Idle -> Unit
    }
}
