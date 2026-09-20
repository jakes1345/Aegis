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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.core.view.WindowCompat
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackdetect.CatcherFinding
import com.trackdetect.analysis.NfcScanner
import com.trackdetect.analysis.Report
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

// ── Colour tokens ─────────────────────────────────────────────────────────────

private val Ground     = Color(0xFF0E1116)
private val Panel      = Color(0xFF161B23)
private val PanelLight = Color(0xFF1D2430)
private val MapGround  = Color(0xFF0A0E13)
private val Ink        = Color(0xFFE6EAF1)
private val InkDim     = Color(0xFFA8B2C1)
private val Muted      = Color(0xFF6F7A8B)
private val Rule       = Color(0xFF262E3A)
private val Accent     = Color(0xFFFF7A3D)
private val Critical   = Color(0xFFF2545B)
private val Caution    = Color(0xFFE8B33D)
private val Clear      = Color(0xFF3DB88A)
private val Blue       = Color(0xFF4A8FD4)

private val CardShape = RoundedCornerShape(4.dp)

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AppSettings.load(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val onboardingAlreadyDone = isOnboardingDone(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ground, surface = Panel)) {
                Surface(color = Ground, modifier = Modifier.fillMaxSize()) {
                    var onboardingDone by remember { mutableStateOf(onboardingAlreadyDone) }
                    if (!onboardingDone) {
                        OnboardingScreen(
                            startScanService = { startService(Intent(this, ScanService::class.java)) },
                            onComplete = { onboardingDone = true }
                        )
                    } else {
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

// ── Formatting helpers ────────────────────────────────────────────────────────

private val fullFmt    = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)
private val clockFmt   = SimpleDateFormat("HH:mm:ss", Locale.US)
private val dayFmt     = SimpleDateFormat("MMM d", Locale.US)
private val dayYearFmt = SimpleDateFormat("MMM d yyyy", Locale.US)

private fun fmtTime(ts: Long): String = fullFmt.format(Date(ts))
private fun fmtClock(ts: Long): String = clockFmt.format(Date(ts))

private fun fmtAgo(now: Long, ts: Long): String {
    val s = ((now - ts) / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ${(s % 3600) / 60} min ago"
        else -> "${s / 86_400} d ago"
    }
}

private fun fmtDuration(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min ${s % 60} s"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

private fun fmtMetres(m: Double): String = when {
    m < 10 -> "~%.1f m".format(Locale.US, m)
    m < 1000 -> "~%.0f m".format(Locale.US, m)
    else -> "~%.1f km".format(Locale.US, m / 1000.0)
}

private fun fmtDbm(rssi: Int): String = when {
    rssi == 0 -> "— dBm"
    rssi < 0 -> "−${-rssi} dBm"
    else -> "$rssi dBm"
}

private fun plural(n: Int, word: String) = if (n == 1) "$n $word" else "$n ${word}s"

private fun compass(bearing: Double): String {
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[((bearing + 22.5) / 45.0).toInt() % 8]
}

private fun dayLabel(ts: Long, now: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis
    val thisYear = cal.get(Calendar.YEAR)
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterdayStart = cal.timeInMillis
    if (ts >= todayStart) return "TODAY"
    if (ts >= yesterdayStart) return "YESTERDAY"
    cal.timeInMillis = ts
    val fmt = if (cal.get(Calendar.YEAR) == thisYear) dayFmt else dayYearFmt
    return fmt.format(Date(ts)).uppercase(Locale.US)
}

// ── Threat colouring ──────────────────────────────────────────────────────────

private fun threatColor(t: Threat): Color = when (t) {
    Threat.CRITICAL -> Critical
    Threat.HIGH -> Accent
    Threat.MEDIUM -> Caution
    Threat.LOW -> Muted
    Threat.NONE -> Clear
}

private fun severityColor(s: Severity): Color = when (s) {
    Severity.CRITICAL -> Critical
    Severity.HIGH -> Accent
    Severity.MEDIUM -> Caution
    Severity.LOW -> Muted
}

/** Green at 0, yellow at 0.5, red at 1. */
private fun meterColor(t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return if (f <= 0.5f) lerp(Clear, Caution, f * 2f) else lerp(Caution, Critical, (f - 0.5f) * 2f)
}

private fun trailColor(t: Threat, following: Boolean): Color = when {
    following || t == Threat.CRITICAL -> Critical
    t == Threat.HIGH -> Accent
    t == Threat.MEDIUM -> Caution
    else -> Blue
}

// ── Shared building blocks ────────────────────────────────────────────────────

/** Wall-clock that ticks every [periodMs] so relative times stay honest. */
@Composable
private fun rememberNow(periodMs: Long = 1000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** Opacity that breathes 0.3 → 0.8 → 0.3, one second per leg. */
@Composable
private fun rememberPulse(): State<Float> {
    var high by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            high = !high
        }
    }
    return animateFloatAsState(
        targetValue = if (high) 0.8f else 0.3f,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "pulse"
    )
}

@Composable
private fun ThreatBadge(label: String, color: Color) {
    Text(
        label, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun SectionLabel(text: String, color: Color = Muted) {
    Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))
}

@Composable
private fun KvRow(label: String, value: String, valueColor: Color = InkDim) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
        Text(
            value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NoticeBanner(color: Color, tag: String, text: String) {
    Row(
        Modifier.fillMaxWidth()
            .background(color.copy(alpha = 0.12f), CardShape)
            .border(1.dp, color.copy(alpha = 0.5f), CardShape)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(tag, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(text, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PanelBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Panel, CardShape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

// ── Threat explainer target ────────────────────────────────────────────────────

private sealed class ExplainerTarget {
    data class BleDevice(val detection: Detection) : ExplainerTarget()
    data class CellIndicator(val finding: CatcherFinding) : ExplainerTarget()
}

// ── Root composable ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainApp(onStart: () -> Unit, onStop: () -> Unit, hasPermissions: () -> Boolean) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("SCAN", "MAP", "LOG", "CELL", "NFC", "WIFI")

    val detections by Registry.detections.collectAsStateWithLifecycle()
    val trusted by Registry.trusted.collectAsStateWithLifecycle()
    val cell by Registry.cell.collectAsStateWithLifecycle()
    val nfc by Registry.nfc.collectAsStateWithLifecycle()
    val wifi by Registry.wifi.collectAsStateWithLifecycle()

    // Settings navigation and threat explainer state
    var showSettings by remember { mutableStateOf(false) }
    var explainerTarget by remember { mutableStateOf<ExplainerTarget?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
        return
    }

    // One red dot per tab that currently holds something worth looking at.
    val followingLive = detections.any { it.following && it.key !in trusted }
    val alerts = listOf(
        followingLive,
        followingLive && detections.any { it.following && it.points.isNotEmpty() },
        false,
        cell.available && cell.level.ordinal >= Threat.HIGH.ordinal,
        nfc.any { it.suspicious },
        wifi.isNotEmpty()
    )

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).statusBarsPadding()) {
            when (tab) {
                0 -> ScanScreen(
                    onStart, onStop, hasPermissions,
                    onSettingsClick = { showSettings = true },
                    onShowExplainer = { d -> explainerTarget = ExplainerTarget.BleDevice(d) }
                )
                1 -> MapScreen()
                2 -> TimelineScreen()
                3 -> CellScreen(
                    onShowExplainer = { f -> explainerTarget = ExplainerTarget.CellIndicator(f) }
                )
                4 -> NfcScreen()
                5 -> WifiScreen()
            }
        }
        NavigationBar(
            containerColor = Panel,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding()
        ) {
            tabs.forEachIndexed { i, label ->
                NavigationBarItem(
                    selected = tab == i,
                    onClick = { tab = i },
                    icon = {
                        Box {
                            TabIcon(index = i, selected = tab == i)
                            if (alerts[i]) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .background(Critical, CircleShape)
                                        .align(Alignment.TopEnd)
                                        .offset(x = 2.dp, y = (-2).dp)
                                )
                            }
                        }
                    },
                    label = {
                        Text(label, fontSize = 10.sp, letterSpacing = 0.3.sp, maxLines = 1)
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent,
                        selectedTextColor = Accent,
                        indicatorColor = Accent.copy(alpha = 0.15f),
                        unselectedIconColor = Muted,
                        unselectedTextColor = Muted
                    )
                )
            }
        }
    }

    // Threat explainer bottom sheet
    val target = explainerTarget
    if (target != null) {
        ModalBottomSheet(
            onDismissRequest = { explainerTarget = null },
            sheetState = sheetState,
            containerColor = Panel
        ) {
            ThreatExplainerSheet(target = target, onDismiss = { explainerTarget = null })
        }
    }
}

// ── Tab icons ─────────────────────────────────────────────────────────────────

@Composable
private fun TabIcon(index: Int, selected: Boolean) {
    val color = if (selected) Accent else Muted
    Canvas(Modifier.size(22.dp)) {
        val cx = center.x
        val cy = center.y
        when (index) {
            0 -> { // SCAN — radar rings
                drawCircle(color.copy(alpha = 0.35f), radius = size.minDimension * 0.46f, center = center, style = Stroke(1f))
                drawCircle(color.copy(alpha = 0.65f), radius = size.minDimension * 0.3f, center = center, style = Stroke(1.5f))
                drawCircle(color, radius = size.minDimension * 0.14f, center = center)
            }
            1 -> { // MAP — location pin
                val pinR = size.minDimension * 0.3f
                val pinTop = Offset(cx, cy - pinR * 1.2f)
                val path = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(cx - pinR, cy - pinR * 2.2f, cx + pinR, cy))
                    moveTo(cx, cy)
                    lineTo(cx - pinR * 0.5f, cy + pinR * 0.6f)
                    lineTo(cx + pinR * 0.5f, cy + pinR * 0.6f)
                    close()
                }
                drawPath(path, color)
                drawCircle(Panel, radius = pinR * 0.38f, center = Offset(cx, cy - pinR))
            }
            2 -> { // LOG — stacked lines
                val w = size.width * 0.75f
                val gaps = listOf(0.25f, 0.5f, 0.75f)
                val widths = listOf(w, w * 0.78f, w * 0.56f)
                gaps.zip(widths).forEach { (frac, lineW) ->
                    drawLine(color, Offset(cx - lineW / 2f, size.height * frac), Offset(cx + lineW / 2f, size.height * frac), strokeWidth = 2f)
                }
            }
            3 -> { // CELL — signal bars
                val barW = size.width * 0.13f
                val gap = size.width * 0.07f
                val totalW = barW * 4 + gap * 3
                val startX = cx - totalW / 2f
                for (b in 0..3) {
                    val barH = size.height * (0.25f + b * 0.18f)
                    val x = startX + b * (barW + gap)
                    val barColor = if (selected || b < 2) color else color.copy(alpha = 0.3f)
                    drawRect(barColor, topLeft = Offset(x, size.height - barH - 2f), size = Size(barW, barH))
                }
            }
            4 -> { // NFC — near-field arcs
                val arcSizes = listOf(0.85f, 0.55f, 0.3f)
                arcSizes.forEachIndexed { i, scale ->
                    val r = size.minDimension * scale * 0.5f
                    drawArc(
                        color = color.copy(alpha = 1f - i * 0.3f),
                        startAngle = 210f, sweepAngle = 120f,
                        useCenter = false,
                        topLeft = Offset(cx - r, cy - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(2f - i * 0.4f)
                    )
                }
                drawCircle(color, radius = 2.5f, center = Offset(cx - size.minDimension * 0.35f, cy))
            }
            else -> { // WIFI — wifi arcs
                val arcSizes = listOf(0.9f, 0.6f, 0.3f)
                arcSizes.forEachIndexed { i, scale ->
                    val r = size.minDimension * scale * 0.5f
                    drawArc(
                        color = color.copy(alpha = if (selected || i > 0) 1f else 0.5f),
                        startAngle = 200f, sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(cx - r, cy - r * 0.5f),
                        size = Size(r * 2, r * 2),
                        style = Stroke(if (i == 0) 2f else 1.5f)
                    )
                }
                drawCircle(color, radius = 2.5f, center = Offset(cx, size.height * 0.8f))
            }
        }
    }
}

