package com.xat.aegis

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xat.aegis.analysis.CaAuditScanner
import com.xat.aegis.analysis.LocationHistory
import com.xat.aegis.analysis.PatternOfLife
import com.xat.aegis.analysis.PatternReport
import com.xat.aegis.analysis.StalkwareScanner
import com.xat.aegis.analysis.SuspiciousCa
import com.xat.aegis.analysis.TlsCanary
import com.xat.aegis.analysis.TlsCanaryResult
import com.xat.aegis.analysis.clock
import com.xat.aegis.comms.CommsRepository
import com.xat.aegis.comms.Contact
import com.xat.aegis.comms.formatAegisNumber
import com.xat.aegis.detect.BleCanary
import com.xat.aegis.detect.BleCanaryState
import com.xat.aegis.detect.DultInterrogator
import com.xat.aegis.detect.DultResult
import com.xat.aegis.detect.LocatorState
import com.xat.aegis.detect.RssiLocator
import com.xat.aegis.detect.RssiLocatorView
import com.xat.aegis.security.BiometricTripwire
import com.xat.aegis.security.BiometricTripwireResult
import com.xat.aegis.security.DuressPhrase
import com.xat.aegis.security.FakeCall
import com.xat.aegis.security.UnlockLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Private colour tokens (local to this file) ─────────────────────────────────

private val TGroundClr   = Color(0xFF0E1116)
private val TPanelClr    = Color(0xFF161B23)
private val TPanelHiClr  = Color(0xFF1D2430)
private val TInkClr      = Color(0xFFE6EAF1)
private val TInkDimClr   = Color(0xFFA8B2C1)
private val TMutedClr    = Color(0xFF6F7A8B)
private val TRuleClr     = Color(0xFF262E3A)
private val TAccentClr   = Color(0xFFFF7A3D)
private val TCriticalClr = Color(0xFFF2545B)
private val TCautionClr  = Color(0xFFE8B33D)
private val TClearClr    = Color(0xFF3DB88A)
private val TBlueClr     = Color(0xFF4A8FD4)
private val TOnAccentClr = Color(0xFF12161D)
private val TCardShape   = RoundedCornerShape(4.dp)

private val tFullFmt = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)
private val tDayFmt  = SimpleDateFormat("MMM d yyyy", Locale.US)
private val tTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun tFull(ts: Long): String = tFullFmt.format(Date(ts))
private fun tDay(ts: Long): String = tDayFmt.format(Date(ts))
private fun tTime(ts: Long): String = tTimeFmt.format(Date(ts))

private fun tSeverityColor(s: Severity): Color = when (s) {
    Severity.CRITICAL -> TCriticalClr
    Severity.HIGH -> TAccentClr
    Severity.MEDIUM -> TCautionClr
    Severity.LOW -> TMutedClr
}

/** Green at 0, yellow at 0.5, red at 1. */
private fun tMeterColor(t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return if (f <= 0.5f) lerp(TClearClr, TCautionClr, f * 2f) else lerp(TCautionClr, TCriticalClr, (f - 0.5f) * 2f)
}

// ── Pages ──────────────────────────────────────────────────────────────────────

/** The pages of the tools screen. Named apart from the objects they drive, so neither shadows the other. */
private sealed class ToolPage {
    data object Menu : ToolPage()
    data object Cameras : ToolPage()
    data object Stalkerware : ToolPage()
    data object Certificates : ToolPage()
    data object Pattern : ToolPage()
    data object Ledger : ToolPage()
    data object Call : ToolPage()
    data object Duress : ToolPage()
    data object Locate : ToolPage()
    data object SelfTest : ToolPage()
}

private class ToolEntry(val page: ToolPage, val glyph: String, val title: String, val description: String)

private val TOOL_ENTRIES = listOf(
    ToolEntry(ToolPage.Cameras, "◎", "Hidden camera sweep",
        "Lens glint, infrared, cameras on the Wi-Fi and a guided room check"),
    ToolEntry(ToolPage.Stalkerware, "⚠", "Stalkerware scan",
        "Apps that can read your screen, messages or location without showing themselves"),
    ToolEntry(ToolPage.Certificates, "§", "Certificates & TLS",
        "Which root certificates this phone trusts, and whether the relay connection is intercepted"),
    ToolEntry(ToolPage.Pattern, "⌁", "Pattern of life",
        "How predictable your movements look to someone watching"),
    ToolEntry(ToolPage.Ledger, "≣", "Unlock ledger",
        "Every unlock, failed attempt and PIN change, judged against your sleep window"),
    ToolEntry(ToolPage.Call, "☏", "Fake call",
        "A convincing incoming call in 30 seconds, 2 minutes or 5 minutes"),
    ToolEntry(ToolPage.Duress, "…", "Duress phrase",
        "An ordinary-looking sentence that silently sends an SOS when you type it"),
    ToolEntry(ToolPage.Locate, "◉", "Find a tracker",
        "Walk towards a detected tracker by signal strength, and make it beep"),
    ToolEntry(ToolPage.SelfTest, "↻", "Self-test beacon",
        "Pretend to be an AirTag for a minute and see whether the detector notices")
)

// ── ToolsScreen composable ─────────────────────────────────────────────────────

/**
 * The tools menu and the nine tool pages behind it. Replaces the whole tab tree
 * while open, like [SettingsScreen], so it keeps clear of the system bars itself.
 * The system back button steps a page back to the menu, then out.
 */
@Composable
fun ToolsScreen(onBack: () -> Unit) {
    var page by remember { mutableStateOf<ToolPage>(ToolPage.Menu) }
    val toMenu = { page = ToolPage.Menu }

    BackHandler { if (page == ToolPage.Menu) onBack() else page = ToolPage.Menu }

    Box(
        Modifier
            .fillMaxSize()
            .background(TGroundClr)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        when (page) {
            ToolPage.Menu -> ToolsMenu(onBack = onBack, onOpen = { page = it })
            ToolPage.Cameras -> CameraSweepScreen(onBack = toMenu)
            ToolPage.Stalkerware -> StalkerwarePage(onBack = toMenu)
            ToolPage.Certificates -> CertificatesPage(onBack = toMenu)
            ToolPage.Pattern -> PatternOfLifePage(onBack = toMenu)
            ToolPage.Ledger -> UnlockLedgerPage(onBack = toMenu)
            ToolPage.Call -> FakeCallPage(onBack = toMenu)
            ToolPage.Duress -> DuressPage(onBack = toMenu)
            ToolPage.Locate -> FindTrackerPage(onBack = toMenu)
            ToolPage.SelfTest -> SelfTestPage(onBack = toMenu)
        }
    }
}

