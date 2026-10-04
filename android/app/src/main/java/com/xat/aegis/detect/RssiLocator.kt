package com.xat.aegis.detect

import android.bluetooth.le.ScanResult
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xat.aegis.Registry
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Whether the signal is getting stronger as the user moves — the "warmer / colder" game. */
enum class SignalTrend { SEARCHING, WARMER, COLDER, STEADY }

/**
 * What the locator knows right now. Angles are compass azimuths in degrees, 0 = north,
 * clockwise. Arrays and floats that are not yet known are NaN rather than zero, so a
 * view never draws a confident reading from an empty one.
 */
data class LocatorState(
    val address: String,
    /** Where the top of the phone points, from the rotation vector. */
    val headingDeg: Float,
    /** Azimuth of the strongest 15° bucket, or NaN until enough directions have been sampled. */
    val bestAzimuthDeg: Float,
    /** Rolling-median RSSI per 15° bucket, 24 entries from north clockwise; NaN where unsampled. */
    val rssiByBucket: FloatArray,
    /** Log-distance estimate in metres, or NaN until the first reading. */
    val estimatedDistanceM: Float,
    /** 0..1: how much to trust [bestAzimuthDeg], from coverage, recency and contrast. */
    val confidence: Float,
    val lastRssi: Int?,
    val trend: SignalTrend,
    val samples: Int,
    /** Milliseconds since the target was last heard, or null before the first packet. */
    val lastSeenAgoMs: Long?
)

/**
 * Helps the user walk to a tracker once it has been detected.
 *
 * A phone has one antenna and no bearing to a BLE transmitter. What it does have is a
 * compass, and the fact that a body is a decent RF shadow at 2.4 GHz: holding the
 * phone in front and turning slowly, the signal is strongest when the phone faces the
 * tracker and weakest when the user's own body is between them. So RSSI is bucketed
 * by the azimuth the phone was pointing at when each packet arrived, each bucket holds
 * a one-second rolling median to tame the fading, and the strongest bucket is the
 * direction to try. Distance comes from the log-distance path-loss model, which is
 * honest to perhaps a factor of two indoors — the view says "about" for a reason.
 */
object RssiLocator {

    const val BUCKETS = 24
    const val BUCKET_DEG = 360f / BUCKETS

    /** Path-loss exponent: 2.0 is free space, which is what the brief asks for. */
    const val PATH_LOSS_EXPONENT = 2.0

    /** Rolling-median window per bucket. */
    private const val WINDOW_MS = 1_000L

    /** A bucket's reading is kept on the dial this long after its last sample. */
    private const val BUCKET_MEMORY_MS = 60_000L

    /** How often the state is republished when no packet has arrived. */
    private const val TICK_MS = 250L

    /** Before this many buckets have readings, no direction is claimed. */
    private const val MIN_BUCKETS_FOR_BEARING = 3

    /** Nothing heard for this long and the trend goes back to searching. */
    private const val LOST_MS = 4_000L

    /**
     * Follows [address] through the service's scan stream and the phone's rotation
     * sensor. Collecting the flow registers the sensor; cancelling it unregisters.
     */
    fun track(address: String, context: Context): Flow<LocatorState> =
        track(address, context, Registry.scans)

    fun track(address: String, context: Context, scans: Flow<ScanResult>): Flow<LocatorState> = channelFlow {
        val app = context.applicationContext
        val target = BleNames.formatMac(address)
        val engine = Engine(target)

        val sensors = app.getSystemService(SensorManager::class.java)
        val rotation = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                engine.heading(azimuthOf(event))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (rotation != null) sensors?.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_GAME)

        launch {
            scans.filter { BleNames.formatMac(it.device?.address ?: "") == target }.collect { scan ->
                engine.sample(scan, SystemClock.elapsedRealtime())
                send(engine.state(SystemClock.elapsedRealtime()))
            }
        }
        launch {
            while (isActive) {
                send(engine.state(SystemClock.elapsedRealtime()))
                delay(TICK_MS)
            }
        }

