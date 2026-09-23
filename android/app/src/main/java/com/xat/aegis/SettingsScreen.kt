package com.xat.aegis

import android.Manifest
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xat.aegis.analysis.Report

// ── AppSettings singleton ──────────────────────────────────────────────────────

/**
 * Persisted settings backed by SharedPreferences (key: "prefs").
 * Call [load] once in the activity before accessing any field.
 * ScanService reads [scanMode] to choose the BLE scan power level.
 */
object AppSettings {

    const val PREFS_NAME = "prefs"
    private const val KEY_AGGRESSIVE_SCAN = "aggressive_scan"
    private const val KEY_FOLLOW_THRESHOLD = "follow_threshold_m"
    private const val KEY_PERSISTENCE_THRESHOLD = "persistence_threshold_min"
    private const val KEY_SCAN_ENABLED = "scan_enabled"
    private const val KEY_RESUME_AFTER_REBOOT = "resume_after_reboot"

    const val DEFAULT_FOLLOW_THRESHOLD_M = 300f
    const val DEFAULT_PERSISTENCE_THRESHOLD_MIN = 10f

    @Volatile private var _aggressiveScan = false
    @Volatile private var _followThresholdM = DEFAULT_FOLLOW_THRESHOLD_M
    @Volatile private var _persistenceThresholdMin = DEFAULT_PERSISTENCE_THRESHOLD_MIN
    @Volatile private var _scanEnabled = false
    @Volatile private var _resumeAfterReboot = false

    val aggressiveScan: Boolean get() = _aggressiveScan
    val followThresholdM: Float get() = _followThresholdM
    val persistenceThresholdMin: Float get() = _persistenceThresholdMin

    /** The persistence threshold in the units [com.xat.aegis.analysis.Tracker] works in. */
    val persistenceThresholdMs: Long get() = (_persistenceThresholdMin * 60_000f).toLong()

    /**
     * Whether the user last left scanning switched on. Read at boot so the detector
     * comes back only for someone who actually had it running.
     */
    val scanEnabled: Boolean get() = _scanEnabled

    /**
     * The user's choice to have scanning come back after a reboot. Only takes effect
     * together with ACCESS_BACKGROUND_LOCATION — see [BootReceiver].
     */
    val resumeAfterReboot: Boolean get() = _resumeAfterReboot

    /** BLE scan mode that ScanService should use. */
    val scanMode: Int
        get() = if (_aggressiveScan) ScanSettings.SCAN_MODE_LOW_LATENCY
                else ScanSettings.SCAN_MODE_BALANCED

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _aggressiveScan = p.getBoolean(KEY_AGGRESSIVE_SCAN, false)
        _followThresholdM = p.getFloat(KEY_FOLLOW_THRESHOLD, DEFAULT_FOLLOW_THRESHOLD_M)
        _persistenceThresholdMin = p.getFloat(KEY_PERSISTENCE_THRESHOLD, DEFAULT_PERSISTENCE_THRESHOLD_MIN)
        _scanEnabled = p.getBoolean(KEY_SCAN_ENABLED, false)
        _resumeAfterReboot = p.getBoolean(KEY_RESUME_AFTER_REBOOT, false)
    }

    fun setResumeAfterReboot(context: Context, value: Boolean) {
        _resumeAfterReboot = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RESUME_AFTER_REBOOT, value).apply()
    }

    fun setScanEnabled(context: Context, value: Boolean) {
        _scanEnabled = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SCAN_ENABLED, value).apply()
    }

    fun setAggressiveScan(context: Context, value: Boolean) {
        _aggressiveScan = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AGGRESSIVE_SCAN, value).apply()
    }

    fun setFollowThreshold(context: Context, value: Float) {
        _followThresholdM = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_FOLLOW_THRESHOLD, value).apply()
    }

    fun setPersistenceThreshold(context: Context, value: Float) {
        _persistenceThresholdMin = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_PERSISTENCE_THRESHOLD, value).apply()
    }
}

// ── Private colour tokens (local to this file) ─────────────────────────────────