@Composable
private fun ToolsMenu(onBack: () -> Unit, onOpen: (ToolPage) -> Unit) {
    ToolPageList(title = "TOOLS", onBack = onBack) {
        item(key = "§intro") {
            Text(
                "Checks and defences that run on demand. Nothing here leaves the phone.",
                color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
        items(TOOL_ENTRIES, key = { it.title }) { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(TCardShape)
                    .background(TPanelClr, TCardShape)
                    .clickable { onOpen(entry.page) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier.size(36.dp).background(TPanelHiClr, TCardShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(entry.glyph, color = TAccentClr, fontSize = 18.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(entry.title, color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(entry.description, color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp)
                }
                Text("›", color = TMutedClr, fontSize = 20.sp)
            }
        }
    }
}

// ── Shared building blocks ─────────────────────────────────────────────────────

@Composable
private fun ToolHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
            Text("‹ BACK", color = TAccentClr, fontSize = 13.sp, letterSpacing = 1.sp)
        }
        Text(
            title, color = TInkClr,
            fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(60.dp))
    }
}

/** The page skeleton every tool shares: the BACK header, a spaced list, a footer. */
@Composable
private fun ToolPageList(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "§header") { ToolHeader(title, onBack) }
        content()
        item(key = "§footer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TSectionLabel(text: String) {
    Text(
        text,
        color = TAccentClr, fontSize = 10.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun TCard(spacing: Int = 8, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(TPanelClr, TCardShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.dp),
        content = content
    )
}

@Composable
private fun TKv(label: String, value: String, valueColor: Color = TInkDimClr) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = TMutedClr, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
        Text(
            value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f), textAlign = TextAlign.End
        )
    }
}

