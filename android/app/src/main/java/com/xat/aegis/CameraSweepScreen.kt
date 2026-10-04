package com.xat.aegis

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xat.aegis.analysis.CameraFindMethod
import com.xat.aegis.analysis.CameraFinding
import com.xat.aegis.analysis.GlintBlob
import com.xat.aegis.analysis.HiddenCameraScanner
import com.xat.aegis.analysis.LensGlintAnalyzer
import com.xat.aegis.analysis.OpticalMode
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ── Colour tokens local to this file ──────────────────────────────────────────

private val CGround   = Color(0xFF0E1116)
private val CPanel    = Color(0xFF161B23)
private val CPanelHi  = Color(0xFF1D2430)
private val CInk      = Color(0xFFE6EAF1)
private val CInkDim   = Color(0xFFA8B2C1)
private val CMuted    = Color(0xFF6F7A8B)
private val CRule     = Color(0xFF262E3A)
private val CAccent   = Color(0xFFFF7A3D)
private val CCritical = Color(0xFFF2545B)
private val CCaution  = Color(0xFFE8B33D)
private val CClear    = Color(0xFF3DB88A)
private val CBlue     = Color(0xFF4A8FD4)
private val CShape    = RoundedCornerShape(4.dp)

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

private enum class SweepTab(val label: String) { OPTICAL("OPTICAL"), NETWORK("NETWORK"), WIRELESS("WIRELESS"), GUIDED("GUIDED") }

/**
 * The hidden-camera sweep: four tabs for the four ways a phone can look —
 * optically (lens glint, IR), on the Wi-Fi (RTSP/ONVIF/mDNS), over the air
 * (setup hotspots, BLE beacons) and by hand (the guided checklist with the
 * magnetometer gauge). Every method writes to [HiddenCameraScanner.findings];
 * each tab shows the rows its own methods produced.
 */
@Composable
fun CameraSweepScreen(onBack: (() -> Unit)? = null) {
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tab = SweepTab.entries[tabIndex.coerceIn(0, SweepTab.entries.size - 1)]
    val findings by HiddenCameraScanner.findings.collectAsStateWithLifecycle()
    val strong = findings.count { it.confidence >= 0.7f }

    Column(Modifier.fillMaxSize().background(CGround)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                Text(
                    "‹", color = CInkDim, fontSize = 28.sp,
                    modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 8.dp)
                )
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("HIDDEN CAMERAS", color = CInk, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Lens glint, infrared, the Wi-Fi, the air and your hands", color = CMuted, fontSize = 12.sp)
            }
            if (findings.isNotEmpty()) {
                Badge(
                    text = "${findings.size}" + if (strong > 0) " · $strong strong" else "",
                    color = if (strong > 0) CCritical else CCaution
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SweepTab.entries.forEachIndexed { i, t ->
                val selected = t == tab
                val count = findings.count { it.method in methodsFor(t) }
                Column(
                    Modifier.weight(1f).clickable { tabIndex = i }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        t.label + if (count > 0) " ($count)" else "",
                        color = if (selected) CInk else CMuted, fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, letterSpacing = 0.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) CAccent else CRule))
                }
            }
        }

        when (tab) {
            SweepTab.OPTICAL -> OpticalTab(findings)
            SweepTab.NETWORK -> NetworkTab(findings)
            SweepTab.WIRELESS -> WirelessTab(findings)
            SweepTab.GUIDED -> GuidedTab(findings)
        }
    }
}

private fun methodsFor(tab: SweepTab): Set<CameraFindMethod> = when (tab) {
    SweepTab.OPTICAL -> setOf(CameraFindMethod.LENS_GLINT, CameraFindMethod.IR_ILLUMINATOR)
    SweepTab.NETWORK -> setOf(CameraFindMethod.NETWORK)
    SweepTab.WIRELESS -> setOf(CameraFindMethod.WIFI_SETUP_AP, CameraFindMethod.BLE_SETUP_AP)
    SweepTab.GUIDED -> setOf(CameraFindMethod.MAGNETIC)
}

// ── OPTICAL ──────────────────────────────────────────────────────────────────