// ── Scan (BLE) screen ─────────────────────────────────────────────────────────

@Composable
private fun ScanScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    hasPermissions: () -> Boolean,
    onSettingsClick: () -> Unit = {},
    onShowExplainer: (Detection) -> Unit = {}
) {
    val detections by Registry.detections.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val trusted by Registry.trusted.collectAsStateWithLifecycle()
    val timeline by Registry.timeline.collectAsStateWithLifecycle()
    val cell by Registry.cell.collectAsStateWithLifecycle()
    val nfc by Registry.nfc.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val now = rememberNow()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (REQUIRED.all { grants[it] == true }) onStart()
    }

    // Devices the user has vouched for drop out of the threat picture and sink to the bottom.
    val live = remember(detections, trusted) { detections.filter { it.key !in trusted } }
    val ordered = remember(detections, trusted) { detections.sortedBy { it.key in trusted } }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "§header") {
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("TRACK DETECT", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val i = Report.share(context, status, detections, timeline, cell, nfc)
                        context.startActivity(Intent.createChooser(i, "Share evidence report"))
                    }) { Text("EXPORT", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp) }
                    TextButton(onClick = onSettingsClick) {
                        Text("⚙", color = Muted, fontSize = 18.sp)
                    }
                }
            }
        }

        if (status.scanning) {
            item(key = "§meter") { ThreatMeter(live, cell) }
        }

        item(key = "§status") { ScanStatusPanel(status, detections, trusted) }

        item(key = "§toggle") {
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
                shape = CardShape, modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (status.scanning) "STOP SCANNING" else "START SCANNING",
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            }
        }

        if (detections.isEmpty()) {
            item(key = "§empty") { EmptyState(status.scanning) }
        } else {
            item(key = "§list-label") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("FLAGGED DEVICES")
                    Text(
                        "${live.size} live · ${detections.size - live.size} trusted · tap a card for detail",
                        color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }
            items(ordered, key = { it.key }) { d ->
                DetectionRow(
                    d = d,
                    trusted = d.key in trusted,
                    now = now,
                    onShowExplainer = if (d.threat == Threat.CRITICAL || d.threat == Threat.HIGH)
                        { { onShowExplainer(d) } } else null
                )
            }
        }

        item(key = "§footer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ThreatMeter(live: List<Detection>, cell: CellStatus) {
    val bleScore = live.maxOfOrNull { it.score } ?: 0
    val cellScore = if (cell.available) cell.score else 0
    val score = maxOf(bleScore, cellScore).coerceIn(0, 100)
    val fraction by animateFloatAsState(
        targetValue = score / 100f,
        animationSpec = tween(durationMillis = 600),
        label = "threat"
    )
    val color = meterColor(fraction)

    val following = live.count { it.following }
    val known = live.count { it.identified }
    val cellHot = cell.available && cell.level.ordinal >= Threat.HIGH.ordinal

    val (summary, summaryColor) = when {
        cellHot && (following > 0 || known > 0) ->
            "IMSI anomaly + BLE tracker: possible coordinated surveillance" to Critical
        following == 1 -> "CRITICAL: device following you" to Critical
        following > 1 -> "CRITICAL: $following devices following you" to Critical
        cellHot -> "IMSI anomaly: possible fake cell tower in range" to Critical
        known > 0 -> "${plural(known, "known tracker")} in range" to Accent
        live.isNotEmpty() -> "${plural(live.size, "unidentified device")} under observation" to Caution
        else -> "No threats" to Clear
    }

    Column(
        Modifier.fillMaxWidth().background(Panel, CardShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("THREAT LEVEL")
            Text(
                "$score", color = color, fontSize = 16.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
            )
        }
        Box(Modifier.fillMaxWidth().height(8.dp).background(Rule, RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color, RoundedCornerShape(4.dp)))
        }
        Text(summary, color = summaryColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "BLE peak $bleScore · cellular $cellScore · scale 0–100",
            color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ScanStatusPanel(status: ScanStatus, detections: List<Detection>, trusted: Set<String>) {
    val live = detections.filter { it.key !in trusted }
    val following = live.count { it.following }
    val (verdict, verdictColor) = when {
        following > 0 -> "$following CONFIRMED FOLLOWING" to Critical
        live.any { it.persistent } -> "Persistent devices, unconfirmed" to Caution
        else -> "Nothing confirmed" to Clear
    }
    Column(
        Modifier.fillMaxWidth().background(Panel, CardShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(verdict, color = verdictColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val scanText = when {
            !status.scanning -> "Idle"
            status.nearbyCount > detections.size ->
                "Scanning · ${detections.size} flagged · ${status.nearbyCount} total in range"
            else -> "Scanning · ${detections.size} device(s) flagged"
        }
        Text(scanText, color = InkDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        val filtered = status.nearbyCount - detections.size
        if (status.scanning && filtered > 0) {
            Text("+$filtered transient signals filtered", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Text(if (status.hasFix)
            "Fix %.4f, %.4f · ${if (status.moving) "moving" else "stationary"} · %.1f km".format(
                Locale.US, status.lat ?: 0.0, status.lon ?: 0.0, status.travelledM / 1000.0)
            else "No fix — following cannot be confirmed without GPS",
            color = if (status.hasFix) Muted else Caution, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        if (trusted.isNotEmpty()) {
            Text("${plural(trusted.size, "device")} marked safe and excluded from the threat level",
                color = Muted, fontSize = 11.sp)
        }
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
private fun RssiBar(rssi: Int) {
    val strength = if (rssi == 0) 0f else ((rssi + 100) / 60f).coerceIn(0f, 1f)
    val color = when {
        rssi == 0 -> Muted
        rssi >= -60 -> Critical
        rssi >= -75 -> Accent
        rssi >= -90 -> Caution
        else -> Muted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(48.dp).height(5.dp).background(Rule, RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxWidth(strength).fillMaxHeight().background(color, RoundedCornerShape(2.dp)))
        }
        Text(fmtDbm(rssi), color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DetectionRow(
    d: Detection,
    trusted: Boolean,
    now: Long,
    onShowExplainer: (() -> Unit)? = null
) {
    var expanded by remember(d.key) { mutableStateOf(false) }
    val stripe = if (trusted) Rule else threatColor(d.threat)
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "chevron"
    )

    Row(
        Modifier.fillMaxWidth()
            .clip(CardShape)
            .background(Panel)
            .clickable { expanded = !expanded }
            .height(IntrinsicSize.Min)
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(stripe))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    d.name, color = if (trusted) InkDim else Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (trusted) ThreatBadge("TRUSTED", Muted) else ThreatBadge(d.threat.name, threatColor(d.threat))
                Text("›", color = Muted, fontSize = 18.sp, modifier = Modifier.rotate(chevron))
            }

            val t = d.tracker
            if (t != null) {
                Text("${t.label} · ${t.brand}", color = if (trusted) Muted else Accent, fontSize = 12.sp)
            } else {
                Text("Unidentified BLE device", color = Muted, fontSize = 12.sp)
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${d.sightings}× · last ${fmtClock(d.lastSeen)} · ${fmtAgo(now, d.lastSeen)}",
                    color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                RssiBar(d.rssi)
            }

            if (d.following && !trusted) {
                Text(
                    "⚠ Confirmed following — ${d.displacementM.roundToInt()} m apart",
                    color = Critical, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
            }

            if (expanded) DetectionDetail(d, trusted, onShowExplainer)
        }
    }
}

@Composable
private fun DetectionDetail(d: Detection, trusted: Boolean, onShowExplainer: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Hairline()

        // ── Tracker type ──
        SectionLabel("TRACKER TYPE")
        val t = d.tracker
        if (t != null) {
            Column(
                Modifier.fillMaxWidth().background(PanelLight, CardShape).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(t.label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    ThreatBadge(t.threat.name, threatColor(t.threat))
                }
                Text("Brand: ${t.brand} · Threat rating: ${t.threat.name}", color = InkDim, fontSize = 12.sp)
                Text(t.notes, color = InkDim, fontSize = 12.sp, lineHeight = 17.sp)
            }
        } else {
            Text(
                "No known tracker signature matched. Flagged on behaviour alone — persistence, movement and signal pattern.",
                color = Muted, fontSize = 12.sp, lineHeight = 17.sp
            )
        }

        // ── Observation ──
        SectionLabel("OBSERVATION")
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            KvRow("First seen", fmtTime(d.firstSeen))
            KvRow("Last seen", fmtTime(d.lastSeen))
            KvRow("Present for", fmtDuration(d.lastSeen - d.firstSeen))
            KvRow(
                "Sightings",
                if (d.places > 0) "${d.sightings} across ${plural(d.places, "distinct location")}"
                else "${d.sightings} (no location data)"
            )
            KvRow("Est. distance", d.approxMetres?.let { fmtMetres(it) } ?: "unknown")
            KvRow("Signal", fmtDbm(d.rssi))
            KvRow("Displacement", "Observed ${d.displacementM.roundToInt()} metres apart")
            KvRow("Address", d.address)
            KvRow(
                "MAC rotation",
                if (d.rotations > 0) "MAC rotated ${d.rotations}× · ${plural(d.addresses, "address").replace("addresss", "addresses")} seen"
                else "Stable address",
                if (d.rotations > 0) Caution else InkDim
            )
            KvRow("Threat score", "${d.score}/100", if (trusted) Muted else threatColor(d.threat))
        }

        // ── Follow assessment ──
        SectionLabel("FOLLOW ASSESSMENT")
        val (line, lineColor) = when (d.confidence) {
            FollowConfidence.NONE -> "Not enough data to assess" to Muted
            FollowConfidence.NO_POSITION -> "No GPS — cannot confirm movement" to Caution
            FollowConfidence.NOT_MOVED_ENOUGH -> "Present but not following" to Caution
            FollowConfidence.CONFIRMED -> "⚠ Confirmed following" to Critical
        }
        Text(line, color = if (trusted) Muted else lineColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val explanation = when (d.confidence) {
            FollowConfidence.NONE ->
                if (d.identified) "Known tracker type, but present too briefly to establish a pattern."
                else "Watching for a persistence pattern."
            FollowConfidence.NO_POSITION ->
                "Device is persistent. A GPS fix is required to prove it moves with you."
            FollowConfidence.NOT_MOVED_ENOUGH ->
                "Persistent, but only ${d.displacementM.roundToInt()} m of shared travel so far. Confirmation needs about 300 m."
            FollowConfidence.CONFIRMED ->
                "Seen ${d.displacementM.roundToInt()} m apart while moving with you. On you or your vehicle. " +
                    "Check: wheel wells, bumper covers, OBD-II port, under seats, bag linings."
        }
        Text(explanation, color = InkDim, fontSize = 12.sp, lineHeight = 17.sp)

        // ── Actions ──
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (trusted) {
                TextButton(
                    onClick = { Registry.untrust(d.key) },
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(1.dp, Rule)
                ) { Text("TRUSTED ✓", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp) }
                Text("Tap to revoke and put it back in the threat picture.", color = Muted, fontSize = 10.sp,
                    modifier = Modifier.weight(1f))
            } else {
                TextButton(
                    onClick = { Registry.trust(d.key) },
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(1.dp, Clear.copy(alpha = 0.6f))
                ) { Text("MARK SAFE", color = Clear, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
                Text("Marks this device as yours and removes it from the threat level.", color = Muted, fontSize = 10.sp,
                    modifier = Modifier.weight(1f))
            }
        }

        // "What is this?" explainer button for high/critical threats
        if (onShowExplainer != null && !trusted) {
            TextButton(
                onClick = onShowExplainer,
                shape = RoundedCornerShape(3.dp),
                border = BorderStroke(1.dp, Accent.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "WHAT IS THIS? HOW DO I RESPOND?",
                    color = Accent, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            }
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

private enum class Mark { DOT, LINE, TRI, RING }

@Composable
private fun MapScreen() {
    val mapData by Registry.map.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val cell by Registry.cell.collectAsStateWithLifecycle()
    val pulse = rememberPulse()
    val textMeasurer = rememberTextMeasurer()
    val bang = remember(textMeasurer) {
        textMeasurer.measure(
            AnnotatedString("!"),
            TextStyle(color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        )
    }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val proj = remember(mapData, status.hasFix, status.lat, status.lon, canvasSize) {
        if (canvasSize.width == 0 || canvasSize.height == 0) null
        else buildProjection(mapData, status, Size(canvasSize.width.toFloat(), canvasSize.height.toFloat()))
    }

    val hasContent = status.hasFix || mapData.track.isNotEmpty() || mapData.devices.isNotEmpty()
    val cellHot = cell.available && cell.level.ordinal >= Threat.HIGH.ordinal
    val followingTrails = mapData.devices.count { it.following }

    Box(Modifier.fillMaxSize().background(MapGround)) {
        if (!hasContent) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No GPS fix.\nStart scanning and move around\nto build the map.",
                    color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        } else {
            Canvas(Modifier.fillMaxSize().onSizeChanged { canvasSize = it }) {
                val p = proj ?: return@Canvas

                // GPS track
                if (mapData.track.size > 1) {
                    val path = Path()
                    mapData.track.forEachIndexed { i, pt ->
                        val o = pt.toOffset(p, size)
                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    drawPath(path, Blue.copy(alpha = 0.7f), style = Stroke(3f, cap = StrokeCap.Round))
                }

                // Device trails
                for (trail in mapData.devices) {
                    val col = trailColor(trail.threat, trail.following)
                    if (trail.points.size > 1) {
                        val path = Path()
                        trail.points.forEachIndexed { i, pt ->
                            val o = pt.toOffset(p, size)
                            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                        }
                        drawPath(path, col.copy(alpha = 0.5f), style = Stroke(2f))
                    }
                    trail.points.forEach { pt ->
                        drawCircle(col, radius = 5f, center = pt.toOffset(p, size))
                    }
                }

                // Threat overlay: a breathing red halo on the last known position of anything following.
                val alpha = pulse.value
                for (trail in mapData.devices) {
                    if (!trail.following || trail.points.isEmpty()) continue
                    val o = trail.points.last().toOffset(p, size)
                    drawCircle(Critical.copy(alpha = alpha * 0.35f), radius = 34f + 14f * alpha, center = o)
                    drawCircle(Critical.copy(alpha = alpha), radius = 18f + 6f * alpha, center = o)
                    drawCircle(Critical, radius = 6f, center = o)
                    drawCircle(Color.White, radius = 6f, center = o, style = Stroke(1.5f))
                }

                // Historical cell markers (triangles)
                for (marker in mapData.cells) {
                    val o = marker.point.toOffset(p, size)
                    val col = trailColor(marker.level, false)
                    val tri = Path().apply {
                        moveTo(o.x, o.y - 14f); lineTo(o.x + 11f, o.y + 9f)
                        lineTo(o.x - 11f, o.y + 9f); close()
                    }
                    drawPath(tri, col)
                    drawPath(tri, Color.Black, style = Stroke(1.5f))
                }

                // Current position
                val curLat = status.lat
                val curLon = status.lon
                if (status.hasFix && curLat != null && curLon != null) {
                    val o = LatLon(curLat, curLon).toOffset(p, size)
                    drawCircle(Color.White, 10f, o)
                    drawCircle(Blue, 7f, o)

                    // Live cell anomaly: yellow warning triangle sitting just above the position dot.
                    if (cellHot) {
                        val apexY = o.y - 46f
                        val baseY = o.y - 14f
                        val tri = Path().apply {
                            moveTo(o.x, apexY); lineTo(o.x + 18f, baseY)
                            lineTo(o.x - 18f, baseY); close()
                        }
                        drawPath(tri, Caution)
                        drawPath(tri, Color.Black, style = Stroke(2f))
                        drawText(
                            bang,
                            topLeft = Offset(
                                o.x - bang.size.width / 2f,
                                (apexY + baseY) / 2f - bang.size.height / 2f + 3f
                            )
                        )
                    }
                }
            }
        }

        // Top strip: what the map currently knows.
        Column(
            Modifier.align(Alignment.TopStart).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                buildString {
                    append(if (status.hasFix) "GPS fix" else "No GPS fix")
                    append(" · ${mapData.track.size} track pts")
                    if (mapData.devices.isNotEmpty()) append(" · ${plural(mapData.devices.size, "device trail")}")
                    if (followingTrails > 0) append(" · $followingTrails FOLLOWING")
                },
                color = if (followingTrails > 0) Critical else Muted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.background(Panel.copy(alpha = 0.85f), CardShape).padding(horizontal = 8.dp, vertical = 5.dp)
            )
            if (cellHot) {
                Text(
                    "⚠ Cell anomaly at your position — ${cell.level.name} (score ${cell.score})",
                    color = Caution, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(Panel.copy(alpha = 0.85f), CardShape).padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        }

        // Scale bar, bottom-left.
        val pr = proj
        if (pr != null && canvasSize.width > 0) {
            ScaleBar(pr.pxPerM, canvasSize.width, Modifier.align(Alignment.BottomStart).padding(12.dp))
        }

        // Legend, bottom-right, always visible.
        Column(
            Modifier.align(Alignment.BottomEnd).padding(12.dp)
                .background(Panel.copy(alpha = 0.85f), CardShape).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LegendItem(Blue, "GPS track", Mark.LINE)
            LegendItem(Blue, "Your position", Mark.RING)
            LegendItem(Critical, "Following device", Mark.DOT)
            LegendItem(Accent, "Known tracker", Mark.DOT)
            LegendItem(Caution, "Persistent device", Mark.DOT)
            LegendItem(Caution, "Cell anomaly", Mark.TRI)
        }
    }
}

@Composable
private fun ScaleBar(pxPerM: Float, canvasWidthPx: Int, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val maxPx = canvasWidthPx * 0.35f
    val candidates = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000)
    val metres = candidates.lastOrNull { it * pxPerM <= maxPx } ?: candidates.first()
    val widthDp = with(density) { (metres * pxPerM).toDp() }
    val label = if (metres >= 1000) "${metres / 1000} km" else "$metres m"
    Column(
        modifier.background(Panel.copy(alpha = 0.85f), CardShape).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.width(1.dp).height(7.dp).background(Ink))
            Box(Modifier.width(widthDp).height(2.dp).background(Ink))
            Box(Modifier.width(1.dp).height(7.dp).background(Ink))
        }
        Text(label, color = InkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LegendItem(color: Color, label: String, mark: Mark) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(10.dp)) {
            when (mark) {
                Mark.DOT -> drawCircle(color, radius = size.minDimension / 2f)
                Mark.RING -> {
                    drawCircle(Color.White, radius = size.minDimension / 2f)
                    drawCircle(color, radius = size.minDimension / 2f - 1.5f)
                }
                Mark.LINE -> drawLine(
                    color, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 3f
                )
                Mark.TRI -> {
                    val tri = Path().apply {
                        moveTo(size.width / 2f, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close()
                    }
                    drawPath(tri, color)
                }
            }
        }
        Text(label, color = InkDim, fontSize = 11.sp)
    }
}

// ── Timeline screen ───────────────────────────────────────────────────────────

@Composable
private fun TimelineScreen() {
    val events by Registry.timeline.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val now = rememberNow(60_000L)
    // Log is newest-first, so groups come out TODAY, YESTERDAY, then older days.
    val groups = remember(events, now / 60_000L) { events.groupBy { dayLabel(it.ts, now) } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("EVENT LOG", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(
            if (events.isEmpty()) "Nothing recorded" else "${plural(events.size, "recorded event")} · tap an event for location context",
            color = Muted, fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
        if (events.isEmpty()) {
            Text("No events recorded yet. Start scanning to begin monitoring.", color = Muted, fontSize = 13.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { (label, list) ->
                    item(key = "§day-$label") { DayHeader(label, list.size) }
                    items(list, key = { it.id }) { TimelineRow(it, status) }
                }
                item(key = "§footer") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun DayHeader(label: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SectionLabel(label, Accent)
        Text(plural(count, "event"), color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TimelineRow(e: TimelineEvent, status: ScanStatus) {
    var open by remember(e.id) { mutableStateOf(false) }
    val col = severityColor(e.severity)
    val titleColor = if (e.severity == Severity.LOW) InkDim else col
    val lat = e.lat
    val lon = e.lon
    val hasLoc = lat != null && lon != null

    Row(
        Modifier.fillMaxWidth()
            .clip(CardShape)
            .background(Panel)
            .clickable { open = !open }
            .height(IntrinsicSize.Min)
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(col))
        Column(
            Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(10.dp).background(col, CircleShape))
                Text(
                    e.title, color = titleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis
                )
                Text(fmtClock(e.ts), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (e.detail.isNotBlank()) {
                Text(
                    e.detail, color = Muted, fontSize = 12.sp,
                    maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis
                )
            }
            if (hasLoc) {
                Text("%.5f, %.5f".format(Locale.US, lat, lon), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (open) {
                Hairline()
                Text(
                    "${fmtTime(e.ts)} · ${e.kind.name.replace('_', ' ').lowercase(Locale.US)} · ${e.severity.name.lowercase(Locale.US)}",
                    color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
                if (lat != null && lon != null) {
                    EventLocationSnapshot(lat, lon, status)
                } else {
                    Text("No location recorded for this event", color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

/** A 100 dp square showing where the event happened relative to where the phone is now. */
@Composable
private fun EventLocationSnapshot(lat: Double, lon: Double, status: ScanStatus) {
    val curLat = status.lat
    val curLon = status.lon
    if (!status.hasFix || curLat == null || curLon == null) {
        Text("No location context", color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        return
    }
    val mPerDegLat = 111320.0
    val mPerDegLon = 111320.0 * cos(Math.toRadians(curLat))
    val eastM = (lon - curLon) * mPerDegLon
    val southM = (curLat - lat) * mPerDegLat
    val dist = hypot(eastM, southM)
    val bearing = (Math.toDegrees(atan2(eastM, -southM)) + 360.0) % 360.0

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(
            Modifier.size(100.dp)
                .background(MapGround, CardShape)
                .border(1.dp, Rule, CardShape)
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val maxR = size.minDimension / 2f - 10f
            drawCircle(Rule, radius = maxR, center = c, style = Stroke(1f))
            drawCircle(Rule, radius = maxR / 2f, center = c, style = Stroke(1f))
            val target = if (dist > 0.0) {
                val pxPerM = maxR / dist.toFloat()
                Offset(c.x + (eastM * pxPerM).toFloat(), c.y + (southM * pxPerM).toFloat())
            } else c
            drawLine(Muted, c, target, strokeWidth = 1f)
            drawCircle(Color.White, radius = 5f, center = c)
            drawCircle(Blue, radius = 3.5f, center = c)
            drawCircle(Critical, radius = 5f, center = target)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (dist < 1.0) "Here" else "${fmtMetres(dist).removePrefix("~")} away",
                color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace
            )
            if (dist >= 1.0) Text("${compass(bearing)} of your position", color = InkDim, fontSize = 11.sp)
            Text("Centre is where you are now", color = Muted, fontSize = 10.sp)
            Text("Outer ring = ${fmtMetres(dist).removePrefix("~")}", color = Muted, fontSize = 10.sp)
        }
    }
}

// ── Cell / IMSI screen ────────────────────────────────────────────────────────

@Composable
private fun CellScreen(onShowExplainer: ((CatcherFinding) -> Unit)? = null) {
    val cell by Registry.cell.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "§header") {
            Column(Modifier.padding(top = 20.dp)) {
                Text("CELLULAR", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Baseline-based IMSI catcher detection · no account required", color = Muted, fontSize = 12.sp)
            }
        }

        if (!cell.available) {
            item(key = "§unavailable") {
                Box(
                    Modifier.fillMaxWidth().background(Panel, CardShape).padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(cell.reason ?: "Cell data unavailable", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            item(key = "§risk") { CatcherRiskBadge(cell) }

            val c = cell.cell
            if (c != null) {
                item(key = "§serving") {
                    PanelBox {
                        SectionLabel("SERVING CELL")
                        CellInfoRow("Technology", c.rat.label)
                        CellInfoRow("Cell ID", c.cellId)
                        CellInfoRow("MCC / MNC", "${c.mcc ?: "?"} / ${c.mnc ?: "?"}")
                        CellInfoRow("Tracking Area", c.tac ?: "Unknown")
                        CellInfoRow("Signal", c.signalDbm?.let { fmtDbm(it) } ?: "?? dBm")
                        CellInfoRow("Neighbours", "${c.neighbors ?: "??"}")
                        CellInfoRow("Observed", fmtTime(c.ts))
                    }
                }
            }

            item(key = "§baseline") {
                Column(Modifier.fillMaxWidth().background(Panel, CardShape).padding(12.dp)) {
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
            }

            if (cell.findings.isNotEmpty()) {
                item(key = "§indicators") {
                    Text(
                        "ACTIVE INDICATORS (${cell.findings.size})", color = Critical, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(cell.findings, key = { it.id }) { f ->
                    FindingRow(
                        f = f,
                        onShowExplainer = if (f.severity == Severity.CRITICAL || f.severity == Severity.HIGH)
                            { { onShowExplainer?.invoke(f) } } else null
                    )
                }
            } else {
                item(key = "§no-indicators") {
                    Text("No active indicators against the current baseline.", color = Clear, fontSize = 12.sp)
                }
            }

            if (cell.score > 0) {
                item(key = "§meaning") { WhatThisMeans(cell.score) }
            }
        }

        item(key = "§footer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CatcherRiskBadge(cell: CellStatus) {
    val col = threatColor(cell.level)
    val (title, desc) = when (cell.level) {
        Threat.CRITICAL -> "RISK: CRITICAL" to "Multiple anomaly indicators — strong evidence of an IMSI catcher"
        Threat.HIGH -> "RISK: HIGH" to "Significant anomalies detected against your baseline"
        Threat.MEDIUM -> "RISK: MEDIUM" to "Some indicators present"
        Threat.LOW -> "RISK: LOW" to "Minor indicators"
        Threat.NONE -> "RISK: NONE" to "No anomalies against baseline"
    }
    Row(
        Modifier.fillMaxWidth()
            .background(col.copy(alpha = 0.12f), CardShape)
            .border(1.dp, col.copy(alpha = 0.5f), CardShape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            title, color = Ground, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
            modifier = Modifier.background(col, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(desc, color = col, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Score ${cell.score}/100", color = col.copy(alpha = 0.8f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun WhatThisMeans(score: Int) {
    val text = when {
        score < 30 -> "Low baseline anomaly. May be normal network fluctuation."
        score < 50 -> "Moderate anomaly. Elevated risk of cell tower spoofing. Consider moving."
        score < 80 -> "High anomaly. Possible IMSI catcher nearby. Avoid making sensitive calls."
        else -> "CRITICAL. Strong evidence of active IMSI catcher. Turn off mobile data. Use WiFi with VPN."
    }
    val col = meterColor(score / 100f)
    Column(
        Modifier.fillMaxWidth()
            .background(Panel, CardShape)
            .border(1.dp, col.copy(alpha = 0.5f), CardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SectionLabel("WHAT THIS MEANS", col)
        Text(text, color = Ink, fontSize = 13.sp, lineHeight = 18.sp)
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
private fun FindingRow(f: CatcherFinding, onShowExplainer: (() -> Unit)? = null) {
    val col = severityColor(f.severity)
    val clickMod = if (onShowExplainer != null) Modifier.clickable { onShowExplainer() } else Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Panel)
            .then(clickMod)
            .height(IntrinsicSize.Min)
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(col))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(f.title, color = col, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                ThreatBadge(f.severity.name, col)
                if (onShowExplainer != null) {
                    Text("?", color = col.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 2.dp))
                }
            }
            Text(f.detail, color = InkDim, fontSize = 12.sp, lineHeight = 17.sp)
            if (onShowExplainer != null) {
                Text("Tap for explanation and response guidance", color = col.copy(alpha = 0.6f),
                    fontSize = 10.sp, letterSpacing = 0.5.sp)
            }
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
            Column(Modifier.weight(1f)) {
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
            val suspicious = tags.count { it.suspicious }
            Text(
                "${plural(tags.size, "tag")} · $suspicious suspicious",
                color = if (suspicious > 0) Critical else Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tags, key = { it.uid }) { NfcTagRow(it) }
                item(key = "§footer") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun NfcTagRow(t: NfcTag) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(t.uid) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500L)
            copied = false
        }
    }
    // IsoDep tags are only ever marked clean when the PPSE handshake succeeded, i.e. EMV.
    val isPayment = !t.suspicious && t.techs.contains("IsoDep")
    val stripe = if (t.suspicious) Critical else Clear

    Row(Modifier.fillMaxWidth().clip(CardShape).background(Panel).height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(stripe))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    t.uid, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                ThreatBadge(if (t.suspicious) "SUSPICIOUS" else "CLEAN", stripe)
            }
            Text("${t.type} · ${t.techs.joinToString()}", color = Accent, fontSize = 12.sp)
            Text(t.note, color = if (t.suspicious) Critical.copy(alpha = 0.9f) else Muted, fontSize = 12.sp)
            if (t.suspicious) {
                NoticeBanner(Critical, "WARNING", "This tag type is commonly found in covert tracking devices")
            } else if (isPayment) {
                NoticeBanner(Clear, "EMV", "Legitimate payment tag — likely not a tracker")
            }
            if (t.payload != null) {
                Text("Data: ${t.payload.take(80)}", color = InkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(fmtTime(t.ts), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(t.uid))
                        copied = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        if (copied) "COPIED ✓" else "COPY UID",
                        color = if (copied) Clear else InkDim, fontSize = 11.sp, letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

// ── WiFi screen ───────────────────────────────────────────────────────────────

private fun wifiReason(reason: String): String = when (reason) {
    "known_catcher_ssid" -> "Known IMSI-catcher bait SSID"
    "signal_anomaly" -> "Abnormally strong signal — device may be nearby"
    "carrier_open_network" -> "Carrier name on open network — possible fake AP"
    "duplicate_bssid" -> "Same SSID on multiple channels — may be spoofed"
    "duplicate_ssid" -> "Same SSID broadcast by multiple access points — may be spoofed"
    "open_unsecured" -> "Open, unsecured network — traffic can be intercepted"
    else -> reason.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
}

@Composable
private fun WifiScreen() {
    val anomalies by Registry.wifi.collectAsStateWithLifecycle()
    val sorted = remember(anomalies) {
        anomalies.sortedWith(compareByDescending<WifiAnomaly> { it.threat.ordinal }.thenByDescending { it.ts })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("WiFi Anomaly Scanner", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text("Detects IMSI catcher bait networks and suspicious access points", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        if (sorted.isEmpty()) {
            Box(Modifier.fillMaxWidth().background(Panel, CardShape).padding(16.dp)) {
                Text(
                    "No WiFi anomalies detected. Scan is passive — results update automatically.",
                    color = InkDim, fontSize = 13.sp, lineHeight = 18.sp
                )
            }
        } else {
            val critical = sorted.count { it.threat == Threat.CRITICAL }
            val high = sorted.count { it.threat == Threat.HIGH }
            Text(
                "${plural(sorted.size, "anomaly").replace("anomalys", "anomalies")} · $critical critical · $high high",
                color = if (critical > 0) Critical else if (high > 0) Accent else Muted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(sorted, key = { i, a -> "${a.bssid}|${a.reason}|${a.ts}|$i" }) { _, a -> WifiRow(a) }
                item(key = "§footer") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun WifiRow(a: WifiAnomaly) {
    val col = threatColor(a.threat)
    Row(Modifier.fillMaxWidth().clip(CardShape).background(Panel).height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(col))
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    a.ssid.ifBlank { "<hidden SSID>" }, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                ThreatBadge(a.threat.name, col)
            }
            Text(a.bssid, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(wifiReason(a.reason), color = col, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(fmtDbm(a.rssi), color = InkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(fmtTime(a.ts), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ── Threat explainer bottom sheet ─────────────────────────────────────────────

private data class ExplainerContent(
    val headline: String,
    val whatIsThis: String,
    val whatToDo: String,
    val howCertain: String,
    val severity: Color
)

private fun bleExplainerContent(d: Detection): ExplainerContent {
    val col = when (d.threat) {
        Threat.CRITICAL -> Color(0xFFF2545B)
        Threat.HIGH -> Color(0xFFFF7A3D)
        else -> Color(0xFFE8B33D)
    }
    return when {
        d.following -> ExplainerContent(
            headline = "Confirmed Following — ${d.name}",
            whatIsThis = "A Bluetooth tracker has been verified as following your movements. " +
                "It appeared at multiple locations ${d.displacementM.roundToInt()} metres apart while you were " +
                "moving, which cannot be explained by proximity alone. ${d.tracker?.let { "This matches a known ${it.brand} ${it.label}." } ?: "No commercial tracker signature matched, but the movement pattern is conclusive."}",
            whatToDo = "Stop and search the vehicle or your belongings now. Check: wheel wells, bumper covers and cavities, " +
                "OBD-II diagnostic port (under dashboard), under seats and floor mats, inside bag linings and seams. " +
                "If found, do not discard it on the roadside — it is evidence. Photograph it in place before removing it. " +
                "Consider filing a police report and contacting legal counsel.",
            howCertain = "High confidence. Confirmation requires the device to appear at locations " +
                "${d.displacementM.roundToInt()} m apart during your movement. " +
                "Threat score: ${d.score}/100 across ${d.sightings} sightings.",
            severity = col
        )
        d.persistent && d.identified -> ExplainerContent(
            headline = "Known Tracker — ${d.name}",
            whatIsThis = "A ${d.tracker?.brand ?: "commercial"} ${d.tracker?.label ?: "tracker"} has been " +
                "present for an extended period. Known trackers are purpose-built surveillance devices. " +
                "Extended presence without confirmed movement suggests it may be attached to your vehicle or belongings.",
            whatToDo = "Monitor this device. If it remains present as you travel, it will be promoted to " +
                "FOLLOWING status automatically. If you believe it does not belong to you, physically search " +
                "your vehicle and belongings. Pay attention to whether the signal strength (RSSI) stays consistent, " +
                "which would indicate it is on or very near your person.",
            howCertain = "Medium-to-high confidence. Device is identified as a commercial tracker with " +
                "${d.sightings} sightings over an extended period. Following confirmation requires GPS movement. " +
                "Threat score: ${d.score}/100.",
            severity = col
        )
        d.persistent -> ExplainerContent(
            headline = "Persistent Unknown Device — ${d.name}",
            whatIsThis = "An unidentified Bluetooth device has been present continuously for an extended period. " +
                "This could be a benign device (a neighbour's router, a fixed sensor) or a tracker that does not " +
                "match the known signature database. Its persistence pattern is unusual for a passing device.",
            whatToDo = "Watch for this device across different locations. If it follows you as you travel, " +
                "it will be flagged as FOLLOWING. If it is stationary and you remain in one place, it is " +
                "likely environmental. Mark it as TRUSTED below if you know what it is.",
            howCertain = "Low-to-medium confidence. No known tracker signature matched — flagged on " +
                "behaviour alone. Threat score: ${d.score}/100 over ${d.sightings} sightings.",
            severity = col
        )
        else -> ExplainerContent(
            headline = "${d.threat.name} Threat — ${d.name}",
            whatIsThis = "This device has characteristics associated with surveillance trackers. " +
                "${d.tracker?.let { "It matches the signature of a ${it.brand} ${it.label}." } ?: "No exact tracker signature matched, but its signal pattern warrants monitoring."}",
            whatToDo = "Continue monitoring. The scan needs more time and movement to determine whether " +
                "this device is following you. Keep the scan running as you travel.",
            howCertain = "Building confidence. Score ${d.score}/100 · ${d.sightings} sightings. " +
                "GPS confirmation of following requires approximately 300 m of shared movement.",
            severity = col
        )
    }
}

private fun cellExplainerContent(f: CatcherFinding): ExplainerContent {
    val col = when (f.severity) {
        Severity.CRITICAL -> Color(0xFFF2545B)
        Severity.HIGH -> Color(0xFFFF7A3D)
        Severity.MEDIUM -> Color(0xFFE8B33D)
        Severity.LOW -> Color(0xFF6F7A8B)
    }
    val (what, todo, certain) = when (f.id) {
        "rat_downgrade" -> Triple(
            "Your device has been forced from a newer radio technology (4G/5G) down to an older one (2G/3G). " +
            "IMSI catchers do this deliberately because older protocols have weaker encryption and are easier " +
            "to intercept. Legitimate networks only downgrade in areas with no newer coverage.",
            "Avoid making voice calls or sending SMS until you leave the area. Use end-to-end encrypted " +
            "messaging apps over WiFi or data. Do not transmit sensitive information. Move away from the " +
            "area and observe whether the technology level returns to normal.",
            "High confidence indicator. Technology downgrade at a location where higher tech was previously " +
            "seen is a primary IMSI catcher signature."
        )
        "cellid_tac_mismatch" -> Triple(
            "A cell tower ID that you have seen before is now claiming to be in a different area (tracking " +
            "area code). Real base stations have fixed, permanent area assignments. A portable IMSI catcher " +
            "moving through the area will produce inconsistent area codes.",
            "Monitor for additional indicators. A single mismatch could be a network reconfiguration, but " +
            "combined with other findings it is significant. Avoid sensitive calls in this area.",
            "High confidence indicator when combined with other findings. Alone it may reflect legitimate " +
            "network changes, but it warrants heightened caution."
        )
        "tac_change_stationary" -> Triple(
            "Your device registered a different tracking area while you were not moving. " +
            "Tracking area changes normally happen when you travel across cell boundaries. When you are " +
            "stationary, a change indicates a new transmitter has appeared near you and forced re-registration.",
            "Note your exact location and time. A stationary area change combined with other indicators " +
            "is a strong surveillance signal. Consider moving away from the area.",
            "Medium confidence. Stationary area changes can occur due to legitimate network maintenance, " +
            "but are uncommon and warrant attention alongside other findings."
        )
        "signal_outlier" -> Triple(
            "The cell signal is significantly stronger than the historical maximum for this area. " +
            "A portable transmitter placed near you — in a vehicle, building, or backpack — produces " +
            "a much stronger signal than a distant tower.",
            "Check your surroundings for parked vehicles with unusual equipment, people lingering nearby, " +
            "or recently placed objects. A strong signal alone is insufficient for action, but note the " +
            "location and observe whether it moves with you.",
            "Medium confidence. Signal strength varies for many reasons. This indicator is most meaningful " +
            "when combined with technology downgrade or unknown cell findings."
        )
        "unknown_cell" -> Triple(
            "A cell tower that has never appeared in this area before has become your serving cell. " +
            "You have been to this location many times, and the tower infrastructure here is well-established. " +
            "Portable IMSI catchers appear as unknown cells in familiar areas.",
            "A new cell at a familiar location deserves caution. If you also see technology downgrade or " +
            "signal strength anomalies, treat this as a serious surveillance indicator. Do not make sensitive " +
            "calls. Move away and observe whether the new cell disappears.",
            "Medium confidence. New towers do get installed occasionally, but this detection requires " +
            "high baseline maturity (many prior visits) before triggering."
        )
        "ephemeral_cell", "ephemeral_cell_strict" -> Triple(
            "A cell tower appeared briefly as your serving cell, then vanished. Real base stations " +
            "broadcast continuously — they do not appear for a few minutes and disappear. " +
            "A portable surveillance device driven into and away from an area looks exactly like this.",
            "Note the time and location. An ephemeral cell that appeared and disappeared suggests a " +
            "mobile surveillance asset that has moved on. Review your surroundings at the time of detection.",
            "Medium-to-high confidence. Ephemeral cells have few legitimate explanations. " +
            "A duration under 90 seconds is the strictest threshold."
        )
        "cell_flapping" -> Triple(
            "Your device has switched between multiple different cell towers rapidly while you were " +
            "stationary. IMSI catchers force repeated re-registrations to capture authentication events. " +
            "Normal network handover does not produce this pattern at a single location.",
            "Avoid making calls or sending messages while this is occurring. If it persists, " +
            "move away from the area. The flapping pattern suggests active interference with your " +
            "device's network registration.",
            "Medium confidence. Rapid cell switching can also result from poor coverage areas, " +
            "but is unusual when stationary in a normally covered location."
        )
        "no_neighbors" -> Triple(
            "Your serving cell is reporting zero neighbouring cells. Real base stations always have " +
            "overlapping coverage with adjacent towers — this is fundamental to how cellular networks " +
            "are designed. A portable IMSI catcher operating alone has no neighbours to report.",
            "This is a supporting indicator rather than an action trigger alone. Watch for it " +
            "alongside technology downgrade or unknown cell findings, at which point you should " +
            "follow the high-confidence response guidance.",
            "Low confidence as a standalone indicator. Meaningful when combined with other findings."
        )
        "timing_advance_zero" -> Triple(
            "LTE timing advance of zero means the transmitter is within approximately 78 metres of " +
            "your device. Macro cell towers are never this close to you. A portable IMSI catcher in " +
            "a parked vehicle or nearby building would produce exactly this reading.",
            "A transmitter within 78 metres is immediately actionable. Scan your visual surroundings " +
            "for parked vehicles with rooftop antennas or unusual equipment. Do not make sensitive " +
            "calls. Move away and observe whether the indicator follows you.",
            "High confidence. LTE timing advance is a precise physical measurement — zero means very " +
            "close proximity to the transmitter."
        )
        "signal_spike" -> Triple(
            "The signal strength on your current cell tower jumped suddenly between two consecutive " +
            "readings. A macro cell tower at a fixed location has a stable signal. A mobile transmitter " +
            "moving towards you — in a vehicle, for example — produces exactly this kind of spike.",
            "A sudden signal spike suggests a mobile surveillance asset moving closer to your position. " +
            "Be aware of your surroundings. If the spike is combined with other indicators, follow " +
            "the high-confidence response protocol immediately.",
            "Medium confidence. Signal spikes can result from device movements, building reflections, " +
            "or other environmental factors, but are notable when combined with other findings."
        )
        "rat_oscillation" -> Triple(
            "Your device has switched between multiple radio technologies (2G, 3G, 4G) rapidly in " +
            "a short window. IMSI catchers force devices through technology cycles to capture separate " +
            "authentication events on each technology, revealing the device's IMSI.",
            "Multiple technology switches in a short window strongly indicate active interference. " +
            "Avoid all calls and data use. Use WiFi with a VPN for any communications. " +
            "Move out of the area and observe whether the switching stops.",
            "High confidence. Rapid multi-technology oscillation is a strong signature of active " +
            "IMSI capture operations."
        )
        else -> Triple(
            "An anomaly was detected in your cellular environment that deviates from your established " +
            "baseline. The specific indicator ID is: ${f.id}. See the full detail for more context.",
            "Monitor for additional indicators. If multiple anomalies appear simultaneously, " +
            "treat the situation as a potential IMSI catcher and avoid sensitive communications.",
            "Confidence level: ${f.severity.name.lowercase()}."
        )
    }
    return ExplainerContent(
        headline = f.title,
        whatIsThis = what,
        whatToDo = todo,
        howCertain = certain,
        severity = col
    )
}

@Composable
private fun ThreatExplainerSheet(target: ExplainerTarget, onDismiss: () -> Unit) {
    val content = when (target) {
        is ExplainerTarget.BleDevice -> bleExplainerContent(target.detection)
        is ExplainerTarget.CellIndicator -> cellExplainerContent(target.finding)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Drag handle (visual only)
        Box(
            Modifier
                .width(40.dp)
                .height(4.dp)
                .background(Muted.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                .align(Alignment.CenterHorizontally)
        )

        // Headline
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                content.headline,
                color = content.severity,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))

        // What is this?
        ExplainerSection(
            heading = "WHAT IS THIS?",
            body = content.whatIsThis,
            headingColor = Accent
        )

        Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))

        // What should I do?
        ExplainerSection(
            heading = "WHAT SHOULD I DO?",
            body = content.whatToDo,
            headingColor = Accent
        )

        Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))

        // How certain is this?
        ExplainerSection(
            heading = "HOW CERTAIN IS THIS?",
            body = content.howCertain,
            headingColor = Muted
        )

        // Dismiss button
        TextButton(
            onClick = onDismiss,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("CLOSE", color = Muted, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ExplainerSection(heading: String, body: String, headingColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            heading,
            color = headingColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
        Text(
            body,
            color = InkDim,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
    }
}