@Composable
private fun TBadge(label: String, color: Color) {
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
private fun TNotice(color: Color, tag: String, text: String) {
    Row(
        Modifier.fillMaxWidth()
            .background(color.copy(alpha = 0.12f), TCardShape)
            .border(1.dp, color.copy(alpha = 0.5f), TCardShape)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(tag, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(text, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

private enum class TButtonStyle { PRIMARY, ACCENT, CRITICAL }

@Composable
private fun TButton(
    text: String,
    onClick: () -> Unit,
    style: TButtonStyle = TButtonStyle.ACCENT,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val colors = when (style) {
        TButtonStyle.PRIMARY -> ButtonDefaults.buttonColors(
            containerColor = TAccentClr, contentColor = TOnAccentClr,
            disabledContainerColor = TAccentClr.copy(alpha = 0.3f), disabledContentColor = TOnAccentClr.copy(alpha = 0.6f)
        )
        TButtonStyle.ACCENT -> ButtonDefaults.buttonColors(
            containerColor = TAccentClr.copy(alpha = 0.15f), contentColor = TAccentClr,
            disabledContainerColor = TAccentClr.copy(alpha = 0.06f), disabledContentColor = TAccentClr.copy(alpha = 0.4f)
        )
        TButtonStyle.CRITICAL -> ButtonDefaults.buttonColors(
            containerColor = TCriticalClr.copy(alpha = 0.15f), contentColor = TCriticalClr,
            disabledContainerColor = TCriticalClr.copy(alpha = 0.06f), disabledContentColor = TCriticalClr.copy(alpha = 0.4f)
        )
    }
    Button(onClick = onClick, colors = colors, shape = TCardShape, enabled = enabled, modifier = modifier) {
        Text(text, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
    }
}

@Composable
private fun TField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TMutedClr) },
        placeholder = placeholder?.let { { Text(it, color = TMutedClr.copy(alpha = 0.6f)) } },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TInkClr,
            unfocusedTextColor = TInkClr,
            focusedBorderColor = TAccentClr,
            unfocusedBorderColor = TRuleClr,
            cursorColor = TAccentClr
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier
    )
}

@Composable
private fun TProgress(text: String) {
    TCard {
        Text(text, color = TInkDimClr, fontSize = 13.sp)
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = TAccentClr, trackColor = TRuleClr
        )
    }
}

/** Wall-clock that ticks every [periodMs] so countdowns and "ago" times stay honest. */
@Composable
private fun rememberTick(periodMs: Long = 1000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private fun openExternal(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ── 2. Stalkerware ─────────────────────────────────────────────────────────────

@Composable
private fun StalkerwarePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val published by StalkwareScanner.results.collectAsStateWithLifecycle()
    var result by remember { mutableStateOf<StalkwareScanner.Result?>(published) }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var usageAccess by remember { mutableStateOf(StalkwareScanner.hasUsageAccess(context)) }

    fun runScan() {
        if (scanning) return
        scanning = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { StalkwareScanner.scan(context) } }
            r.onSuccess { result = it }.onFailure { error = "The scan failed: ${it.javaClass.simpleName}: ${it.message}" }
            scanning = false
        }
    }

    LaunchedEffect(Unit) { if (result == null) runScan() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Usage access is granted on Android's own page, so it is re-read on return.
        usageAccess = StalkwareScanner.hasUsageAccess(context)
    }

    ToolPageList(title = "STALKERWARE", onBack = onBack) {
        if (!usageAccess) {
            item(key = "§usage") {
                TCard {
                    Text("Usage access not granted", color = TCautionClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "With usage access Aegis can also see which apps run a hidden foreground service " +
                        "without ever being opened — the strongest single sign of stalkerware. Without it " +
                        "that signal is skipped.",
                        color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                    )
                    TButton("GRANT USAGE ACCESS", onClick = { openExternal(context, StalkwareScanner.usageAccessIntent()) })
                }
            }
        }

        if (scanning) {
            item(key = "§progress") { TProgress("Scanning installed apps…") }
        } else {
            item(key = "§rescan") { TButton("SCAN AGAIN", onClick = { runScan() }) }
        }

        error?.let { e -> item(key = "§error") { TNotice(TCriticalClr, "ERROR", e) } }

        val r = result
        if (r != null) {
            item(key = "§summary") {
                TCard(spacing = 4) {
                    TKv("Scanned", tFull(r.scannedAt))
                    TKv("Flagged", "${r.high.size} high · ${r.medium.size} medium")
                    TKv("Notification access", "${r.notificationListeners.size} ${if (r.notificationListeners.size == 1) "app" else "apps"}")
                    TKv("Accessibility services", "${r.accessibilityServices.size}")
                    TKv("VPN-capable apps", "${r.vpnApps.size}")
                }
            }
            if (r.high.isEmpty() && r.medium.isEmpty()) {
                item(key = "§clean") {
                    TCard {
                        Text("No suspicious apps found", color = TClearClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Nothing installed scores high enough to worry about. A clean result is not " +
                            "proof: stalkerware sold as a 'parental control' app can look like a store install.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                        )
                    }
                }
            }
            if (r.high.isNotEmpty()) {
                item(key = "§high-hdr") { TSectionLabel("HIGH") }
                items(r.high, key = { "high|${it.packageName}" }) { SuspiciousAppCard(it, context) }
            }
            if (r.medium.isNotEmpty()) {
                item(key = "§medium-hdr") { TSectionLabel("MEDIUM") }
                items(r.medium, key = { "medium|${it.packageName}" }) { SuspiciousAppCard(it, context) }
            }
        }
    }
}

@Composable
private fun SuspiciousAppCard(app: StalkwareScanner.SuspiciousApp, context: Context) {
    TCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(app.appLabel, color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(app.packageName, color = TMutedClr, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            TBadge("${app.severity.name} · ${app.score}", tSeverityColor(app.severity))
        }
        TKv("Installed", tDay(app.installTs))
        TKv("Installer", app.installerPackage ?: if (app.sideloaded) "unknown (sideloaded)" else "—")
        if (app.reasons.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (reason in app.reasons) {
                    Text("• $reason", color = TInkDimClr, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
        TButton("OPEN IN SETTINGS", onClick = { openExternal(context, StalkwareScanner.appDetailsIntent(app.packageName)) })
    }
}

// ── 3. Certificates & TLS ──────────────────────────────────────────────────────

@Composable
private fun CertificatesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cas by remember { mutableStateOf<List<SuspiciousCa>?>(null) }
    var tls by remember { mutableStateOf<TlsCanaryResult?>(null) }
    var last by remember { mutableStateOf(TlsCanary.lastResult(context)) }
    var pinned by remember { mutableStateOf(TlsCanary.pinned(context)) }
    var checking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        cas = withContext(Dispatchers.IO) { runCatching { CaAuditScanner.scan(context) }.getOrDefault(emptyList()) }
    }

    fun checkNow() {
        if (checking) return
        checking = true
        scope.launch {
            runCatching { TlsCanary.check(context).collect { tls = it } }
                .onFailure { tls = TlsCanaryResult(intercepted = false, method = null, proxyHost = null, error = "Check failed: ${it.message}") }
            last = TlsCanary.lastResult(context)
            pinned = TlsCanary.pinned(context)
            checking = false
        }
    }

    ToolPageList(title = "CERTIFICATES & TLS", onBack = onBack) {
        item(key = "§tls-hdr") { TSectionLabel("TLS CANARY") }
        item(key = "§tls") {
            val r = tls
            TCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Relay connection", color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Opens TLS to the comms relay and compares the server's key with the one pinned " +
                            "on first contact. An intercepting proxy cannot present the same key.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                    when {
                        r == null -> Unit
                        r.intercepted -> TBadge("INTERCEPTED", TCriticalClr)
                        r.error != null && r.observedSpki == null -> TBadge("NO RESULT", TCautionClr)
                        r.pinnedNow -> TBadge("PINNED", TBlueClr)
                        else -> TBadge("CLEAR", TClearClr)
                    }
                }
                val lastRun = last
                if (lastRun != null) {
                    TKv("Last result", lastRun.second, if (lastRun.second.startsWith("INTERCEPTED")) TCriticalClr else TInkDimClr)
                    if (lastRun.first > 0) TKv("Checked", tFull(lastRun.first))
                } else {
                    TKv("Last result", "never run")
                }
                pinned?.let { (host, pin) -> TKv("Pinned ($host)", pin.take(24) + "…") }
                if (r != null) {
                    r.relayHost?.let { TKv("Relay", it) }
                    r.observedSpki?.let { TKv("Observed key", it.take(24) + "…") }
                    r.observedIssuer?.let { TKv("Issuer", it) }
                    r.chainTrusted?.let { TKv("Chain trusted", if (it) "yes" else "no", if (it) TClearClr else TCriticalClr) }
                    r.proxyHost?.let { TKv("Proxy", it, TCautionClr) }
                    r.method?.let { Text(it, color = if (r.intercepted) TCriticalClr else TInkDimClr, fontSize = 12.sp, lineHeight = 16.sp) }
                    r.error?.let { Text(it, color = TCautionClr, fontSize = 12.sp, lineHeight = 16.sp) }
                }
                if (checking) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = TAccentClr, trackColor = TRuleClr)
                } else {
                    TButton("CHECK NOW", onClick = { checkNow() })
                }
                if (pinned != null) {
                    TextButton(
                        onClick = { TlsCanary.resetPin(context); pinned = null },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("FORGET PIN (RELAY ROTATED ITS KEY)", color = TMutedClr, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }

        item(key = "§ca-hdr") { TSectionLabel("TRUST STORE AUDIT") }
        val list = cas
        when {
            list == null -> item(key = "§ca-progress") { TProgress("Reading the trust store…") }
            list.isEmpty() -> item(key = "§ca-clean") {
                TCard {
                    Text("No unexpected root certificates", color = TClearClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Nothing was installed by hand, and every system root is one Android ships with. " +
                        "A hand-installed root is how HTTPS traffic is read through a proxy by someone " +
                        "who has had the phone and its PIN.",
                        color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                    )
                }
            }
            else -> items(list, key = { "ca|${it.fingerprint}" }) { ca ->
                TCard(spacing = 4) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ca.subjectCn, color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        TBadge(ca.source.uppercase(Locale.US), tSeverityColor(ca.severity))
                    }
                    TKv("Issuer", CaAuditScanner.cn(ca.issuerDn) ?: ca.issuerDn)
                    TKv("SHA-256", ca.fingerprint.take(23) + "…")
                    TKv("Valid", "${tDay(ca.validFrom)} – ${tDay(ca.validTo)}")
                    if (ca.reason.isNotBlank()) {
                        Text(ca.reason, color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

// ── 4. Pattern of life ─────────────────────────────────────────────────────────

@Composable
private fun PatternOfLifePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<PatternReport?>(null) }
    var fixes by remember { mutableIntStateOf(0) }
    var analysing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showClear by remember { mutableStateOf(false) }

    fun analyse() {
        if (analysing) return
        analysing = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    fixes = LocationHistory.size(context)
                    PatternOfLife.analyze(context)
                }
            }
            r.onSuccess { report = it }.onFailure { error = "Analysis failed: ${it.javaClass.simpleName}: ${it.message}" }
            analysing = false
        }
    }

    LaunchedEffect(Unit) { analyse() }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            containerColor = TPanelClr,
            title = { Text("Clear location history?", color = TInkClr, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This deletes the $fixes stored GPS fixes the route analysis reads. Detections and " +
                    "the timeline are not affected. This cannot be undone.",
                    color = TInkDimClr, fontSize = 13.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { LocationHistory.clear(context) } }
                        fixes = 0
                        report = null
                        analyse()
                    }
                }) {
                    Text("CLEAR", color = TCriticalClr, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) {
                    Text("CANCEL", color = TMutedClr, letterSpacing = 1.sp)
                }
            }
        )
    }

    ToolPageList(title = "PATTERN OF LIFE", onBack = onBack) {
        if (analysing) item(key = "§progress") { TProgress("Analysing $fixes fixes — stops, departures and routes…") }
        error?.let { e -> item(key = "§error") { TNotice(TCriticalClr, "ERROR", e) } }

        val r = report
        if (r != null && !analysing) {
            if (r.fixes == 0 || r.clusters.isEmpty()) {
                item(key = "§empty") {
                    TCard {
                        Text("Not enough history yet", color = TInkDimClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "The analysis needs a few days of fixes with the scanner running and a GPS lock. " +
                            "Stops are found from the fixes alone; nothing is sent anywhere.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                        )
                        TKv("Fixes recorded", "$fixes")
                        TKv("Days covered", "${r.daysCovered}")
                    }
                }
            } else {
                item(key = "§predict") {
                    val score = r.predictabilityScore.coerceIn(0f, 1f)
                    val label = when {
                        score < 0.25f -> "Low"
                        score < 0.5f -> "Moderate"
                        score < 0.75f -> "High"
                        else -> "Clockwork"
                    }
                    TCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Predictability", color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "$label · ${(score * 100).toInt()}%", color = tMeterColor(score),
                                fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(TRuleClr)) {
                            Box(Modifier.fillMaxWidth(score.coerceAtLeast(0.02f)).fillMaxHeight().background(tMeterColor(score)))
                        }
                        Text(
                            "How well someone who watched for a week could guess where you will be and when. " +
                            "0 is unpredictable, 100 is clockwork.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                        )
                        TKv(
                            "Weekday departure window",
                            if (r.weekdayDepartureWindowMin > 0) "${r.weekdayDepartureWindowMin} min" else "too few to say"
                        )
                        TKv("Route repetition", if (r.trips > 0) "%.0f%%".format(Locale.US, r.routeRepetitionPct) else "no trips yet")
                        TKv("Trips between places", "${r.trips}")
                        TKv("Days covered", "${r.daysCovered}")
                        TKv("Fixes analysed", "${r.fixes}")
                    }
                }

                item(key = "§places-hdr") { TSectionLabel("PLACES") }
                items(r.clusters, key = { "place|${it.id}" }) { c ->
                    TCard(spacing = 4) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.label, color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(
                                "%.5f, %.5f".format(Locale.US, c.lat, c.lon),
                                color = TMutedClr, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                        TKv("Visits", "${c.visits} on ${c.days} ${if (c.days == 1) "day" else "days"}")
                        TKv("Time here", "${c.dwellMinutes / 60} h ${c.dwellMinutes % 60} min")
                        TKv("Seen", "${tDay(c.firstSeen)} – ${tDay(c.lastSeen)}")
                        if (c.departures.isNotEmpty()) {
                            Text("Usual departures", color = TMutedClr, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            for (d in c.departures) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(d.label, color = TInkDimClr, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    Text("${d.samples}×", color = TMutedClr, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }

                if (r.suggestions.isNotEmpty()) {
                    item(key = "§suggest-hdr") { TSectionLabel("SUGGESTIONS") }
                    item(key = "§suggest") {
                        TCard(spacing = 6) {
                            for (s in r.suggestions) {
                                Text("• $s", color = TInkDimClr, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                        }
                    }
                }
            }
        }

        item(key = "§data-hdr") { TSectionLabel("DATA") }
        item(key = "§data") {
            TCard {
                TKv("Fixes recorded", "$fixes of ${LocationHistory.CAP}")
                Text(
                    "The GPS log the analysis reads. It stays on this phone and is also removed by " +
                    "CLEAR ALL DATA in Settings.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                )
                TButton("ANALYSE AGAIN", onClick = { analyse() }, enabled = !analysing)
                TButton("CLEAR HISTORY", onClick = { showClear = true }, style = TButtonStyle.CRITICAL, enabled = fixes > 0 && !analysing)
            }
        }
    }
}

// ── 5. Unlock ledger ───────────────────────────────────────────────────────────

private const val LEDGER_WINDOW_MS = 7L * 24 * 3600_000L
private const val LEDGER_MAX_ROWS = 200

private fun unlockGlyph(kind: UnlockKind): String = when (kind) {
    UnlockKind.PASSWORD_SUCCEEDED -> "✓"
    UnlockKind.PASSWORD_FAILED -> "✗"
    UnlockKind.PASSWORD_CHANGED -> "✎"
    UnlockKind.KEYGUARD_HIDDEN -> "○"
    UnlockKind.KEYGUARD_SHOWN -> "●"
    UnlockKind.SCREEN_INTERACTIVE -> "☼"
    UnlockKind.SCREEN_NON_INTERACTIVE -> "☾"
    UnlockKind.ADMIN_ENABLED -> "+"
    UnlockKind.ADMIN_DISABLED -> "−"
}

private fun unlockLabel(kind: UnlockKind): String = when (kind) {
    UnlockKind.PASSWORD_SUCCEEDED -> "Unlocked with credential"
    UnlockKind.PASSWORD_FAILED -> "Failed unlock attempt"
    UnlockKind.PASSWORD_CHANGED -> "Screen lock credential changed"
    UnlockKind.KEYGUARD_HIDDEN -> "Unlocked"
    UnlockKind.KEYGUARD_SHOWN -> "Locked"
    UnlockKind.SCREEN_INTERACTIVE -> "Screen on"
    UnlockKind.SCREEN_NON_INTERACTIVE -> "Screen off"
    UnlockKind.ADMIN_ENABLED -> "Ledger device admin enabled"
    UnlockKind.ADMIN_DISABLED -> "Ledger device admin disabled"
}

@Composable
private fun UnlockLedgerPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adminActive by remember { mutableStateOf(UnlockLedger.isAdminActive(context)) }
    var usageAccess by remember { mutableStateOf(UnlockLedger.hasUsageAccess(context)) }
    var events by remember { mutableStateOf<List<UnlockEvent>?>(null) }
    var sleepStart by remember { mutableStateOf(UnlockLedger.sleepStartMinutes(context).toString()) }
    var sleepEnd by remember { mutableStateOf(UnlockLedger.sleepEndMinutes(context).toString()) }
    var sleepError by remember { mutableStateOf<String?>(null) }
    var sleepSaved by remember { mutableStateOf(false) }
    var tripwire by remember { mutableStateOf<BiometricTripwireResult?>(null) }
    val recent by UnlockLedger.recentFlow.collectAsStateWithLifecycle()

    fun reload() {
        scope.launch {
            events = withContext(Dispatchers.IO) {
                runCatching {
                    UnlockLedger.init(context)
                    UnlockLedger.backfill(context)
                    UnlockLedger.events(System.currentTimeMillis() - LEDGER_WINDOW_MS).take(LEDGER_MAX_ROWS)
                }.getOrDefault(emptyList())
            }
        }
    }

    LaunchedEffect(Unit) {
        reload()
        tripwire = withContext(Dispatchers.IO) {
            runCatching { BiometricTripwire.check(context) }
                .getOrElse { BiometricTripwireResult.UNAVAILABLE("Could not test the key (${it.javaClass.simpleName})") }
        }
    }
    // A new row landing (admin callback or a backfill elsewhere) refreshes the list.
    LaunchedEffect(recent?.id) { if (events != null) reload() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Both grants happen on Android's own pages, so they are re-read on return.
        adminActive = UnlockLedger.isAdminActive(context)
        usageAccess = UnlockLedger.hasUsageAccess(context)
    }

    val startMin = sleepStart.toIntOrNull()
    val endMin = sleepEnd.toIntOrNull()

    ToolPageList(title = "UNLOCK LEDGER", onBack = onBack) {
        item(key = "§sources-hdr") { TSectionLabel("SOURCES") }
        item(key = "§sources") {
            TCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Device admin", color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Reports every successful and failed unlock and any PIN change as it happens. " +
                            "Aegis never locks, wipes or sets a policy with it.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                    TBadge(if (adminActive) "ACTIVE" else "OFF", if (adminActive) TClearClr else TCautionClr)
                }
                if (!adminActive) {
                    TButton("ENABLE DEVICE ADMIN", onClick = { openExternal(context, UnlockLedger.requestAdminIntent(context)) })
                }
                HorizontalDivider(color = TRuleClr, thickness = 0.5.dp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Usage access", color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Lets the ledger read the platform's own lock, unlock and screen events for the " +
                            "last few days, including from before the admin was enabled.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                    TBadge(if (usageAccess) "GRANTED" else "OFF", if (usageAccess) TClearClr else TCautionClr)
                }
                if (!usageAccess) {
                    TButton("GRANT USAGE ACCESS", onClick = { openExternal(context, UnlockLedger.usageAccessIntent()) })
                }
            }
        }

        item(key = "§tripwire") {
            val t = tripwire
            TCard(spacing = 4) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Biometric tripwire", color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "A Keystore key that dies the moment a fingerprint or face is added or removed.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                    when (t) {
                        null -> TBadge("CHECKING", TMutedClr)
                        BiometricTripwireResult.OK -> TBadge("INTACT", TClearClr)
                        BiometricTripwireResult.FIRST_INIT -> TBadge("ARMED", TBlueClr)
                        is BiometricTripwireResult.TRIGGERED -> TBadge("TRIGGERED", TCriticalClr)
                        is BiometricTripwireResult.UNAVAILABLE -> TBadge("UNAVAILABLE", TCautionClr)
                    }
                }
                when (t) {
                    is BiometricTripwireResult.TRIGGERED ->
                        Text("The enrolled biometrics changed; noticed ${tFull(t.ts)}.", color = TCriticalClr, fontSize = 12.sp, lineHeight = 16.sp)
                    is BiometricTripwireResult.UNAVAILABLE ->
                        Text(t.reason, color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp)
                    BiometricTripwireResult.FIRST_INIT ->
                        Text("Armed just now against the biometrics enrolled at this moment.", color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp)
                    else -> Unit
                }
            }
        }

        item(key = "§sleep-hdr") { TSectionLabel("SLEEP WINDOW") }
        item(key = "§sleep") {
            TCard {
                Text(
                    "An unlock inside this window is reported as an anomaly. Times are minutes after " +
                    "midnight (23:00 is 1380, 07:00 is 420); the window may cross midnight.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TField(
                        value = sleepStart,
                        onValueChange = { sleepStart = it.filter { c -> c.isDigit() }.take(4); sleepError = null; sleepSaved = false },
                        label = "Start (min)", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f)
                    )
                    TField(
                        value = sleepEnd,
                        onValueChange = { sleepEnd = it.filter { c -> c.isDigit() }.take(4); sleepError = null; sleepSaved = false },
                        label = "End (min)", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f)
                    )
                }
                TKv(
                    "Window",
                    if (startMin != null && endMin != null && startMin in 0 until 1440 && endMin in 0 until 1440)
                        "${clock(startMin)} – ${clock(endMin)}"
                    else "—"
                )
                sleepError?.let { Text(it, color = TCriticalClr, fontSize = 12.sp) }
                if (sleepSaved) Text("Saved", color = TClearClr, fontSize = 12.sp)
                TButton("SAVE SLEEP WINDOW", onClick = {
                    if (startMin == null || endMin == null || startMin !in 0 until 1440 || endMin !in 0 until 1440) {
                        sleepError = "Both values must be between 0 and 1439"
                    } else {
                        UnlockLedger.setSleepWindow(context, startMin, endMin)
                        sleepSaved = true
                    }
                })
            }
        }

        item(key = "§events-hdr") { TSectionLabel("LAST 7 DAYS") }
        val list = events
        when {
            list == null -> item(key = "§events-progress") { TProgress("Reading the ledger…") }
            list.isEmpty() -> item(key = "§events-empty") {
                TCard {
                    Text("No events recorded yet", color = TInkDimClr, fontSize = 13.sp)
                    Text(
                        "Rows appear once the device admin is active or usage access is granted and the " +
                        "phone has been locked and unlocked.",
                        color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                    )
                }
            }
            else -> items(list, key = { "ev|${it.id}" }) { e ->
                val anomaly = e.kind.isUnlock && UnlockLedger.inSleepWindow(e.ts)
                val tone = when {
                    anomaly -> TCautionClr
                    e.kind == UnlockKind.PASSWORD_FAILED || e.kind == UnlockKind.PASSWORD_CHANGED || e.kind == UnlockKind.ADMIN_DISABLED -> TCriticalClr
                    e.kind.isUnlock -> TInkClr
                    else -> TMutedClr
                }
                Row(
                    Modifier.fillMaxWidth().background(TPanelClr, TCardShape).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(unlockGlyph(e.kind), color = tone, fontSize = 16.sp, modifier = Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(unlockLabel(e.kind) + if (anomaly) " · in sleep window" else "", color = tone, fontSize = 13.sp)
                        Text(
                            tFull(e.ts) + (e.extra?.let { "  ·  $it" } ?: ""),
                            color = TMutedClr, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

// ── 6. Fake call ───────────────────────────────────────────────────────────────

@Composable
private fun FakeCallPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { FakeCall.caller(context) }
    var name by remember { mutableStateOf(initial.name) }
    var number by remember { mutableStateOf(initial.number ?: "") }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var connectTo by remember { mutableStateOf<Contact?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var canExact by remember { mutableStateOf(FakeCall.canScheduleExactly(context)) }
    var pendingAt by remember { mutableStateOf(FakeCall.pendingAt(context)) }
    val now = rememberTick()

    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) { runCatching { CommsRepository.contacts() }.getOrDefault(emptyList()) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canExact = FakeCall.canScheduleExactly(context)
        pendingAt = FakeCall.pendingAt(context)
    }
    // A pending call that has rung drops off the countdown.
    LaunchedEffect(now) {
        val at = pendingAt
        if (at != null && at <= now) pendingAt = null
    }

    fun schedule(delayMs: Long) {
        FakeCall.setCaller(context, name.ifBlank { "Mum" }, number.ifBlank { null })
        FakeCall.schedule(context, delayMs, connectTo?.number)
        pendingAt = FakeCall.pendingAt(context)
    }

    ToolPageList(title = "FAKE CALL", onBack = onBack) {
        item(key = "§intro") {
            Text(
                "A way out of a conversation or a situation that is going wrong. The phone rings with " +
                "its own ringtone and shows an incoming call from whoever you name here.",
                color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
            )
        }

        val pending = pendingAt
        if (pending != null) {
            item(key = "§pending") {
                val secs = ((pending - now) / 1000L).coerceAtLeast(0L)
                TCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Rings in ${secs / 60}:${"%02d".format(Locale.US, secs % 60)}", color = TInkClr, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(
                                "Put the phone away; it will ring on its own." +
                                    (connectTo?.let { " Answering calls ${it.name} for real." } ?: ""),
                                color = TMutedClr, fontSize = 12.sp
                            )
                        }
                        TBadge("SCHEDULED", TAccentClr)
                    }
                    TButton("CANCEL", onClick = { FakeCall.cancel(context); pendingAt = null }, style = TButtonStyle.CRITICAL)
                }
            }
        }

        item(key = "§caller-hdr") { TSectionLabel("CALLER") }
        item(key = "§caller") {
            TCard {
                TField(value = name, onValueChange = { name = it.take(40) }, label = "Name shown", placeholder = "Mum")
                TField(value = number, onValueChange = { number = it.take(24) }, label = "Number shown (optional)", keyboardType = KeyboardType.Phone)
                Text(
                    "Saved when a call is scheduled. The name and number are only what appears on the " +
                    "screen; no real call is made unless you pick a contact below.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                )
            }
        }

        item(key = "§connect-hdr") { TSectionLabel("ON ANSWER") }
        item(key = "§connect") {
            TCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Connect to a real contact", color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "Answering places an encrypted Aegis call to this contact instead of a pretend " +
                            "one. Leave empty to talk to nobody.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Box {
                        TextButton(
                            onClick = { pickerOpen = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = TAccentClr),
                            shape = TCardShape
                        ) {
                            Text(connectTo?.name ?: "Nobody", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Nobody", fontWeight = if (connectTo == null) FontWeight.Bold else FontWeight.Normal) },
                                onClick = { pickerOpen = false; connectTo = null }
                            )
                            for (c in contacts) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(c.name, fontWeight = if (connectTo?.number == c.number) FontWeight.Bold else FontWeight.Normal)
                                            Text(formatAegisNumber(c.number), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TMutedClr)
                                        }
                                    },
                                    onClick = { pickerOpen = false; connectTo = c }
                                )
                            }
                        }
                    }
                }
                if (contacts.isEmpty()) {
                    Text("No Aegis contacts yet — add one on the COMMS tab.", color = TMutedClr, fontSize = 12.sp)
                }
            }
        }

        item(key = "§when-hdr") { TSectionLabel("RING IN") }
        item(key = "§when") {
            TCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TButton("30 S", onClick = { schedule(FakeCall.DELAY_30S) }, style = TButtonStyle.PRIMARY, modifier = Modifier.weight(1f))
                    TButton("2 MIN", onClick = { schedule(FakeCall.DELAY_2MIN) }, style = TButtonStyle.PRIMARY, modifier = Modifier.weight(1f))
                    TButton("5 MIN", onClick = { schedule(FakeCall.DELAY_5MIN) }, style = TButtonStyle.PRIMARY, modifier = Modifier.weight(1f))
                }
                if (!canExact) {
                    Text(
                        "Exact alarms are not allowed for Aegis, so Android may ring a little late while " +
                        "the phone is idle. Allow them for the call to land on the second.",
                        color = TCautionClr, fontSize = 12.sp, lineHeight = 16.sp
                    )
                    TButton("ALLOW EXACT ALARMS", onClick = { FakeCall.requestExactAlarms(context) })
                } else {
                    Text("Exact alarms allowed — the call rings on time even with the screen off.", color = TMutedClr, fontSize = 12.sp)
                }
            }
        }
    }
}

