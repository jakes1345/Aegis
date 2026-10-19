package app.clairix

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.clairix.nav.NavScreen
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

private const val RPM_MAX = 8000f
private const val RPM_REDLINE_FRACTION = 0.75f
private const val COOLANT_COLD_C = 70
private const val COOLANT_HOT_C = 100
private const val KPH_TO_MPH = 0.621f

private const val SECTION_NAVIGATE = 0
private const val SECTION_SENSORS = 1

/** The Drive tab: turn-by-turn navigation, or the in-car sensors (scanner, ADS-B, OBD). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(viewModel: DriveViewModel = viewModel()) {
    var section by rememberSaveable { mutableIntStateOf(SECTION_NAVIGATE) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                SegmentedButton(
                    selected = section == SECTION_NAVIGATE,
                    onClick = { section = SECTION_NAVIGATE },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("Navigate") }
                SegmentedButton(
                    selected = section == SECTION_SENSORS,
                    onClick = { section = SECTION_SENSORS },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("Sensors") }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (section == SECTION_NAVIGATE) {
                    NavScreen(modifier = Modifier.fillMaxSize())
                } else {
                    DriveSensors(viewModel)
                }
            }
        }
    }
}

@Composable
private fun DriveSensors(viewModel: DriveViewModel) {
    val obd by viewModel.obdData.collectAsStateWithLifecycle()
    val sdrState by viewModel.sdrState.collectAsStateWithLifecycle()
    val channels by viewModel.scannerChannels.collectAsStateWithLifecycle()
    val activeChannel by viewModel.activeChannel.collectAsStateWithLifecycle()
    val squelchOpen by viewModel.squelchOpen.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val lastRecordingPath by viewModel.lastRecordingPath.collectAsStateWithLifecycle()
    val aircraft by viewModel.aircraft.collectAsStateWithLifecycle()
    val driveModeActive by viewModel.driveModeActive.collectAsStateWithLifecycle()
    val squelchLevel by viewModel.squelchLevel.collectAsStateWithLifecycle()

    DriveContent(
        obd = obd,
        sdrState = sdrState,
        channels = channels,
        activeChannel = activeChannel,
        squelchOpen = squelchOpen,
        isRecording = isRecording,
        lastRecordingPath = lastRecordingPath,
        aircraft = aircraft,
        driveModeActive = driveModeActive,
        squelchLevel = squelchLevel,
        onSetSquelch = viewModel::setSquelch,
        onAddChannel = viewModel::addChannel,
        onRemoveChannel = viewModel::removeChannel,
        onSetChannelActive = viewModel::setChannelActive,
        onConnectObd = viewModel::connectObd,
        onDisconnectObd = viewModel::disconnectObd,
        onToggleDriveMode = {
            if (driveModeActive) viewModel.deactivateDriveMode() else viewModel.activateDriveMode()
        },
        onOpenRecording = viewModel::openRecording
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DriveContent(
    obd: ObdData,
    sdrState: SdrState,
    channels: List<ScannerChannel>,
    activeChannel: ScannerChannel?,
    squelchOpen: Boolean,
    isRecording: Boolean,
    lastRecordingPath: String?,
    aircraft: Map<String, Aircraft>,
    driveModeActive: Boolean,
    squelchLevel: Float,
    onSetSquelch: (Float) -> Unit,
    onAddChannel: (String, Long) -> Unit,
    onRemoveChannel: (Long) -> Unit,
    onSetChannelActive: (Long, Boolean) -> Unit,
    onConnectObd: () -> Unit,
    onDisconnectObd: () -> Unit,
    onToggleDriveMode: () -> Unit,
    onOpenRecording: (String) -> Unit
) {
    var scannerExpanded by remember { mutableStateOf(true) }
    var adsbExpanded by remember { mutableStateOf(true) }
    var obdExpanded by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatusBarRow(obd = obd, sdrState = sdrState)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ScannerCard(
                    expanded = scannerExpanded,
                    onToggle = { scannerExpanded = !scannerExpanded },
                    channels = channels,
                    activeChannel = activeChannel,
                    squelchOpen = squelchOpen,
                    isRecording = isRecording,
                    squelchLevel = squelchLevel,
                    lastRecordingPath = lastRecordingPath,
                    onSquelchChange = onSetSquelch,
                    onAddClick = { showAddDialog = true },
                    onRemoveChannel = onRemoveChannel,
                    onSetChannelActive = onSetChannelActive,
                    onOpenRecording = onOpenRecording
                )
                AdsbCard(
                    expanded = adsbExpanded,
                    onToggle = { adsbExpanded = !adsbExpanded },
                    aircraft = aircraft.values.toList()
                )
                ObdCard(
                    expanded = obdExpanded,
                    onToggle = { obdExpanded = !obdExpanded },
                    obd = obd,
                    onConnect = onConnectObd,
                    onDisconnect = onDisconnectObd
                )
            }

            FilledTonalButton(
                onClick = onToggleDriveMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .heightIn(min = 64.dp),
                shape = RoundedCornerShape(12.dp),
                colors = if (driveModeActive) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                }
            ) {
                Text(
                    text = if (driveModeActive) "Deactivate" else "Activate Drive Mode",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (showAddDialog) {
        AddChannelDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, freqHz ->
                onAddChannel(name, freqHz)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun StatusBarRow(obd: ObdData, sdrState: SdrState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = obd.speedKph?.toString() ?: "--",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "km/h",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            val mph = obd.speedKph?.let { (it * KPH_TO_MPH).roundToInt() }
            Text(
                text = if (mph != null) "$mph mph" else "-- mph",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            SdrIndicator(sdrState)
        }

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(
                color = if (obd.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (obd.connected) obd.deviceName ?: "OBD" else "No OBD",
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun SdrIndicator(state: SdrState) {
    val ok = MaterialTheme.colorScheme.primary
    val caution = MaterialTheme.colorScheme.tertiary
    val bad = MaterialTheme.colorScheme.error
    val pulse = rememberInfiniteTransition(label = "sdrPulse").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "sdrPulseAlpha"
    )
    val dotColor: Color
    val dotAlpha: Float
    val label: String
    when (state) {
        is SdrState.Ready -> {
            dotColor = ok
            dotAlpha = 1f
            label = "SDR Ready"
        }
        is SdrState.Opening -> {
            dotColor = caution
            dotAlpha = pulse.value
            label = "Opening…"
        }
        is SdrState.Error -> {
            dotColor = bad
            dotAlpha = 1f
            label = state.msg
        }
        else -> {
            dotColor = bad
            dotAlpha = 1f
            label = "SDR Closed"
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color = dotColor, modifier = Modifier.alpha(dotAlpha))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Int = 10) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun CardHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(10.dp))
        trailing()
        Spacer(Modifier.weight(1f))
        Text(
            text = if (expanded) "▲" else "▼",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScannerCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    channels: List<ScannerChannel>,
    activeChannel: ScannerChannel?,
    squelchOpen: Boolean,
    isRecording: Boolean,
    squelchLevel: Float,
    lastRecordingPath: String?,
    onSquelchChange: (Float) -> Unit,
    onAddClick: () -> Unit,
    onRemoveChannel: (Long) -> Unit,
    onSetChannelActive: (Long, Boolean) -> Unit,
    onOpenRecording: (String) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        CardHeader(title = "Scanner", expanded = expanded, onToggle = onToggle) {
            if (isRecording) {
                val pulse = rememberInfiniteTransition(label = "recPulse").animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                    label = "recPulseAlpha"
                )
                StatusDot(
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.alpha(pulse.value),
                    size = 12
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "REC",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ActiveChannelDisplay(activeChannel)
                SquelchWave(squelchOpen = squelchOpen)
                ChannelList(
                    channels = channels,
                    onRemoveChannel = onRemoveChannel,
                    onSetChannelActive = onSetChannelActive
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${channels.size} channels",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    FilledTonalIconButton(onClick = onAddClick) {
                        Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Squelch",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = squelchLevel,
                        onValueChange = onSquelchChange,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    Text(
                        text = "${(squelchLevel * 100).roundToInt()}%",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (lastRecordingPath != null) {
                    Text(
                        text = lastRecordingPath,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpenRecording(lastRecordingPath) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveChannelDisplay(channel: ScannerChannel?) {
    if (channel == null) {
        Text(
            text = "No active channel",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column {
        Text(
            text = formatMhz(channel.freqHz),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = channel.name,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SquelchWave(squelchOpen: Boolean) {
    val pulse = rememberInfiniteTransition(label = "wavePulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "wavePulseAlpha"
    )
    val phase = rememberInfiniteTransition(label = "wavePhase").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1200)),
        label = "wavePhaseValue"
    )
    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.outline
    val color = if (squelchOpen) activeColor else idleColor
    val alpha = if (squelchOpen) pulse.value else 0.5f

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .alpha(alpha)
    ) {
        val midY = size.height / 2f
        val path = Path()
        val steps = 96
        val amplitude = if (squelchOpen) size.height * 0.4f else size.height * 0.04f
        for (i in 0..steps) {
            val x = size.width * i / steps
            val t = i.toFloat() / steps
            val envelope = sin(PI * t).toFloat()
            val y = midY + amplitude * envelope * sin(t * 6f * PI.toFloat() + phase.value)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 3.dp.toPx()))
        if (!squelchOpen) {
            drawLine(
                color = color,
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelList(
    channels: List<ScannerChannel>,
    onRemoveChannel: (Long) -> Unit,
    onSetChannelActive: (Long, Boolean) -> Unit
) {
    if (channels.isEmpty()) {
        Text(
            text = "No channels saved",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
    ) {
        items(channels, key = { it.freqHz }) { channel ->
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled) {
                        onRemoveChannel(channel.freqHz)
                        true
                    } else {
                        false
                    }
                }
            )
            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            text = "Delete",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = channel.name,
                        modifier = Modifier.weight(1f),
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = formatMhz(channel.freqHz),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = channel.active,
                        onCheckedChange = { onSetChannelActive(channel.freqHz, it) }
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun AdsbCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    aircraft: List<Aircraft>
) {
    val anyEmergency = aircraft.any { it.isEmergency }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        CardHeader(title = "Aircraft", expanded = expanded, onToggle = onToggle) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (anyEmergency) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.secondaryContainer
                    )
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text(
                    text = aircraft.size.toString(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (anyEmergency) MaterialTheme.colorScheme.onError
                    else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
            ) {
                if (aircraft.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No aircraft detected",
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "SDR must be on 1090 MHz",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val sorted = remember(aircraft) {
                        aircraft.sortedWith(
                            compareByDescending<Aircraft> { it.isEmergency }.thenBy { it.icao }
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    ) {
                        items(sorted, key = { it.icao }) { plane ->
                            AircraftRow(plane)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AircraftRow(plane: Aircraft) {
    val emergency = plane.isEmergency
    val rowBackground = if (emergency) MaterialTheme.colorScheme.errorContainer else Color.Transparent
    val textColor = if (emergency) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurface
    val dimColor = if (emergency) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = plane.callsign?.takeIf { it.isNotBlank() } ?: plane.icao,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = textColor
            )
            if (emergency) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.error)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "EMERG",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onError
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = plane.squawk ?: "----",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = textColor
                    )
                }
            }
        }
        Text(
            text = plane.altitudeFt?.let { "$it ft" } ?: "-- ft",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = dimColor,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        Text(
            text = plane.speedKts?.let { "$it kts" } ?: "-- kts",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = dimColor,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        Text(
            text = "↑",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier
                .padding(start = 6.dp)
                .rotate(plane.headingDeg?.toFloat() ?: 0f)
                .alpha(if (plane.headingDeg == null) 0.3f else 1f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ObdCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    obd: ObdData,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        CardHeader(title = "OBD-II", expanded = expanded, onToggle = onToggle) {
            Text(
                text = if (obd.connected) obd.deviceName ?: "Connected" else "Not connected",
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                RpmGauge(rpm = obd.rpm)
                CoolantRow(tempC = obd.coolantTempC)

                Column {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Throttle",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = obd.throttlePct?.let { "$it%" } ?: "--",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { ((obd.throttlePct ?: 0) / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                    )
                }

                if (obd.dtcs.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        obd.dtcs.forEach { code ->
                            AssistChip(
                                onClick = {},
                                label = { Text(code, fontFamily = FontFamily.Monospace) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            )
                        }
                    }
                }

                if (obd.connected) {
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                } else {
                    OutlinedButton(onClick = onConnect) { Text("Connect OBD") }
                }
            }
        }
    }
}

@Composable
private fun RpmGauge(rpm: Int?) {
    val fraction = ((rpm ?: 0) / RPM_MAX).coerceIn(0f, 1f)
    val low = MaterialTheme.colorScheme.primary
    val high = MaterialTheme.colorScheme.error
    val barColor = if (fraction <= RPM_REDLINE_FRACTION) {
        low
    } else {
        lerp(low, high, (fraction - RPM_REDLINE_FRACTION) / (1f - RPM_REDLINE_FRACTION))
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp)),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Text(
            text = rpm?.let { "$it RPM" } ?: "--",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CoolantRow(tempC: Int?) {
    val color = when {
        tempC == null -> MaterialTheme.colorScheme.onSurfaceVariant
        tempC < COOLANT_COLD_C -> MaterialTheme.colorScheme.secondary
        tempC <= COOLANT_HOT_C -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color = color, size = 12)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Coolant",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = tempC?.let { "$it °C" } ?: "--",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = color
        )
    }
}

@Composable
private fun AddChannelDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Long) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var freq by remember { mutableStateOf("") }
    val freqMhz = freq.toDoubleOrNull()
    val minMhz = SdrUsbDriver.MIN_FREQ_HZ / 1_000_000.0
    val maxMhz = SdrUsbDriver.MAX_FREQ_HZ / 1_000_000.0
    val valid = name.isNotBlank() && freqMhz != null && freqMhz in minMhz..maxMhz

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add channel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Channel name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = freq,
                    onValueChange = { freq = it },
                    label = { Text("Frequency (MHz, %.0f–%.0f)".format(Locale.US, minMhz, maxMhz)) },
                    isError = freq.isNotEmpty() && (freqMhz == null || freqMhz !in minMhz..maxMhz),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (valid && freqMhz != null) {
                        onConfirm(name.trim(), (freqMhz * 1_000_000.0).roundToLong())
                    }
                }
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatMhz(freqHz: Long): String =
    String.format(Locale.US, "%.3f MHz", freqHz / 1_000_000.0)

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun DriveScreenPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        DriveContent(
            obd = ObdData(),
            sdrState = SdrState.Closed,
            channels = emptyList(),
            activeChannel = null,
            squelchOpen = false,
            isRecording = false,
            lastRecordingPath = null,
            aircraft = emptyMap(),
            driveModeActive = false,
            squelchLevel = 0.3f,
            onSetSquelch = {},
            onAddChannel = { _, _ -> },
            onRemoveChannel = {},
            onSetChannelActive = { _, _ -> },
            onConnectObd = {},
            onDisconnectObd = {},
            onToggleDriveMode = {},
            onOpenRecording = {}
        )
    }
}