private val SGroundClr   = Color(0xFF0E1116)
private val SPanelClr    = Color(0xFF161B23)
private val SInkClr      = Color(0xFFE6EAF1)
private val SInkDimClr   = Color(0xFFA8B2C1)
private val SMutedClr    = Color(0xFF6F7A8B)
private val SRuleClr     = Color(0xFF262E3A)
private val SAccentClr   = Color(0xFFFF7A3D)
private val SCriticalClr = Color(0xFFF2545B)
private val SCardShape   = RoundedCornerShape(4.dp)

// ── SettingsScreen composable ──────────────────────────────────────────────────

@Composable
fun SettingsScreen(onBack: () -> Unit, onClearData: () -> Unit = {}) {
    val context = LocalContext.current
    val trusted by Registry.trusted.collectAsStateWithLifecycle()
    val status by Registry.status.collectAsStateWithLifecycle()
    val detections by Registry.detections.collectAsStateWithLifecycle()
    val timeline by Registry.timeline.collectAsStateWithLifecycle()
    val cell by Registry.cell.collectAsStateWithLifecycle()
    val nfc by Registry.nfc.collectAsStateWithLifecycle()

    var aggressiveScan by remember { mutableStateOf(AppSettings.aggressiveScan) }
    var followThreshold by remember { mutableFloatStateOf(AppSettings.followThresholdM) }
    var persistenceThreshold by remember { mutableFloatStateOf(AppSettings.persistenceThresholdMin) }
    var showClearDialog by remember { mutableStateOf(false) }

    // ── Resume after reboot ─────────────────────────────────────────────────
    //
    // Android only lets the scanner start at boot with background location ("Allow
    // all the time"), which has to be requested on its own, after foreground
    // location, and which Android grants only on its settings page — there is no
    // dialog for it. The toggle is on only when the user chose it *and* the
    // permission is actually there; it is re-read on every resume because the
    // grant happens outside the app.
    fun has(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var resumeWanted by remember { mutableStateOf(AppSettings.resumeAfterReboot) }
    var backgroundGranted by remember { mutableStateOf(has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }
    var showBackgroundDialog by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        backgroundGranted = has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> backgroundGranted = granted }
    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Background location can only be asked for once foreground location is held.
        if (granted) backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    if (showBackgroundDialog) {
        AlertDialog(
            onDismissRequest = { showBackgroundDialog = false },
            containerColor = SPanelClr,
            title = {
                Text("Allow location all the time", color = SInkClr, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Android only lets Aegis restart scanning after a reboot if location access " +
                    "is set to \"Allow all the time\". Android will open the location permission " +
                    "page for Aegis — choose \"Allow all the time\" there, then come back.",
                    color = SInkDimClr, fontSize = 13.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBackgroundDialog = false
                    if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        foregroundLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }) {
                    Text("CONTINUE", color = SAccentClr, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackgroundDialog = false }) {
                    Text("CANCEL", color = SMutedClr, letterSpacing = 1.sp)
                }
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = SPanelClr,
            title = {
                Text("Clear all data?", color = SInkClr, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "This removes all detections, the cellular baseline and all timeline events. " +
                    "This cannot be undone.",
                    color = SInkDimClr, fontSize = 13.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // The scanner owns the baseline and the event log in memory, so it
                    // has to do the clearing. Deleting the files from here only worked
                    // while the service was stopped — otherwise the next flush wrote
                    // everything straight back.
                    onClearData()
                    showClearDialog = false
                }) {
                    Text("CLEAR", color = SCriticalClr, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("CANCEL", color = SMutedClr, letterSpacing = 1.sp)
                }
            }
        )
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(SGroundClr)
            // This screen replaces the whole tab tree, so it does not inherit the
            // insets applied around the tabs and has to keep clear of the system
            // bars itself — otherwise the BACK row sits under the status bar.
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header row
        item(key = "§header") {
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                    Text("‹ BACK", color = SAccentClr, fontSize = 13.sp, letterSpacing = 1.sp)
                }
                Text(
                    "SETTINGS", color = SInkClr,
                    fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
                Spacer(Modifier.width(60.dp))
            }
        }

        // ── Scan settings ────────────────────────────────────────────────────

        item(key = "§scan-hdr") { SSettingsSectionLabel("SCAN SETTINGS") }

        item(key = "§aggressive") {
            Column(
                Modifier.fillMaxWidth().background(SPanelClr, SCardShape).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            "Aggressive scan mode", color = SInkClr,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Higher detection rate at the cost of battery life",
                            color = SMutedClr, fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = aggressiveScan,
                        onCheckedChange = { v ->
                            aggressiveScan = v
                            AppSettings.setAggressiveScan(context, v)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SAccentClr,
                            checkedTrackColor = SAccentClr.copy(alpha = 0.4f)
                        )
                    )
                }
                Text(
                    if (aggressiveScan) "SCAN_MODE_LOW_LATENCY — faster detection, higher battery drain"
                    else "SCAN_MODE_BALANCED — standard detection, recommended for field use",
                    color = SMutedClr, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
        }

        item(key = "§resume-reboot") {
            val active = resumeWanted && backgroundGranted
            Column(
                Modifier.fillMaxWidth().background(SPanelClr, SCardShape).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            "Resume scanning after reboot", color = SInkClr,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Restarts the scanner when the phone boots, if it was running before",
                            color = SMutedClr, fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = active,
                        onCheckedChange = { v ->
                            resumeWanted = v
                            AppSettings.setResumeAfterReboot(context, v)
                            if (v && !backgroundGranted) showBackgroundDialog = true
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SAccentClr,
                            checkedTrackColor = SAccentClr.copy(alpha = 0.4f)
                        )
                    )
                }
                if (!backgroundGranted) {
                    Text(
                        "Needs location access set to \"Allow all the time\". Android asks for this " +
                        "separately: turning this on sends you to the location permission page for " +
                        "Aegis, where you pick \"Allow all the time\".",
                        color = SMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                    )
                    if (resumeWanted) {
                        // After a refusal Android stops opening the page on request, so
                        // offer the app's own settings page as the way there.
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("OPEN APP SETTINGS", color = SAccentClr, fontSize = 11.sp, letterSpacing = 1.sp)
                        }
                    }
                } else if (!resumeWanted) {
                    Text(
                        "\"Allow all the time\" is granted but not used while this is off. You can " +
                        "reduce it to \"Only while using the app\" in Android settings.",
                        color = SMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                    )
                }
            }
        }

        item(key = "§follow-thresh") {
            Column(
                Modifier.fillMaxWidth().background(SPanelClr, SCardShape).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Following confirmation threshold",
                        color = SInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${followThreshold.toInt()} m",
                        color = SAccentClr, fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "Minimum displacement required to confirm a device is following you",
                    color = SMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                )
                Slider(
                    value = followThreshold,
                    onValueChange = { followThreshold = it },
                    onValueChangeFinished = { AppSettings.setFollowThreshold(context, followThreshold) },
                    valueRange = 100f..500f,
                    steps = 7,
                    colors = SliderDefaults.colors(
                        thumbColor = SAccentClr,
                        activeTrackColor = SAccentClr,
                        inactiveTrackColor = SRuleClr
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("100 m", color = SMutedClr, fontSize = 11.sp)
                    Text("Default: 300 m", color = SMutedClr, fontSize = 11.sp)
                    Text("500 m", color = SMutedClr, fontSize = 11.sp)
                }
            }
        }

        item(key = "§persist-thresh") {
            Column(
                Modifier.fillMaxWidth().background(SPanelClr, SCardShape).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Persistence threshold",
                        color = SInkClr, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${persistenceThreshold.toInt()} min",
                        color = SAccentClr, fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "Minutes of continuous presence before a device is flagged as persistent",
                    color = SMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                )
                Slider(
                    value = persistenceThreshold,
                    onValueChange = { persistenceThreshold = it },
                    onValueChangeFinished = {
                        AppSettings.setPersistenceThreshold(context, persistenceThreshold)
                    },
                    valueRange = 5f..30f,
                    steps = 4,
                    colors = SliderDefaults.colors(
                        thumbColor = SAccentClr,
                        activeTrackColor = SAccentClr,
                        inactiveTrackColor = SRuleClr
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("5 min", color = SMutedClr, fontSize = 11.sp)
                    Text("Default: 10 min", color = SMutedClr, fontSize = 11.sp)
                    Text("30 min", color = SMutedClr, fontSize = 11.sp)
                }
            }
        }

        // ── Trusted devices ──────────────────────────────────────────────────

        item(key = "§trusted-hdr") { SSettingsSectionLabel("TRUSTED DEVICES") }

        if (trusted.isEmpty()) {
            item(key = "§trusted-empty") {
                Column(
                    Modifier.fillMaxWidth().background(SPanelClr, SCardShape).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("No trusted devices yet", color = SInkDimClr, fontSize = 13.sp)
                    Text(
                        "Mark any detection card as safe on the SCAN tab. " +
                        "Its Bluetooth address is automatically added here.",
                        color = SMutedClr, fontSize = 12.sp, lineHeight = 17.sp
                    )
                }
            }
        } else {
            items(trusted.sorted(), key = { "trusted|$it" }) { addr ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(SPanelClr, SCardShape)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        addr, color = SInkDimClr, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { Registry.untrust(addr) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("REMOVE", color = SCriticalClr, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                }
            }
            item(key = "§trusted-hint") {
                Text(
                    "Tap MARK SAFE on a detection card on the Scan tab to add a device here.",
                    color = SMutedClr, fontSize = 12.sp
                )
            }
        }

        // ── Data management ──────────────────────────────────────────────────

        item(key = "§data-hdr") { SSettingsSectionLabel("DATA") }

        item(key = "§data-actions") {
            Column(
                Modifier.fillMaxWidth().background(SPanelClr, SCardShape).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        val intent = Report.share(context, status, detections, timeline, cell, nfc)
                        context.startActivity(Intent.createChooser(intent, "Share evidence report"))
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SAccentClr,
                        contentColor = Color(0xFF12161D)
                    ),
                    shape = SCardShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("EXPORT EVIDENCE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Button(
                    onClick = { showClearDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SCriticalClr.copy(alpha = 0.15f),
                        contentColor = SCriticalClr
                    ),
                    shape = SCardShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("CLEAR ALL DATA", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }

        // ── About ────────────────────────────────────────────────────────────

        item(key = "§about-hdr") { SSettingsSectionLabel("ABOUT") }

        item(key = "§about") {
            Column(
                Modifier.fillMaxWidth().background(SPanelClr, SCardShape).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SSettingsKv("App", "Aegis")
                SSettingsKv(
                    "Version",
                    "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
                )
                SSettingsKv("Package", BuildConfig.APPLICATION_ID)

                Box(Modifier.fillMaxWidth().height(1.dp).background(SRuleClr))

                Text(
                    "Legal disclaimer",
                    color = SInkClr, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Aegis is a counter-surveillance research tool intended for lawful personal " +
                    "and professional security use. Detection of a device is not conclusive evidence of " +
                    "surveillance. Always verify findings through multiple methods before acting. " +
                    "Users are responsible for complying with all applicable laws regarding electronic " +
                    "surveillance detection in their jurisdiction.",
                    color = SMutedClr, fontSize = 12.sp, lineHeight = 18.sp
                )

                Box(Modifier.fillMaxWidth().height(1.dp).background(SRuleClr))

                Text(
                    "Privacy",
                    color = SInkClr, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Detection data stays on this device. Location history, device identifiers, " +
                    "cellular baselines, the timeline and the card vault are stored locally and are " +
                    "never uploaded. The Map tab downloads map tiles from OpenStreetMap, which sees " +
                    "your IP address and the area you're viewing. Evidence exports are shared only " +
                    "when you explicitly trigger an export.",
                    color = SMutedClr, fontSize = 12.sp, lineHeight = 18.sp
                )
            }
        }

        item(key = "§footer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SSettingsSectionLabel(text: String) {
    Text(
        text,
        color = SAccentClr, fontSize = 10.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun SSettingsKv(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = SMutedClr, fontSize = 12.sp)
        Text(value, color = SInkDimClr, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