@Composable
private fun OpticalTab(findings: List<CameraFinding>) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var modeIndex by rememberSaveable { mutableStateOf(0) }
    val mode = OpticalMode.entries[modeIndex.coerceIn(0, OpticalMode.entries.size - 1)]
    var live by rememberSaveable { mutableStateOf(true) }
    val state by HiddenCameraScanner.optical.collectAsStateWithLifecycle()
    val mine = findings.filter { it.method == CameraFindMethod.LENS_GLINT || it.method == CameraFindMethod.IR_ILLUMINATOR }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpticalMode.entries.forEachIndexed { i, m ->
                Chip(
                    text = m.label.uppercase(Locale.US), selected = m == mode,
                    modifier = Modifier.weight(1f), onClick = { modeIndex = i }
                )
            }
            Chip(text = if (live) "PAUSE" else "RESUME", selected = false, onClick = { live = !live })
        }

        if (!granted) {
            PanelBox {
                Text("Camera permission", color = CInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "The optical sweep looks through the phone's camera for lenses looking back. Frames are analysed in memory and never saved or sent.",
                    color = CInkDim, fontSize = 12.sp, lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                ActionButton("ALLOW CAMERA") { ask.launch(Manifest.permission.CAMERA) }
            }
        } else {
            Box(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(CShape).background(Color.Black)
            ) {
                if (live) {
                    CameraViewfinder(mode)
                    GlintOverlay(state.tracks)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Paused", color = CMuted, fontSize = 13.sp)
                    }
                }
                // Status strip
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xAA0E1116)).padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val status = when {
                        state.error != null -> state.error!!
                        !state.running -> "Opening camera…"
                        state.tooBright -> "Too bright for IR — darken the room"
                        state.washedOut -> "Washed out — back away from the surface"
                        state.tracks.any { it.candidate } -> "STEADY REFLECTION — look for a pinhole"
                        state.tracks.isNotEmpty() -> "${state.tracks.size} bright spot(s), none steady yet"
                        else -> if (mode == OpticalMode.GLINT) "Pan slowly, torch on" else "Point at suspect objects"
                    }
                    val col = when {
                        state.error != null -> CCritical
                        state.tracks.any { it.candidate } -> CCritical
                        state.tooBright || state.washedOut -> CCaution
                        else -> CInkDim
                    }
                    Text(status, color = col, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Y ${state.meanLuma.roundToInt()} · Δ${(state.lumaChange * 100).roundToInt()}% · ${state.fps.roundToInt()} fps",
                        color = CMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        PanelBox {
            if (mode == OpticalMode.GLINT) {
                Text("How glint works", color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "The torch is on. A lens — even a pinhole — sends light straight back along its own axis, so it shows as a tiny round spot that stays put while you move. Screw heads, glass edges and chrome flash too, but their highlights slide. Keep the phone moving slowly at arm's length; a circle turns red once a spot has held still for ${LensGlintAnalyzer.MIN_PERSISTENCE} frames with the scene steady.",
                    color = CInkDim, fontSize = 12.sp, lineHeight = 16.sp
                )
            } else {
                Text("How the IR view works", color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Torch off, front camera. Night-vision cameras light the room with 850 nm infrared LEDs; the eye sees nothing, but the front camera's IR filter is weak and shows them as a white or violet dot. Make the room as dark as you can — the view greys out if there is too much visible light — and point the screen side at smoke detectors, clocks, chargers and vents.",
                    color = CInkDim, fontSize = 12.sp, lineHeight = 16.sp
                )
            }
        }

        FindingsSection(mine, methodsFor(SweepTab.OPTICAL), "No steady reflections recorded yet")
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Binds CameraX to the lifecycle for [mode]: back camera with torch for glint, front
 * camera dark for IR. Preview and analysis both ask for 4:3 so the overlay's
 * normalised coordinates land on the right pixels, and the view is FIT_CENTER
 * inside a 3:4 box so nothing is cropped away.
 */
@Composable
private fun CameraViewfinder(mode: OpticalMode) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    DisposableEffect(mode) {
        HiddenCameraScanner.opticalStarting(mode)
        var provider: ProcessCameraProvider? = null
        var cancelled = false
        val analyzer = LensGlintAnalyzer(
            mode = mode, mirrored = mode.frontCamera,
            onState = { HiddenCameraScanner.publishOptical(it) },
            onCandidate = { HiddenCameraScanner.opticalCandidate(ctx, mode, it) }
        )
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            if (cancelled) return@addListener
            try {
                val p = future.get()
                provider = p
                val selector = if (mode.frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                val preview = Preview.Builder().setResolutionSelector(resolution).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor, analyzer)
                p.unbindAll()
                val camera = p.bindToLifecycle(owner, selector, preview, analysis)
                if (mode.torch && camera.cameraInfo.hasFlashUnit()) camera.cameraControl.enableTorch(true)
            } catch (e: Exception) {
                HiddenCameraScanner.opticalError(mode, e.message)
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            cancelled = true
            analyzer.close()
            runCatching { provider?.unbindAll() }
            HiddenCameraScanner.opticalStopped(mode)
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun GlintOverlay(tracks: List<GlintBlob>) {
    Canvas(Modifier.fillMaxSize()) {
        for (b in tracks) {
            val c = Offset(b.x * size.width, b.y * size.height)
            val r = max(14.dp.toPx(), b.radius * min(size.width, size.height) * 3f)
            val col = when {
                b.candidate -> CCritical
                b.persistence >= LensGlintAnalyzer.MIN_PERSISTENCE / 2 -> CCaution
                else -> CInkDim.copy(alpha = 0.45f)
            }
            drawCircle(col, r, c, style = Stroke(width = if (b.candidate) 3.dp.toPx() else 1.5.dp.toPx()))
            if (b.candidate) {
                drawCircle(col.copy(alpha = 0.35f), r * 1.7f, c, style = Stroke(width = 1.dp.toPx()))
                drawLine(col, Offset(c.x - r * 1.9f, c.y), Offset(c.x - r * 1.2f, c.y), 1.5.dp.toPx())
                drawLine(col, Offset(c.x + r * 1.2f, c.y), Offset(c.x + r * 1.9f, c.y), 1.5.dp.toPx())
                drawLine(col, Offset(c.x, c.y - r * 1.9f), Offset(c.x, c.y - r * 1.2f), 1.5.dp.toPx())
                drawLine(col, Offset(c.x, c.y + r * 1.2f), Offset(c.x, c.y + r * 1.9f), 1.5.dp.toPx())
            }
        }
    }
}

// ── NETWORK ──────────────────────────────────────────────────────────────────

@Composable
private fun NetworkTab(findings: List<CameraFinding>) {
    val ctx = LocalContext.current
    val progress by HiddenCameraScanner.network.collectAsStateWithLifecycle()
    val wifi by Registry.wifiStatus.collectAsStateWithLifecycle()
    val now = rememberNow(5_000L)
    val mine = findings.filter { it.method == CameraFindMethod.NETWORK }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        PanelBox {
            SectionLabel("THIS NETWORK")
            val conn = wifi.connection
            Text(
                conn?.ssid ?: progress.ssid ?: "Not connected to Wi-Fi",
                color = if (conn != null || progress.ssid != null) CInk else CInkDim, fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
            val sub = progress.subnet ?: conn?.ipv4?.let { "phone at $it" }
            if (sub != null) Text(sub, color = CMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            if (progress.running) {
                val frac = if (progress.hostsTotal > 0) progress.hostsDone.toFloat() / progress.hostsTotal else 0f
                LinearProgressIndicator(progress = { frac }, color = CAccent, trackColor = CRule, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(progress.phase, color = CInkDim, fontSize = 12.sp)
                    Text("${progress.hostsDone}/${progress.hostsTotal} · ${progress.liveHosts} live", color = CMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                ActionButton("CANCEL", tone = CInkDim) { HiddenCameraScanner.cancelNetworkScan() }
            } else {
                progress.error?.let { Text("⚠ $it", color = CCaution, fontSize = 12.sp, lineHeight = 16.sp) }
                if (progress.lastScanTs > 0 && progress.error == null) {
                    Text(
                        "Last sweep ${fmtAgo(now, progress.lastScanTs)} · ${progress.liveHosts} live hosts · ${progress.found} camera-like",
                        color = CMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(6.dp))
                ActionButton("SWEEP THE WI-FI FOR CAMERAS") { HiddenCameraScanner.startNetworkScan(ctx) }
            }
        }

        PanelBox {
            Text("What it looks for", color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Every address on the Wi-Fi's /24 is tried on the camera ports — RTSP (554, 8554), the Dahua and XMeye recorder ports (37777, 34567), the web ports — and whatever answers is asked for its banner. At the same time an ONVIF discovery probe goes out, which every ONVIF camera must answer, and mDNS is browsed for RTSP, Axis, PSIA, ONVIF and HomeKit camera services. A host with just a web page and no camera fingerprint is left out: that is every printer and router. Join the venue's Wi-Fi first — a guest network that isolates clients will hide everything, which is itself worth knowing.",
                color = CInkDim, fontSize = 12.sp, lineHeight = 16.sp
            )
        }

        FindingsSection(mine, methodsFor(SweepTab.NETWORK), "No camera-like hosts found on the last sweep")
        Spacer(Modifier.height(24.dp))
    }
}

// ── WIRELESS ─────────────────────────────────────────────────────────────────

@Composable
private fun WirelessTab(findings: List<CameraFinding>) {
    val ctx = LocalContext.current
    val state by HiddenCameraScanner.wireless.collectAsStateWithLifecycle()
    val now = rememberNow(1_000L)
    val mine = findings.filter { it.method == CameraFindMethod.WIFI_SETUP_AP || it.method == CameraFindMethod.BLE_SETUP_AP }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        PanelBox {
            SectionLabel("WI-FI SETUP HOTSPOTS")
            Text(
                "A camera nobody has finished installing broadcasts its own hotspot — usually open, named for its maker with a few hex digits. The venue's own networks never look like that.",
                color = CInkDim, fontSize = 12.sp, lineHeight = 16.sp
            )
            Spacer(Modifier.height(8.dp))
            state.wifiError?.let { Text("⚠ $it", color = CCaution, fontSize = 12.sp) }
            if (state.wifiScannedTs > 0) {
                Text(
                    "Scanned ${fmtAgo(now, state.wifiScannedTs)} · ${state.wifiApsSeen} networks in range",
                    color = CMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(6.dp))
            }
            ActionButton(if (state.wifiScanning) "SCANNING…" else "SCAN FOR SETUP HOTSPOTS", enabled = !state.wifiScanning) {
                HiddenCameraScanner.startWifiSetupScan(ctx)
            }
        }

        PanelBox {
            SectionLabel("BLUETOOTH SETUP BEACONS")
            Text(
                "Before it has Wi-Fi credentials a camera advertises over Bluetooth so an app can hand them over. Twenty seconds of listening catches everything in the room that is still waiting.",
                color = CInkDim, fontSize = 12.sp, lineHeight = 16.sp
            )
            Spacer(Modifier.height(8.dp))
            state.bleError?.let { Text("⚠ $it", color = CCaution, fontSize = 12.sp) }
            if (state.bleListening) {
                val left = ((state.bleUntilTs - now) / 1000L).coerceAtLeast(0L)
                Text(
                    "Listening · ${left}s left · ${state.bleDevicesSeen} devices heard",
                    color = CAccent, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(6.dp))
                ActionButton("STOP", tone = CInkDim) { HiddenCameraScanner.stopBleListening() }
            } else {
                if (state.bleDevicesSeen > 0) {
                    Text("Last window heard ${state.bleDevicesSeen} devices", color = CMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                }
                ActionButton("LISTEN FOR 20 SECONDS") { HiddenCameraScanner.startBleListening(ctx, 20_000L) }
            }
        }

        FindingsSection(mine, methodsFor(SweepTab.WIRELESS), "Nothing in range is announcing itself as a camera")
        Spacer(Modifier.height(24.dp))
    }
}

// ── GUIDED ───────────────────────────────────────────────────────────────────

@Composable
private fun GuidedTab(findings: List<CameraFinding>) {
    val ctx = LocalContext.current
    val done by HiddenCameraScanner.sweepDone.collectAsStateWithLifecycle()
    val mag by HiddenCameraScanner.magnetic.collectAsStateWithLifecycle()
    val mine = findings.filter { it.method == CameraFindMethod.MAGNETIC }
    val steps = HiddenCameraScanner.SWEEP_STEPS

    // The gauge stops with the tab: a magnetometer at 50 Hz is not free.
    DisposableEffect(Unit) { onDispose { HiddenCameraScanner.stopMagnetic() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // Magnetometer gauge
        PanelBox {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("MAGNETIC GAUGE", Modifier.weight(1f))
                Chip(
                    text = if (mag.active) "STOP" else "START",
                    selected = mag.active,
                    onClick = { if (mag.active) HiddenCameraScanner.stopMagnetic() else HiddenCameraScanner.startMagnetic(ctx) }
                )
            }
            Spacer(Modifier.height(8.dp))
            mag.error?.let { Text("⚠ $it", color = CCaution, fontSize = 12.sp) }
            if (mag.active) {
                MagneticBar(mag.fieldUt, mag.baselineUt, mag.thresholdUt, mag.triggered)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (mag.warmingUp) "Settling…" else if (mag.triggered) "ANOMALY — look here" else "Quiet",
                        color = if (mag.triggered) CCritical else if (mag.warmingUp) CMuted else CClear,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${mag.fieldUt.roundToInt()} µT · base ${mag.baselineUt.roundToInt()} · Δ${mag.deltaUt.roundToInt()} / ${mag.thresholdUt.roundToInt()}",
                        color = CMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                }
                if (mag.unreliable) {
                    Text("Sensor reports itself unreliable — wave the phone in a figure of eight, away from metal.", color = CCaution, fontSize = 11.sp, lineHeight = 15.sp)
                }
            } else {
                Text(
                    "Start the gauge, then slide the back of the phone slowly along the surfaces listed below. The baseline learns the room's field; a magnet inside something jumps the needle well past the trigger line.",
                    color = CInkDim, fontSize = 12.sp, lineHeight = 16.sp
                )
            }
        }

        // Checklist
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("ROOM SWEEP · ${done.count { id -> steps.any { it.id == id } }}/${steps.size}", Modifier.weight(1f))
            if (done.isNotEmpty()) {
                Text("RESET", color = CMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    modifier = Modifier.clickable { HiddenCameraScanner.resetSweep() }.padding(4.dp))
            }
        }
        steps.forEachIndexed { i, step ->
            val checked = step.id in done
            Row(
                Modifier.fillMaxWidth().background(if (checked) CPanel else CPanelHi, CShape)
                    .clickable { HiddenCameraScanner.toggleSweepStep(step.id) }
                    .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Checkbox(
                    checked = checked, onCheckedChange = { HiddenCameraScanner.toggleSweepStep(step.id) },
                    colors = CheckboxDefaults.colors(checkedColor = CClear, uncheckedColor = CMuted, checkmarkColor = CGround)
                )
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", color = CMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            step.title, color = if (checked) CInkDim else CInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Badge(step.tool, CBlue)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(step.detail, color = if (checked) CMuted else CInkDim, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }

        // The honest limit
        Column(Modifier.fillMaxWidth().border(1.dp, CCaution.copy(alpha = 0.6f), CShape).padding(14.dp)) {
            Text("WHAT THE PHONE CANNOT SEE", color = CCaution, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Text(HiddenCameraScanner.ANALOG_RF_NOTE, color = CInkDim, fontSize = 12.sp, lineHeight = 16.sp)
        }

        FindingsSection(mine, methodsFor(SweepTab.GUIDED), "No magnetic anomalies recorded")
        Spacer(Modifier.height(24.dp))
    }
}

/** Field against baseline: the baseline sits at the centre, the trigger lines either side, the needle where the field is. */
@Composable
private fun MagneticBar(field: Float, baseline: Float, threshold: Float, triggered: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val span = max(threshold * 2.5f, 60f)   // µT shown either side of the baseline
        val cx = size.width / 2f
        fun xOf(ut: Float): Float = (cx + (ut - baseline) / span * cx).coerceIn(0f, size.width)
        drawRoundRect(CRule, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        if (threshold > 0f) {
            val l = xOf(baseline - threshold)
            val r = xOf(baseline + threshold)
            drawRect(CClear.copy(alpha = 0.18f), topLeft = Offset(l, 0f), size = androidx.compose.ui.geometry.Size(r - l, size.height))
            drawLine(CCaution, Offset(l, 0f), Offset(l, size.height), 1.dp.toPx())
            drawLine(CCaution, Offset(r, 0f), Offset(r, size.height), 1.dp.toPx())
        }
        drawLine(CInkDim, Offset(cx, 0f), Offset(cx, size.height), 1.dp.toPx())
        val nx = xOf(field)
        drawLine(if (triggered) CCritical else CInk, Offset(nx, 2.dp.toPx()), Offset(nx, size.height - 2.dp.toPx()), 3.dp.toPx())
    }
}

// ── Shared pieces ────────────────────────────────────────────────────────────

@Composable
private fun FindingsSection(rows: List<CameraFinding>, methods: Set<CameraFindMethod>, emptyText: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val strong = rows.count { it.confidence >= 0.7f }
        SectionLabel(
            "FINDINGS (${rows.size})" + if (strong > 0) " · $strong strong" else "",
            Modifier.weight(1f), color = if (strong > 0) CCritical else CInkDim
        )
        if (rows.isNotEmpty()) {
            Text(
                "CLEAR", color = CMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.clickable { methods.forEach { HiddenCameraScanner.clear(it) } }.padding(4.dp)
            )
        }
    }
    if (rows.isEmpty()) {
        Text(emptyText, color = CMuted, fontSize = 12.sp, lineHeight = 16.sp)
    } else {
        rows.forEach { FindingRow(it) }
    }
}

@Composable
private fun FindingRow(f: CameraFinding) {
    val tone = when {
        f.confidence >= 0.8f -> CCritical
        f.confidence >= 0.5f -> CCaution
        else -> CInkDim
    }
    Column(Modifier.fillMaxWidth().background(CPanel, CShape).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(tone, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(8.dp))
            Text(
                HiddenCameraScanner.methodLabel(f.method).uppercase(Locale.US), color = tone, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f)
            )
            Text("${(f.confidence * 100).roundToInt()} %", color = tone, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(timeFmt.format(Date(f.ts)), color = CMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(6.dp))
        Text(f.location, color = CInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
        Spacer(Modifier.height(4.dp))
        Text(f.detail, color = CInkDim, fontSize = 12.sp, lineHeight = 16.sp)
        if (f.lat != null && f.lon != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                String.format(Locale.US, "%.5f, %.5f", f.lat, f.lon),
                color = CMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun PanelBox(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(CPanel, CShape).padding(14.dp)) { content() }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = CInkDim) {
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = modifier)
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.7f), CShape).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(if (selected) CAccent.copy(alpha = 0.18f) else CPanelHi, CShape)
            .border(1.dp, if (selected) CAccent else CRule, CShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) CAccent else CInkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
    }
}

@Composable
private fun ActionButton(text: String, enabled: Boolean = true, tone: Color = CAccent, onClick: () -> Unit) {
    val col = if (enabled) tone else CMuted
    Box(
        Modifier.fillMaxWidth()
            .border(1.dp, col, CShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = col, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

/** Wall-clock time that ticks every [periodMs] so "n s ago" labels and countdowns move. */
@Composable
private fun rememberNow(periodMs: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return now
}

private fun fmtAgo(now: Long, ts: Long): String {
    val s = ((now - ts) / 1000L).coerceAtLeast(0L)
    return when {
        s < 5 -> "just now"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        else -> "${s / 3600}h ago"
    }
}
