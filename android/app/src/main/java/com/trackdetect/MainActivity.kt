package com.trackdetect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackdetect.analysis.NfcScanner
import com.trackdetect.analysis.Report
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min

// ── Colour tokens ─────────────────────────────────────────────────────────────

private val Ground   = Color(0xFF0E1116)
private val Panel    = Color(0xFF161B23)
private val Ink      = Color(0xFFE6EAF1)
private val InkDim   = Color(0xFFA8B2C1)
private val Muted    = Color(0xFF6F7A8B)
private val Rule     = Color(0xFF262E3A)
private val Accent   = Color(0xFFFF7A3D)
private val Critical = Color(0xFFF2545B)
private val Caution  = Color(0xFFE8B33D)
private val Clear    = Color(0xFF3DB88A)
private val Blue     = Color(0xFF4A8FD4)

private val REQUIRED = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.POST_NOTIFICATIONS
)

// ── Activity ──────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ground, surface = Panel)) {
                Surface(color = Ground, modifier = Modifier.fillMaxSize()) {
                    MainApp(
                        onStart = { startService(Intent(this, ScanService::class.java)) },
                        onStop = {
                            startService(Intent(this, ScanService::class.java)
                                .apply { action = ScanService.ACTION_STOP })
                        },
                        hasPermissions = { REQUIRED.all { granted(it) } }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(this, { tag ->
            try { Registry.addNfc(NfcScanner.parse(tag)) } catch (_: Exception) {}
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}

// ── Root composable ───────────────────────────────────────────────────────────

@Composable
private fun MainApp(onStart: () -> Unit, onStop: () -> Unit, hasPermissions: () -> Boolean) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("SCAN", "MAP", "LOG", "CELL", "NFC")

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ScanScreen(onStart, onStop, hasPermissions)
                1 -> MapScreen()
                2 -> TimelineScreen()
                3 -> CellScreen()
                4 -> NfcScreen()
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            tabs.forEachIndexed { i, label ->
                val active = tab == i
                TextButton(
                    onClick = { tab = i },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        label,
                        color = if (active) Accent else Muted,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

// ── Scan (BLE) screen ─────────────────────────────────────────────────────────

@Composable
private fun ScanScreen(onStart: () -> Unit, onStop: () -> Unit, hasPermissions: () -> Boolean) {
    val detections by Registry.detections.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val timeline by Registry.timeline.collectAsStateWithLifecycle()
    val cell by Registry.cell.collectAsStateWithLifecycle()
    val nfc by Registry.nfc.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onStart() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("TRACK DETECT", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            TextButton(onClick = {
                val i = Report.share(context, status, detections, timeline, cell, nfc)
                context.startActivity(Intent.createChooser(i, "Share evidence report"))
            }) { Text("EXPORT", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp) }
        }

        Spacer(Modifier.height(12.dp))
        ScanStatusPanel(status, detections)

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                if (status.scanning) onStop()
                else if (hasPermissions()) onStart()
                else launcher.launch(REQUIRED)
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (status.scanning) Rule else Accent,
                contentColor = if (status.scanning) Ink else Color(0xFF12161D)
            ),
            shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (status.scanning) "STOP SCANNING" else "START SCANNING",
                fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        }

        Spacer(Modifier.height(14.dp))
        if (detections.isEmpty()) {
            EmptyState(status.scanning)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(detections, key = { it.key }) { DetectionRow(it) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ScanStatusPanel(status: ScanStatus, detections: List<Detection>) {
    val following = detections.count { it.following }
    val (verdict, verdictColor) = when {
        following > 0 -> "$following CONFIRMED FOLLOWING" to Critical
        detections.any { it.persistent } -> "Persistent devices, unconfirmed" to Caution
        else -> "Nothing confirmed" to Clear
    }
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(4.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(verdict, color = verdictColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(if (status.scanning) "Scanning · ${detections.size} device(s)" else "Idle",
            color = InkDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(if (status.hasFix)
            "Fix %.4f, %.4f · ${if (status.moving) "moving" else "stationary"} · %.1f km".format(
                status.lat ?: 0.0, status.lon ?: 0.0, status.travelledM / 1000.0)
            else "No fix — following cannot be confirmed without GPS",
            color = if (status.hasFix) Muted else Caution, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        status.error?.let { Text(it, color = Critical, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
private fun EmptyState(scanning: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (scanning) "Nothing flagged yet." else "Not scanning.", color = InkDim, fontSize = 14.sp)
        if (scanning) Text("Proving something follows you takes 10 min presence and a 300 m drive. Leave it running.",
            color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun DetectionRow(d: Detection) {
    val stripe = when {
        d.following -> Critical; d.persistent -> Caution; d.identified -> Accent; else -> Rule
    }
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(4.dp))) {
        Box(Modifier.width(3.dp).height(if (d.following) 120.dp else 96.dp).background(stripe))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(d.threat.name, color = stripe, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            }
            d.tracker?.let { Text("${it.label} · ${it.brand}", color = Accent, fontSize = 12.sp) }
            Text(buildString {
                append("${d.rssi} dBm")
                d.approxMetres?.let { append(" · ~%.0f m".format(it)) }
                append(" · ${d.sightings}×")
                if (d.places > 0) append(" · ${d.places} place(s)")
                if (d.rotations > 0) append(" · ${d.rotations} MAC change(s)")
            }, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            val (line, lc) = when (d.confidence) {
                FollowConfidence.CONFIRMED -> "FOLLOWING — ${d.displacementM.toInt()} m apart. On you or your vehicle." to Critical
                FollowConfidence.NO_POSITION -> "Persistent — no GPS fix, cannot confirm following." to Caution
                FollowConfidence.NOT_MOVED_ENOUGH -> "Persistent — only ${d.displacementM.toInt()} m so far." to Caution
                FollowConfidence.NONE -> (if (d.identified) "Known tracker type, present too briefly." else "Watching for pattern.") to Muted
            }
            Text(line, color = lc, fontSize = 12.sp)
            if (d.following) Text("Check: wheel wells, bumper covers, OBD-II port, under seats, bag linings.",
                color = InkDim, fontSize = 11.sp)
        }
    }
}

// ── Map screen ────────────────────────────────────────────────────────────────

private data class MapProj(val cLat: Double, val cLon: Double, val pxPerM: Float)

private fun LatLon.toOffset(p: MapProj, size: Size): Offset {
    val mPerDegLat = 111320.0
    val mPerDegLon = 111320.0 * cos(Math.toRadians(p.cLat))
    val dx = ((lon - p.cLon) * mPerDegLon * p.pxPerM).toFloat()
    val dy = ((p.cLat - lat) * mPerDegLat * p.pxPerM).toFloat()
    return Offset(size.width / 2 + dx, size.height / 2 + dy)
}

private fun buildProjection(mapData: MapData, status: ScanStatus, size: Size): MapProj? {
    val pts = buildList {
        addAll(mapData.track)
        mapData.devices.forEach { addAll(it.points) }
        mapData.cells.forEach { add(it.point) }
        if (status.hasFix && status.lat != null && status.lon != null) add(LatLon(status.lat, status.lon))
    }
    if (pts.isEmpty()) return null
    val cLat = pts.sumOf { it.lat } / pts.size
    val cLon = pts.sumOf { it.lon } / pts.size
    if (pts.size == 1) return MapProj(cLat, cLon, min(size.width, size.height) / 400f)
    val mPerDegLat = 111320.0
    val mPerDegLon = 111320.0 * cos(Math.toRadians(cLat))
    val wM = (pts.maxOf { it.lon } - pts.minOf { it.lon }) * mPerDegLon
    val hM = (pts.maxOf { it.lat } - pts.minOf { it.lat }) * mPerDegLat
    if (wM < 1 && hM < 1) return MapProj(cLat, cLon, min(size.width, size.height) / 400f)
    val sx = if (wM > 0) (size.width * 0.75f / wM).toFloat() else Float.MAX_VALUE
    val sy = if (hM > 0) (size.height * 0.75f / hM).toFloat() else Float.MAX_VALUE
    return MapProj(cLat, cLon, min(sx, sy))
}

private fun threatColor(t: Threat, following: Boolean) = when {
    following || t == Threat.CRITICAL -> Critical
    t == Threat.HIGH -> Color(0xFFE8583D)
    t == Threat.MEDIUM -> Caution
    else -> Blue
}

@Composable
private fun MapScreen() {
    val mapData by Registry.map.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0E13))) {
        if (!status.hasFix && mapData.track.isEmpty() && mapData.devices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No GPS fix.\nStart scanning and move around\nto build the map.",
                    color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val proj = buildProjection(mapData, status, size) ?: return@Canvas

                // GPS track
                if (mapData.track.size > 1) {
                    val path = Path()
                    mapData.track.forEachIndexed { i, p ->
                        val o = p.toOffset(proj, size)
                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    drawPath(path, Blue.copy(alpha = 0.7f), style = Stroke(3f, cap = StrokeCap.Round))
                }

                // Device trails
                for (trail in mapData.devices) {
                    val col = threatColor(trail.threat, trail.following)
                    if (trail.points.size > 1) {
                        val path = Path()
                        trail.points.forEachIndexed { i, p ->
                            val o = p.toOffset(proj, size)
                            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                        }
                        drawPath(path, col.copy(alpha = 0.5f), style = Stroke(2f))
                    }
                    trail.points.forEach { p ->
                        drawCircle(col, radius = 5f, center = p.toOffset(proj, size))
                    }
                }

                // Cell markers (triangles)
                for (marker in mapData.cells) {
                    val o = marker.point.toOffset(proj, size)
                    val col = threatColor(marker.level, false)
                    val tri = Path().apply {
                        moveTo(o.x, o.y - 14f); lineTo(o.x + 11f, o.y + 9f)
                        lineTo(o.x - 11f, o.y + 9f); close()
                    }
                    drawPath(tri, col)
                    drawPath(tri, Color.Black, style = Stroke(1.5f))
                }

                // Current position
                if (status.hasFix && status.lat != null && status.lon != null) {
                    val o = LatLon(status.lat, status.lon).toOffset(proj, size)
                    drawCircle(Color.White, 10f, o)
                    drawCircle(Blue, 7f, o)
                }
            }
        }

        // Legend overlay
        Column(
            Modifier.align(Alignment.TopStart).padding(12.dp)
                .background(Panel.copy(alpha = 0.85f), RoundedCornerShape(4.dp)).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LegendDot(Blue, "GPS track")
            if (mapData.devices.any { it.following }) LegendDot(Critical, "Following device")
            if (mapData.devices.any { !it.following }) LegendDot(Caution, "Persistent device")
            if (mapData.cells.isNotEmpty()) LegendDot(Caution, "Cell anomaly")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
        Text(label, color = InkDim, fontSize = 11.sp)
    }
}

// ── Timeline screen ───────────────────────────────────────────────────────────

private val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.US)

@Composable
private fun TimelineScreen() {
    val events by Registry.timeline.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("EVENT LOG", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text("${events.size} recorded event(s)", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        if (events.isEmpty()) {
            Text("No events yet. Events are recorded when scanning starts, a device is confirmed following, or a cell anomaly is detected.",
                color = Muted, fontSize = 13.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(events, key = { it.id }) { TimelineRow(it) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun TimelineRow(e: TimelineEvent) {
    val (stripe, titleColor) = when (e.severity) {
        Severity.CRITICAL -> Critical to Critical
        Severity.HIGH -> Color(0xFFE8583D) to Color(0xFFE8583D)
        Severity.MEDIUM -> Caution to Caution
        Severity.LOW -> Muted to InkDim
    }
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(4.dp))) {
        Box(Modifier.width(3.dp).heightIn(min = 56.dp).background(stripe))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(e.title, color = titleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(sdf.format(Date(e.ts)), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (e.detail.isNotBlank()) Text(e.detail, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.lat != null) Text("%.4f, %.4f".format(e.lat, e.lon), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ── Cell / IMSI screen ────────────────────────────────────────────────────────

@Composable
private fun CellScreen() {
    val cell by Registry.cell.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("CELLULAR", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text("Baseline-based anomaly detection · no account required", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        if (!cell.available) {
            Box(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(4.dp)).padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(cell.reason ?: "Cell data unavailable", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            return@Column
        }

        // Threat level banner
        val (bannerColor, bannerText) = when (cell.level) {
            Threat.CRITICAL -> Critical to "CRITICAL — Multiple anomaly indicators"
            Threat.HIGH -> Color(0xFFE8583D) to "HIGH — Significant anomalies detected"
            Threat.MEDIUM -> Caution to "MEDIUM — Some indicators present"
            Threat.LOW -> Color(0xFF9AB8D4) to "LOW — Minor indicators"
            Threat.NONE -> Clear to "CLEAR — No anomalies"
        }
        Box(
            Modifier.fillMaxWidth().background(bannerColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                .padding(12.dp)
        ) {
            Column {
                Text(bannerText, color = bannerColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Score ${cell.score}/100", color = bannerColor.copy(alpha = 0.8f), fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Current cell info
        val c = cell.cell
        if (c != null) {
            Column(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(4.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CellInfoRow("Technology", c.rat.label)
                CellInfoRow("Cell ID", c.cellId)
                CellInfoRow("MCC / MNC", "${c.mcc ?: "?"} / ${c.mnc ?: "?"}")
                CellInfoRow("Tracking Area", c.tac ?: "Unknown")
                CellInfoRow("Signal", "${c.signalDbm ?: "??"} dBm")
                CellInfoRow("Neighbours", "${c.neighbors ?: "??"}")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Baseline maturity
        Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(4.dp)).padding(12.dp)) {
            Text("Baseline maturity", color = InkDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { cell.maturity },
                modifier = Modifier.fillMaxWidth(),
                color = if (cell.mature) Clear else Caution,
                trackColor = Rule
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (cell.mature) "${cell.knownCells} cells · ${cell.observations} observations · Mature"
                else "${cell.observations}/50 observations — keep scanning in your usual locations to build baseline",
                color = Muted, fontSize = 12.sp
            )
        }

        // Findings
        if (cell.findings.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("ACTIVE INDICATORS", color = Critical, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(cell.findings, key = { it.id }) { FindingRow(it) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        } else {
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CellInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = InkDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun FindingRow(f: CatcherFinding) {
    val col = when (f.severity) {
        Severity.CRITICAL -> Critical; Severity.HIGH -> Color(0xFFE8583D)
        Severity.MEDIUM -> Caution; Severity.LOW -> InkDim
    }
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(4.dp))) {
        Box(Modifier.width(3.dp).heightIn(min = 64.dp).background(col))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(f.title, color = col, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(f.detail, color = Muted, fontSize = 12.sp)
        }
    }
}

// ── NFC screen ────────────────────────────────────────────────────────────────

@Composable
private fun NfcScreen() {
    val tags by Registry.nfc.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("NFC SWEEP", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("HF RFID tag scanner — tap surfaces to read passive tags", color = Muted, fontSize = 12.sp)
            }
            if (tags.isNotEmpty()) {
                TextButton(onClick = { Registry.clearNfc() }) {
                    Text("CLEAR", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (tags.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No tags scanned yet.", color = InkDim, fontSize = 14.sp)
                Text("Hold the phone's NFC reader to surfaces while this tab is visible:\ndesk undersides, car seats, bag linings, laptop bases, door frames.",
                    color = Muted, fontSize = 13.sp)
                Text("Suspicious tags: MIFARE Classic chips appear in commercially sold covert tracking devices and access-card cloners. Payment cards show as safe.",
                    color = Muted, fontSize = 12.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tags, key = { it.uid }) { NfcTagRow(it) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun NfcTagRow(t: NfcTag) {
    val stripe = if (t.suspicious) Critical else Clear
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(4.dp))) {
        Box(Modifier.width(3.dp).heightIn(min = 80.dp).background(stripe))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(t.uid, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Text(if (t.suspicious) "SUSPICIOUS" else "CLEAN",
                    color = stripe, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            }
            Text("${t.type} · ${t.techs.joinToString()}", color = Accent, fontSize = 12.sp)
            Text(t.note, color = if (t.suspicious) Critical.copy(alpha = 0.9f) else Muted, fontSize = 12.sp)
            if (t.payload != null) Text("Data: ${t.payload.take(80)}", color = InkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(sdf.format(Date(t.ts)), color = Muted, fontSize = 11.sp)
        }
    }
}