        awaitClose { if (rotation != null) sensors?.unregisterListener(listener) }
    }

    /**
     * Compass azimuth of the phone's top edge from a rotation-vector reading. The app
     * is portrait-only, so no display-rotation remap is needed.
     */
    private fun azimuthOf(event: SensorEvent): Float {
        val matrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(matrix, orientation)
        var deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (deg < 0f) deg += 360f
        return deg % 360f
    }

    /**
     * Free-space reference at one metre from the advertised transmit power. Advertised
     * power is at the antenna, often a positive number; one metre at 2.4 GHz costs
     * about 41 dB. Absent a figure, -59 dBm is the usual BLE beacon reference.
     */
    fun referenceAtOneMetre(txPowerLevel: Int?): Int =
        (txPowerLevel?.minus(41) ?: -59).coerceIn(-100, -30)

    /** Log-distance path loss: d = 10^((ref - rssi) / (10 n)). */
    fun distanceMetres(rssi: Double, referenceAtOneMetre: Int): Float =
        10.0.pow((referenceAtOneMetre - rssi) / (10.0 * PATH_LOSS_EXPONENT)).toFloat()

    private class Engine(val address: String) {
        private val lock = Any()
        private var heading = Float.NaN
        private val windows = Array(BUCKETS) { ArrayDeque<Pair<Long, Int>>() }
        private val median = FloatArray(BUCKETS) { Float.NaN }
        private val updatedAt = LongArray(BUCKETS)
        /** Every sample of the last six seconds, for the trend and the distance smoothing. */
        private val recent = ArrayDeque<Pair<Long, Int>>()
        private var reference = -59
        private var samples = 0
        private var lastSeen = 0L
        private var lastRssi: Int? = null

        fun heading(deg: Float) { synchronized(lock) { heading = deg } }

        fun sample(scan: ScanResult, now: Long) {
            val rssi = scan.rssi
            if (rssi >= 0 || rssi < -127) return
            synchronized(lock) {
                samples++
                lastSeen = now
                lastRssi = rssi
                scan.scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }?.let { reference = referenceAtOneMetre(it) }
                recent.addLast(now to rssi)
                while (recent.isNotEmpty() && now - recent.first().first > 6_000L) recent.removeFirst()

                val h = heading
                if (h.isNaN()) return
                val bucket = (((h + BUCKET_DEG / 2f) % 360f) / BUCKET_DEG).toInt().coerceIn(0, BUCKETS - 1)
                val window = windows[bucket]
                window.addLast(now to rssi)
                while (window.isNotEmpty() && now - window.first().first > WINDOW_MS) window.removeFirst()
                median[bucket] = medianOf(window.map { it.second })
                updatedAt[bucket] = now
            }
        }

        fun state(now: Long): LocatorState = synchronized(lock) {
            val buckets = FloatArray(BUCKETS) { i ->
                if (updatedAt[i] != 0L && now - updatedAt[i] <= BUCKET_MEMORY_MS) median[i] else Float.NaN
            }
            val filled = buckets.count { !it.isNaN() }
            var best = -1
            var max = Float.NEGATIVE_INFINITY
            var min = Float.POSITIVE_INFINITY
            for (i in 0 until BUCKETS) {
                val v = buckets[i]
                if (v.isNaN()) continue
                if (v > max) { max = v; best = i }
                if (v < min) min = v
            }
            val bestAzimuth = if (filled >= MIN_BUCKETS_FOR_BEARING && best >= 0) best * BUCKET_DEG else Float.NaN

            // Distance from the last second and a half of packets, not one of them.
            val smoothed = recent.filter { now - it.first <= 1_500L }.map { it.second }
            val distance = if (smoothed.isEmpty()) Float.NaN
            else distanceMetres(medianOf(smoothed).toDouble(), reference)

            val coverage = (filled.toFloat() / 8f).coerceIn(0f, 1f)
            val recency = if (lastSeen == 0L) 0f else (1f - (now - lastSeen).toFloat() / LOST_MS).coerceIn(0f, 1f)
            val contrast = if (filled >= 2) ((max - min) / 15f).coerceIn(0f, 1f) else 0f
            val confidence = (0.4f * coverage + 0.3f * recency + 0.3f * contrast).coerceIn(0f, 1f)

            LocatorState(
                address = address,
                headingDeg = if (heading.isNaN()) 0f else heading,
                bestAzimuthDeg = bestAzimuth,
                rssiByBucket = buckets,
                estimatedDistanceM = distance,
                confidence = confidence,
                lastRssi = lastRssi,
                trend = trend(now),
                samples = samples,
                lastSeenAgoMs = if (lastSeen == 0L) null else now - lastSeen
            )
        }

        /** Last 1.5 s against the 3–6 s before it; three dB either way is a real change. */
        private fun trend(now: Long): SignalTrend {
            if (lastSeen == 0L || now - lastSeen > LOST_MS) return SignalTrend.SEARCHING
            val current = recent.filter { now - it.first <= 1_500L }.map { it.second }
            val earlier = recent.filter { now - it.first in 3_000L..6_000L }.map { it.second }
            if (current.isEmpty() || earlier.isEmpty()) return SignalTrend.STEADY
            val delta = medianOf(current) - medianOf(earlier)
            return when {
                delta >= 3f -> SignalTrend.WARMER
                delta <= -3f -> SignalTrend.COLDER
                else -> SignalTrend.STEADY
            }
        }

        private fun medianOf(values: List<Int>): Float {
            if (values.isEmpty()) return Float.NaN
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid].toFloat()
            else (sorted[mid - 1] + sorted[mid]) / 2f
        }
    }
}