// ── 7. Duress phrase ───────────────────────────────────────────────────────────

@Composable
private fun DuressPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var configured by remember { mutableStateOf(DuressPhrase.isConfigured(context)) }
    var phrase by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            containerColor = TPanelClr,
            title = { Text("Remove the duress phrase?", color = TInkClr, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Typing the phrase into a conversation will no longer send an SOS.",
                    color = TInkDimClr, fontSize = 13.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    DuressPhrase.clear(context)
                    configured = false
                    message = "Duress phrase removed"
                    messageIsError = false
                }) {
                    Text("REMOVE", color = TCriticalClr, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) {
                    Text("CANCEL", color = TMutedClr, letterSpacing = 1.sp)
                }
            }
        )
    }

    ToolPageList(title = "DURESS PHRASE", onBack = onBack) {
        item(key = "§status") {
            TCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            if (configured) "Duress phrase is set" else "No duress phrase",
                            color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "An ordinary-looking sentence you can type into any Aegis conversation. The " +
                            "message goes out as written, so anyone watching the screen sees a normal chat; " +
                            "underneath, the SOS fires to your groups with your last known position.",
                            color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                    TBadge(if (configured) "CONFIGURED" else "NOT SET", if (configured) TClearClr else TMutedClr)
                }
            }
        }

        item(key = "§set-hdr") { TSectionLabel(if (configured) "REPLACE PHRASE" else "SET PHRASE") }
        item(key = "§set") {
            TCard {
                TField(
                    value = phrase,
                    onValueChange = { phrase = it.take(120); message = null },
                    label = "New phrase",
                    placeholder = "e.g. did you remember to feed the cat"
                )
                Text(
                    "Pick something you would plausibly say but never would by accident. Matching ignores " +
                    "case and extra spaces. The phrase is stored encrypted and is never shown again — " +
                    "not here, not in the timeline, not in a notification.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                )
                message?.let { Text(it, color = if (messageIsError) TCriticalClr else TClearClr, fontSize = 12.sp) }
                TButton(
                    if (configured) "REPLACE PHRASE" else "SAVE PHRASE",
                    onClick = {
                        val value = phrase
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { DuressPhrase.set(context, value) } }
                            r.onSuccess {
                                phrase = ""
                                configured = DuressPhrase.isConfigured(context)
                                message = if (configured) "Saved" else "A blank phrase clears the setting"
                                messageIsError = false
                            }.onFailure {
                                message = "Could not save: ${it.message ?: it.javaClass.simpleName}"
                                messageIsError = true
                            }
                        }
                    },
                    style = TButtonStyle.PRIMARY,
                    enabled = phrase.isNotBlank()
                )
                if (configured) {
                    TButton("CLEAR PHRASE", onClick = { showClear = true }, style = TButtonStyle.CRITICAL)
                }
            }
        }

        item(key = "§how") {
            TCard(spacing = 6) {
                Text("How it works", color = TInkClr, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "• Type the phrase as a message to any contact or group and send it as usual.\n" +
                    "• The message is delivered normally, so the conversation looks unremarkable.\n" +
                    "• At the same time an SOS goes to all your groups with your last known location, " +
                    "and the timeline records only that an SOS was triggered — never the phrase itself.\n" +
                    "• Nothing on screen changes. Carry on as if nothing happened.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                )
            }
        }
    }
}