// ── View ─────────────────────────────────────────────────────────────────────

private val LocGround = Color(0xFF0E1116)
private val LocRule = Color(0xFF262E3A)
private val LocInk = Color(0xFFE6EAF1)
private val LocInkDim = Color(0xFFA8B2C1)
private val LocMuted = Color(0xFF6F7A8B)
private val LocCold = Color(0xFF2E5C8A)
private val LocHot = Color(0xFFFF7A3D)
private val LocCritical = Color(0xFFF2545B)

/**
 * A compass rose with the signal painted around it. The dial turns with the phone so
 * north stays north; the fixed pointer at the top is where the phone faces. Each 15°
 * segment is coloured by how strong the tracker was from that direction — cold blue
 * to hot orange — and the strongest one is marked. Turn until the mark sits under the
 * pointer, then walk, watching the distance and the warmer/colder line.
 */
@Composable
fun RssiLocatorView(state: LocatorState, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = LocInkDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    val distanceStyle = TextStyle(color = LocInk, fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    val unitStyle = TextStyle(color = LocMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

    val values = state.rssiByBucket.filter { !it.isNaN() }
    val minRssi = values.minOrNull() ?: -100f
    val maxRssi = values.maxOrNull() ?: -40f
    val range = (maxRssi - minRssi).coerceAtLeast(6f)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(16.dp)
        ) {
            val c = center
            val r = size.minDimension / 2f
            val ringR = r * 0.82f
            val arcStroke = r * 0.16f

            drawCircle(LocGround, r, c)
            drawCircle(LocRule, r, c, style = Stroke(2f))
            drawCircle(LocRule, ringR - arcStroke * 0.7f, c, style = Stroke(1f))

            // The world frame: rotated so that compass north is at the top of the dial
            // only when the phone itself points north.
            rotate(-state.headingDeg, c) {
                val arcRect = Offset(c.x - ringR, c.y - ringR)
                val arcSize = Size(ringR * 2, ringR * 2)
                for (i in 0 until RssiLocator.BUCKETS) {
                    val v = state.rssiByBucket[i]
                    // Canvas angles: 0° at three o'clock, clockwise. Azimuth 0 is north = -90°.
                    val start = i * RssiLocator.BUCKET_DEG - 90f - RssiLocator.BUCKET_DEG / 2f + 1f
                    val sweep = RssiLocator.BUCKET_DEG - 2f
                    if (v.isNaN()) {
                        drawArc(LocRule.copy(alpha = 0.6f), start, sweep, false, arcRect, arcSize, style = Stroke(arcStroke * 0.35f))
                    } else {
                        val t = ((v - minRssi) / range).coerceIn(0f, 1f)
                        val colour = lerp(LocCold, LocHot, t)
                        drawArc(colour, start, sweep, false, arcRect, arcSize, style = Stroke(arcStroke, cap = StrokeCap.Butt))
                    }
                }

                // Tick marks every 30°, cardinal letters at the four points.
                for (deg in 0 until 360 step 30) {
                    val a = Math.toRadians(deg - 90.0)
                    val inner = ringR - arcStroke * 0.9f
                    val outer = inner - (if (deg % 90 == 0) r * 0.07f else r * 0.035f)
                    drawLine(
                        if (deg % 90 == 0) LocInkDim else LocMuted,
                        Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
                        Offset(c.x + outer * cos(a).toFloat(), c.y + outer * sin(a).toFloat()),
                        strokeWidth = if (deg % 90 == 0) 2.5f else 1.2f
                    )
                }
                val labelR = ringR - arcStroke * 0.9f - r * 0.17f
                for ((deg, letter) in listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W")) {
                    val a = Math.toRadians(deg - 90.0)
                    val layout = measurer.measure(letter, if (deg == 0) labelStyle.copy(color = LocHot) else labelStyle)
                    val pos = Offset(
                        c.x + labelR * cos(a).toFloat() - layout.size.width / 2f,
                        c.y + labelR * sin(a).toFloat() - layout.size.height / 2f
                    )
                    // Letters are drawn upright by undoing the dial rotation around their own centre.
                    rotate(state.headingDeg, Offset(pos.x + layout.size.width / 2f, pos.y + layout.size.height / 2f)) {
                        drawText(layout, topLeft = pos)
                    }
                }

                // The strongest direction, marked on the outside of the ring.
                if (!state.bestAzimuthDeg.isNaN()) {
                    val a = Math.toRadians(state.bestAzimuthDeg - 90.0)
                    val tipR = ringR + arcStroke * 0.55f + r * 0.02f
                    val baseR = tipR + r * 0.09f
                    val tip = Offset(c.x + tipR * cos(a).toFloat(), c.y + tipR * sin(a).toFloat())
                    val spread = Math.toRadians(5.0)
                    val left = Offset(c.x + baseR * cos(a - spread).toFloat(), c.y + baseR * sin(a - spread).toFloat())
                    val right = Offset(c.x + baseR * cos(a + spread).toFloat(), c.y + baseR * sin(a + spread).toFloat())
                    val marker = Path().apply { moveTo(tip.x, tip.y); lineTo(left.x, left.y); lineTo(right.x, right.y); close() }
                    drawPath(marker, LocHot.copy(alpha = 0.35f + 0.65f * state.confidence))
                }
            }

            // Fixed pointer: where the phone faces.
            val pointer = Path().apply {
                moveTo(c.x, c.y - r + 4f)
                lineTo(c.x - r * 0.05f, c.y - r + r * 0.11f)
                lineTo(c.x + r * 0.05f, c.y - r + r * 0.11f)
                close()
            }
            drawPath(pointer, LocInk)

            // Distance in the middle.
            val distanceText = if (state.estimatedDistanceM.isNaN()) "—" else formatDistance(state.estimatedDistanceM)
            val distanceLayout = measurer.measure(distanceText, distanceStyle)
            drawText(distanceLayout, topLeft = Offset(c.x - distanceLayout.size.width / 2f, c.y - distanceLayout.size.height / 2f - r * 0.06f))
            val unitText = when {
                state.estimatedDistanceM.isNaN() -> "no signal yet"
                state.lastRssi != null -> "about · ${state.lastRssi} dBm"
                else -> "about"
            }
            val unitLayout = measurer.measure(unitText, unitStyle)
            drawText(unitLayout, topLeft = Offset(c.x - unitLayout.size.width / 2f, c.y + distanceLayout.size.height / 2f - r * 0.04f))
        }

        val (trendText, trendColour) = when (state.trend) {
            SignalTrend.WARMER -> "Warmer — keep going" to LocHot
            SignalTrend.COLDER -> "Colder — turn back" to LocCold
            SignalTrend.STEADY -> "Steady — hold the phone out and turn slowly" to LocInkDim
            SignalTrend.SEARCHING -> when {
                state.lastSeenAgoMs == null -> "Listening for ${BleNames.macSuffix(state.address)}…"
                else -> "Signal lost — last heard ${state.lastSeenAgoMs / 1000} s ago"
            } to LocCritical
        }
        Text(trendText, color = trendColour, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        val bearingText = when {
            state.bestAzimuthDeg.isNaN() -> "Turn a full circle to find the strongest direction"
            else -> {
                var rel = (state.bestAzimuthDeg - state.headingDeg + 360f) % 360f
                if (rel > 180f) rel -= 360f
                when {
                    kotlin.math.abs(rel) <= RssiLocator.BUCKET_DEG -> "Strongest straight ahead"
                    rel > 0 -> "Strongest ${rel.toInt()}° to your right"
                    else -> "Strongest ${(-rel).toInt()}° to your left"
                }
            }
        }
        Text(bearingText, color = LocInkDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("CONFIDENCE ${(state.confidence * 100).toInt()}%", color = LocMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            LinearProgressIndicator(
                progress = { state.confidence },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = lerp(LocCold, LocHot, state.confidence),
                trackColor = LocRule
            )
        }
    }
}

private fun formatDistance(metres: Float): String = when {
    metres < 10f -> "%.1f m".format(metres)
    metres < 100f -> "${metres.toInt()} m"
    else -> "100+ m"
}