// ── 8. Find a tracker ──────────────────────────────────────────────────────────

@Composable
private fun FindTrackerPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val detections by Registry.detections.collectAsStateWithLifecycle()
    val trusted by Registry.trusted.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    var selectedKey by remember { mutableStateOf<String?>(null) }

    val ordered = remember(detections, trusted) {
        detections
            .filter { it.address !in trusted }
            .sortedWith(compareByDescending<Detection> { it.following }.thenByDescending { it.threat.ordinal }.thenByDescending { it.rssi })
    }
    val selected = ordered.firstOrNull { it.key == selectedKey }

    // Back from the locator goes to the list first, then the menu.
    BackHandler(enabled = selected != null) { selectedKey = null }

    if (selected != null) {
        TrackerLocator(detection = selected, onBack = { selectedKey = null })
        return
    }

    ToolPageList(title = "FIND A TRACKER", onBack = onBack) {
        if (!status.scanning) {
            item(key = "§not-scanning") {
                TNotice(TCautionClr, "SCANNER OFF", "The locator listens to the scanner's packets. Start scanning on the SCAN tab first.")
            }
        }
        item(key = "§intro") {
            Text(
                "Pick a detection, then hold the phone in front of you and turn slowly. The signal is " +
                "strongest when the phone faces the tracker and weakest when your body is in the way.",
                color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
        if (ordered.isEmpty()) {
            item(key = "§empty") {
                TCard {
                    Text("Nothing detected right now", color = TInkDimClr, fontSize = 13.sp)
                    Text("Detections appear here as the scanner finds them; trusted devices are left out.", color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        } else {
            items(ordered, key = { "det|${it.key}" }) { d ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(TCardShape)
                        .background(TPanelClr, TCardShape)
                        .clickable { selectedKey = d.key }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.tracker?.label ?: d.name, color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            d.dultLabel ?: d.summary, color = TMutedClr, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(d.address, color = TMutedClr, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (d.following) TBadge("FOLLOWING", TCriticalClr)
                        else TBadge(d.threat.name, when (d.threat) {
                            Threat.CRITICAL -> TCriticalClr; Threat.HIGH -> TAccentClr
                            Threat.MEDIUM -> TCautionClr; Threat.LOW -> TMutedClr; Threat.NONE -> TClearClr
                        })
                        Text(
                            if (d.rssi == 0) "— dBm" else "${d.rssi} dBm",
                            color = TInkDimClr, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    Text("›", color = TMutedClr, fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun TrackerLocator(detection: Detection, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Registry.status.collectAsStateWithLifecycle()
    val flow = remember(detection.address) { RssiLocator.track(detection.address, context) }
    val state by flow.collectAsStateWithLifecycle<LocatorState?>(initialValue = null)

    var soundJob by remember { mutableStateOf<Job?>(null) }
    var soundState by remember { mutableStateOf<DultResult?>(null) }
    var soundBusy by remember { mutableStateOf(false) }
    val canBeep = detection.dult?.answered == true

    fun sound(start: Boolean) {
        soundJob?.cancel()
        soundBusy = true
        soundState = null
        soundJob = scope.launch {
            val device = runCatching {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(detection.address)
            }.getOrNull()
            if (device == null) {
                soundState = DultResult(detection.address, error = "Bluetooth is unavailable or the address is malformed", complete = true)
                soundBusy = false
                return@launch
            }
            runCatching { DultInterrogator.sound(device, context, start).collect { soundState = it } }
                .onFailure { soundState = DultResult(detection.address, error = it.message ?: it.javaClass.simpleName, complete = true) }
            soundBusy = false
        }
    }

    ToolPageList(title = detection.tracker?.label?.uppercase(Locale.US) ?: "LOCATE", onBack = onBack) {
        item(key = "§who") {
            TCard(spacing = 4) {
                Text(detection.tracker?.label ?: detection.name, color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                detection.dultLabel?.let { Text(it, color = TInkDimClr, fontSize = 12.sp) }
                TKv("Address", detection.address)
                TKv("Last RSSI", if (detection.rssi == 0) "—" else "${detection.rssi} dBm")
                if (detection.following) TKv("Status", "FOLLOWING", TCriticalClr)
            }
        }
        if (!status.scanning) {
            item(key = "§not-scanning") {
                TNotice(TCautionClr, "SCANNER OFF", "No packets arrive while the scanner is stopped. Start it on the SCAN tab.")
            }
        }
        item(key = "§dial") {
            val s = state
            if (s == null) {
                TProgress("Listening for ${detection.address}…")
            } else {
                Column(Modifier.fillMaxWidth().background(TPanelClr, TCardShape).padding(12.dp)) {
                    RssiLocatorView(state = s, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    TKv("Samples", "${s.samples}")
                    TKv("Confidence", "${(s.confidence * 100).toInt()}%")
                    s.lastRssi?.let { TKv("Last RSSI", "$it dBm") }
                    s.lastSeenAgoMs?.let { TKv("Last heard", if (it < 1000) "just now" else "${it / 1000} s ago") }
                }
            }
        }
        item(key = "§beep") {
            val r = soundState
            TCard {
                Text("Make it beep", color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (canBeep)
                        "This tracker answered over the DULT non-owner service, so it is away from its owner " +
                        "and will play a sound on request."
                    else
                        "Only a tracker that has answered over the DULT non-owner service can be asked to " +
                        "sound. This one has not — it may still be with its owner, or not support DULT.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 16.sp
                )
                if (r != null) {
                    when {
                        r.error != null -> Text(r.error, color = TCautionClr, fontSize = 12.sp)
                        r.soundStarted -> Text("Beeping — follow the sound.", color = TClearClr, fontSize = 12.sp)
                        r.complete -> Text("Command sent.", color = TInkDimClr, fontSize = 12.sp)
                    }
                }
                if (soundBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = TAccentClr, trackColor = TRuleClr)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TButton("MAKE IT BEEP", onClick = { sound(true) }, style = TButtonStyle.PRIMARY, enabled = canBeep && !soundBusy, modifier = Modifier.weight(1f))
                    TButton("STOP SOUND", onClick = { sound(false) }, enabled = canBeep && !soundBusy, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ── 9. Self-test beacon ────────────────────────────────────────────────────────

private const val CANARY_DURATION_MS = 60_000L

private fun canaryLabel(s: BleCanaryState): String = when (s) {
    BleCanaryState.Starting -> "Starting the advertiser"
    is BleCanaryState.Advertising -> "Advertising as a separated AirTag"
    is BleCanaryState.Detected -> "DETECTED by the scanner at ${s.rssi} dBm (${s.address})"
    BleCanaryState.Stopped -> "Stopped — the minute is up"
    is BleCanaryState.Error -> "Error: ${s.message}"
}

private fun canaryColor(s: BleCanaryState): Color = when (s) {
    BleCanaryState.Starting -> TMutedClr
    is BleCanaryState.Advertising -> TBlueClr
    is BleCanaryState.Detected -> TClearClr
    BleCanaryState.Stopped -> TInkDimClr
    is BleCanaryState.Error -> TCriticalClr
}

@Composable
private fun SelfTestPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Registry.status.collectAsStateWithLifecycle()
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var transitions by remember { mutableStateOf<List<Pair<Long, BleCanaryState>>>(emptyList()) }
    val now = rememberTick(500L)

    fun start() {
        job?.cancel()
        transitions = emptyList()
        running = true
        startedAt = System.currentTimeMillis()
        job = scope.launch {
            runCatching {
                BleCanary.start(context, CANARY_DURATION_MS).collect { state ->
                    transitions = transitions + (System.currentTimeMillis() to state)
                }
            }.onFailure { e ->
                transitions = transitions + (System.currentTimeMillis() to BleCanaryState.Error(e.message ?: e.javaClass.simpleName))
            }
            running = false
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (running) transitions = transitions + (System.currentTimeMillis() to BleCanaryState.Stopped)
        running = false
    }

    val detected = transitions.any { it.second is BleCanaryState.Detected }
    val advertising = transitions.any { it.second is BleCanaryState.Advertising }
    val failed = transitions.lastOrNull()?.second is BleCanaryState.Error

    ToolPageList(title = "SELF-TEST BEACON", onBack = onBack) {
        item(key = "§intro") {
            Text(
                "The phone advertises as an AirTag that has been separated from its owner for sixty " +
                "seconds, with a random key, and the detector has to notice. The scanner recognises " +
                "the payload as its own and never turns it into a detection or an alert.",
                color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
            )
        }
        if (!status.scanning) {
            item(key = "§not-scanning") {
                TNotice(TCautionClr, "SCANNER OFF", "The test can only be heard by the running scanner. Start scanning on the SCAN tab first.")
            }
        }
        item(key = "§control") {
            TCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                running -> "Running — ${((startedAt + CANARY_DURATION_MS - now) / 1000L).coerceAtLeast(0L)} s left"
                                transitions.isEmpty() -> "Ready"
                                detected -> "Passed — the detector heard the beacon"
                                failed -> "Failed — see below"
                                advertising -> "Advertised, not heard"
                                else -> "Finished"
                            },
                            color = TInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        if (running) {
                            Text("Keep the phone where it is; the scanner needs a few seconds.", color = TMutedClr, fontSize = 12.sp)
                        }
                    }
                    when {
                        running -> TBadge("LIVE", TBlueClr)
                        detected -> TBadge("DETECTED", TClearClr)
                        failed -> TBadge("ERROR", TCriticalClr)
                        advertising -> TBadge("NOT HEARD", TCautionClr)
                        else -> Unit
                    }
                }
                if (running) {
                    LinearProgressIndicator(
                        progress = { ((now - startedAt).toFloat() / CANARY_DURATION_MS).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(), color = TAccentClr, trackColor = TRuleClr
                    )
                    TButton("STOP", onClick = { stop() }, style = TButtonStyle.CRITICAL)
                } else {
                    TButton("START 60 S TEST", onClick = { start() }, style = TButtonStyle.PRIMARY)
                }
            }
        }

        if (transitions.isNotEmpty()) {
            item(key = "§log-hdr") { TSectionLabel("WHAT HAPPENED") }
            itemsIndexed(transitions, key = { i, _ -> "t|$i" }) { _, (ts, state) ->
                Row(
                    Modifier.fillMaxWidth().background(TPanelClr, TCardShape).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(tTime(ts), color = TMutedClr, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(canaryLabel(state), color = canaryColor(state), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
                }
            }
            if (!running && advertising && !detected && !failed) {
                item(key = "§caveat") {
                    TNotice(
                        TCautionClr, "NOT HEARD",
                        "The advertiser worked, but this phone's Bluetooth controller does not report its own " +
                        "packets to the scanner — many do not. A second phone running Aegis nearby is what " +
                        "proves the scanner; it should show an AirTag during the test."
                    )
                }
            }
        }

        item(key = "§meaning-hdr") { TSectionLabel("WHAT THE RESULTS MEAN") }
        item(key = "§meaning") {
            TCard(spacing = 6) {
                Text(
                    "DETECTED means the scanner received the fake AirTag's advertisement and matched its " +
                    "random key: the whole chain from radio to classifier is working on this phone. The " +
                    "address shown is the random one the Bluetooth stack chose for the test.",
                    color = TInkDimClr, fontSize = 12.sp, lineHeight = 17.sp
                )
                Text(
                    "ADVERTISING without DETECTED means the beacon went on the air but this controller does " +
                    "not hear itself. That is a property of the chip, not a fault in the detector.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                )
                Text(
                    "An ERROR before advertising means Bluetooth is off, advertising is not permitted, or " +
                    "this phone cannot advertise at all.",
                    color = TMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                )
            }
        }
    }
}
