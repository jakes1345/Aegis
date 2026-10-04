package com.xat.aegis.analysis

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.xat.aegis.Alert
import com.xat.aegis.EventKind
import com.xat.aegis.Registry
import com.xat.aegis.Severity
import com.xat.aegis.WifiSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ── Data model ───────────────────────────────────────────────────────────────

enum class CameraFindMethod { LENS_GLINT, IR_ILLUMINATOR, NETWORK, BLE_SETUP_AP, WIFI_SETUP_AP, MAGNETIC }

data class CameraFinding(
    val id: String,
    val ts: Long,
    val method: CameraFindMethod,
    /** 0..1 — how strongly this evidence alone points at a camera. */
    val confidence: Float,
    /** Where to look: a position in the viewfinder, an IP address, a distance estimate. */
    val location: String,
    val detail: String,
    val lat: Double?,
    val lon: Double?
)

/** The two optical passes. Glint wants the torch on the back camera; IR wants a dark room and the front camera. */
enum class OpticalMode(val label: String, val torch: Boolean, val frontCamera: Boolean) {
    GLINT("Lens glint", torch = true, frontCamera = false),
    IR("IR illuminator", torch = false, frontCamera = true)
}

/**
 * A tracked bright spot in the viewfinder. [x], [y] and [radius] are normalised 0..1
 * in *display* orientation (rotation and front-camera mirroring already applied),
 * so the overlay just multiplies by its own size.
 */
data class GlintBlob(
    val id: Int,
    val x: Float,
    val y: Float,
    val radius: Float,
    /** Consecutive frames this spot has held still while the scene held still. */
    val persistence: Int,
    val confidence: Float
) {
    val candidate: Boolean get() = persistence >= LensGlintAnalyzer.MIN_PERSISTENCE
}

/** What the OPTICAL tab shows beside the viewfinder. */
data class OpticalState(
    val mode: OpticalMode = OpticalMode.GLINT,
    val running: Boolean = false,
    val torchOn: Boolean = false,
    val error: String? = null,
    /** Mean luma of the last frame, 0..255. */
    val meanLuma: Float = 0f,
    /** Frame-to-frame change in mean luma, as a fraction. Above 6 % the tracker holds. */
    val lumaChange: Float = 0f,
    /** Pixels over the threshold in the last frame, at 1/4 scale. */
    val brightPixels: Int = 0,
    /** True when so much of the frame is saturated that nothing can be told apart. */
    val washedOut: Boolean = false,
    /** IR mode only: the room is too bright for an IR dot to stand out. */
    val tooBright: Boolean = false,
    val tracks: List<GlintBlob> = emptyList(),
    val frames: Long = 0L,
    val fps: Float = 0f
)

data class NetworkScanProgress(
    val running: Boolean = false,
    val phase: String = "",
    val hostsDone: Int = 0,
    val hostsTotal: Int = 0,
    /** "192.168.1.0/24" — what was swept. */
    val subnet: String? = null,
    val ssid: String? = null,
    val liveHosts: Int = 0,
    val error: String? = null,
    val lastScanTs: Long = 0L,
    val found: Int = 0
)

data class WirelessState(
    val wifiScanning: Boolean = false,
    val wifiScannedTs: Long = 0L,
    val wifiApsSeen: Int = 0,
    val wifiError: String? = null,
    val bleListening: Boolean = false,
    /** Wall-clock time the current BLE listening window ends, or 0. */
    val bleUntilTs: Long = 0L,
    val bleDevicesSeen: Int = 0,
    val bleError: String? = null
)

data class MagneticState(
    val active: Boolean = false,
    val error: String? = null,
    /** Field magnitude right now, µT. */
    val fieldUt: Float = 0f,
    /** Slow baseline the room settles at, µT (Earth's field is 25–65 µT). */
    val baselineUt: Float = 0f,
    val deltaUt: Float = 0f,
    val thresholdUt: Float = 0f,
    /** Largest deviation seen in the current or last episode. */
    val peakUt: Float = 0f,
    val triggered: Boolean = false,
    /** The sensor says its own readings cannot be trusted — wave the phone in a figure 8. */
    val unreliable: Boolean = false,
    val warmingUp: Boolean = false
)

/** One line of the guided room sweep. */
data class SweepStep(val id: String, val title: String, val detail: String, val tool: String)

// ── Optical: lens glint / IR illuminator ─────────────────────────────────────

/**
 * Finds the one thing a camera lens cannot help doing: looking back at you.
 *
 * With the torch on, a lens — even a pinhole lens — is a retroreflector: the light
 * goes down the barrel, bounces off the sensor stack and comes straight back up
 * the same axis as a steady, round pinpoint. Shiny edges, screws and glass also
 * flash, but their highlights slide as the phone moves. So per frame: threshold the
 * Y plane, connect the bright pixels into blobs on a 1/4-scale grid, keep the small
 * round ones, and track each by nearest centroid. A blob that holds its place for
 * [MIN_PERSISTENCE] consecutive frames while the frame's mean luma holds within 6 %
 * (the scene is static, so this is not just a slow pan) is a candidate.
 *
 * IR mode is the same machinery with the torch off on the front camera — whose IR
 * cut filter is weak on most phones — in a dark room: a night-vision camera lights
 * the room with 850 nm LEDs the eye cannot see, and they show up as a bright dot.
 *
 * Only the Y plane is read; frames never leave this class.
 */
class LensGlintAnalyzer(
    private val mode: OpticalMode,
    /** True when the preview is mirrored (front camera), so overlay x is flipped to match. */
    private val mirrored: Boolean,
    private val onState: (OpticalState) -> Unit,
    private val onCandidate: (GlintBlob) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        const val MIN_PERSISTENCE = 8
        private const val SCALE = 4
        private const val GLINT_THRESHOLD = 235
        /** IR leaks through the front camera's filter dimly; the dot is bright but rarely saturates. */
        private const val IR_THRESHOLD = 200
        /** IR mode: above this mean luma the room has visible light in it and a dot means nothing. */
        private const val IR_MAX_AMBIENT = 60f
        private const val MIN_AREA = 2
        private const val MAX_AREA = 60
        private const val MIN_CIRCULARITY = 0.6f
        /** Fraction of the grid allowed over threshold before the frame is called washed out. */
        private const val WASHOUT_FRACTION = 0.15f
        private const val LUMA_STABLE_FRACTION = 0.06f
        /** Grid pixels a centroid may move between frames and still be the same spot. */
        private const val MATCH_RADIUS = 3f
        private const val CONFIDENCE_FULL_AT = 20f
    }

    private class Track(var gx: Float, var gy: Float, var radius: Float, var persistence: Int, val id: Int) {
        var reportedAt = 0
    }

    private class Blob {
        var area = 0
        var sx = 0L
        var sy = 0L
        var perimeter = 0
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private var prevMean = -1f
    private var frames = 0L
    private var fpsWindowStart = 0L
    private var fpsWindowFrames = 0
    private var fps = 0f
    @Volatile private var closed = false

    private var gw = 0
    private var gh = 0
    private var grid = ByteArray(0)
    private var labels = IntArray(0)
    private var stack = IntArray(0)
    private val blobs = ArrayList<Blob>(64)

    /** Stops emitting; the executor may still be mid-frame when the use case unbinds. */
    fun close() { closed = true }

    override fun analyze(image: ImageProxy) {
        try {
            if (!closed) process(image)
        } catch (_: Throwable) {
            // A malformed frame is not worth the camera session.
        } finally {
            image.close()
        }
    }

    private fun ensureBuffers(w: Int, h: Int) {
        if (w == gw && h == gh) return
        gw = w; gh = h
        val n = w * h
        grid = ByteArray(n)
        labels = IntArray(n)
        stack = IntArray(n)
    }

    private fun process(image: ImageProxy) {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val w = image.width
        val h = image.height
        if (w < SCALE * 8 || h < SCALE * 8) return
        ensureBuffers(w / SCALE, h / SCALE)
        val threshold = if (mode == OpticalMode.GLINT) GLINT_THRESHOLD else IR_THRESHOLD
        val limit = buf.limit()

        // Pass 1: downsample the Y plane, threshold, mean.
        var sum = 0L
        var bright = 0
        for (gy in 0 until gh) {
            val rowBase = gy * SCALE * rowStride
            val gRow = gy * gw
            for (gx in 0 until gw) {
                val idx = rowBase + gx * SCALE * pixStride
                val v = if (idx < limit) buf.get(idx).toInt() and 0xFF else 0
                sum += v
                if (v > threshold) { grid[gRow + gx] = 1; bright++ } else grid[gRow + gx] = 0
            }
        }
        val n = gw * gh
        val mean = sum.toFloat() / n
        val change = if (prevMean > 0f) abs(mean - prevMean) / prevMean else 0f
        prevMean = mean
        val stable = change < LUMA_STABLE_FRACTION
        val washedOut = bright > n * WASHOUT_FRACTION
        val tooBright = mode == OpticalMode.IR && mean > IR_MAX_AMBIENT

        // Pass 2: connected components, then the shape filter.
        blobs.clear()
        Arrays.fill(labels, 0, n, 0)
        val candidates = ArrayList<Track>()
        if (bright > 0 && !washedOut && !tooBright) {
            var label = 0
            for (i in 0 until n) {
                if (grid[i].toInt() == 1 && labels[i] == 0) flood(i, ++label)
            }
            for (b in blobs) {
                if (b.area < MIN_AREA || b.area > MAX_AREA) continue
                val p = b.perimeter.toFloat()
                val circularity = (4f * PI.toFloat() * b.area) / (p * p)
                if (circularity < MIN_CIRCULARITY) continue
                val r = sqrt(b.area / PI.toFloat())
                candidates.add(Track(b.sx.toFloat() / b.area, b.sy.toFloat() / b.area, r, 1, 0))
            }
        }

        // Pass 3: nearest-centroid tracking. Unmatched tracks die — persistence
        // means *consecutive* frames; a spot that blinks is not a lens.
        val matched = BooleanArray(tracks.size)
        val next = ArrayList<Track>(candidates.size)
        for (c in candidates) {
            var best = -1
            var bestD = MATCH_RADIUS * MATCH_RADIUS
            for (ti in tracks.indices) {
                if (matched[ti]) continue
                val t = tracks[ti]
                val dx = t.gx - c.gx
                val dy = t.gy - c.gy
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = ti }
            }
            if (best >= 0) {
                matched[best] = true
                val t = tracks[best]
                t.gx = c.gx; t.gy = c.gy; t.radius = c.radius
                t.persistence = if (stable) t.persistence + 1 else 1
                next.add(t)
            } else {
                next.add(Track(c.gx, c.gy, c.radius, 1, nextId++))
            }
        }
        tracks.clear()
        tracks.addAll(next)

        // Report
        frames++
        val now = SystemClock.elapsedRealtime()
        fpsWindowFrames++
        if (fpsWindowStart == 0L) fpsWindowStart = now
        else if (now - fpsWindowStart >= 1_000L) {
            fps = fpsWindowFrames * 1000f / (now - fpsWindowStart)
            fpsWindowStart = now
            fpsWindowFrames = 0
        }
        val rotation = image.imageInfo.rotationDegrees
        val out = ArrayList<GlintBlob>(tracks.size)
        for (t in tracks) {
            val nx = (t.gx + 0.5f) / gw
            val ny = (t.gy + 0.5f) / gh
            val (vx, vy) = toView(nx, ny, rotation)
            val blob = GlintBlob(
                id = t.id, x = vx, y = vy, radius = t.radius / max(gw, gh),
                persistence = t.persistence,
                confidence = min(1f, t.persistence / CONFIDENCE_FULL_AT)
            )
            out.add(blob)
            // First report at the persistence floor, then every four frames while the
            // spot keeps holding so the finding's confidence climbs with it.
            if (t.persistence >= MIN_PERSISTENCE && (t.reportedAt == 0 || t.persistence - t.reportedAt >= 4)) {
                t.reportedAt = t.persistence
                if (!closed) onCandidate(blob)
            }
        }
        if (!closed) onState(
            OpticalState(
                mode = mode, running = true, torchOn = mode.torch, error = null,
                meanLuma = mean, lumaChange = change, brightPixels = bright,
                washedOut = washedOut, tooBright = tooBright,
                tracks = out, frames = frames, fps = fps
            )
        )
    }

    /** Iterative 4-connected flood fill from [start]; appends the blob's statistics. */
    private fun flood(start: Int, label: Int) {
        val b = Blob()
        var sp = 0
        stack[sp++] = start
        labels[start] = label
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % gw
            val y = i / gw
            b.area++
            b.sx += x
            b.sy += y
            var boundary = false
            if (x > 0) { val j = i - 1; if (grid[j].toInt() == 1) { if (labels[j] == 0) { labels[j] = label; stack[sp++] = j } } else boundary = true } else boundary = true
            if (x < gw - 1) { val j = i + 1; if (grid[j].toInt() == 1) { if (labels[j] == 0) { labels[j] = label; stack[sp++] = j } } else boundary = true } else boundary = true
            if (y > 0) { val j = i - gw; if (grid[j].toInt() == 1) { if (labels[j] == 0) { labels[j] = label; stack[sp++] = j } } else boundary = true } else boundary = true
            if (y < gh - 1) { val j = i + gw; if (grid[j].toInt() == 1) { if (labels[j] == 0) { labels[j] = label; stack[sp++] = j } } else boundary = true } else boundary = true
            if (boundary) b.perimeter++
        }
        blobs.add(b)
    }

    /**
     * Buffer coordinates to display coordinates: the sensor is landscape, the screen
     * is portrait, and the front preview is mirrored.
     */
    private fun toView(nx: Float, ny: Float, rotation: Int): Pair<Float, Float> {
        var x: Float
        var y: Float
        when (rotation) {
            90 -> { x = 1f - ny; y = nx }
            180 -> { x = 1f - nx; y = 1f - ny }
            270 -> { x = ny; y = 1f - nx }
            else -> { x = nx; y = ny }
        }
        if (mirrored) x = 1f - x
        return x to y
    }
}

// ── The scanner ──────────────────────────────────────────────────────────────

/**
 * Every way a phone with no root and no special hardware can find a hidden camera,
 * and the one honest note about the way it cannot ([ANALOG_RF_NOTE]).
 *
 * Findings from all six methods land on [findings], newest first, and anything at
 * or above [ALERT_CONFIDENCE] also goes through [Registry.publishAlert] so it reaches
 * the timeline and the notification shade. Each method keeps its own live state
 * flow for the tab that drives it.
 */
object HiddenCameraScanner {

    private const val FINDINGS_CAP = 200
    private const val ALERT_CONFIDENCE = 0.7f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _findings = MutableStateFlow<List<CameraFinding>>(emptyList())
    val findings: StateFlow<List<CameraFinding>> = _findings.asStateFlow()

    private val _optical = MutableStateFlow(OpticalState())
    val optical: StateFlow<OpticalState> = _optical.asStateFlow()

    private val _network = MutableStateFlow(NetworkScanProgress())
    val network: StateFlow<NetworkScanProgress> = _network.asStateFlow()

    private val _wireless = MutableStateFlow(WirelessState())
    val wireless: StateFlow<WirelessState> = _wireless.asStateFlow()

    private val _magnetic = MutableStateFlow(MagneticState())
    val magnetic: StateFlow<MagneticState> = _magnetic.asStateFlow()

    private val _sweepDone = MutableStateFlow<Set<String>>(emptySet())
    val sweepDone: StateFlow<Set<String>> = _sweepDone.asStateFlow()

    /** Finding ids already raised as alerts, so a climbing confidence re-alerts nobody. */
    private val alerted = Collections.synchronizedSet(HashSet<String>())

    /** Adds or replaces (by id) a finding; alerts once when it is strong enough. */
    fun report(f: CameraFinding) {
        _findings.update { current -> (listOf(f) + current.filter { it.id != f.id }).take(FINDINGS_CAP) }
        if (f.confidence >= ALERT_CONFIDENCE && alerted.add(f.id)) {
            Registry.publishAlert(
                Alert(
                    id = "camera|${f.id}", ts = f.ts,
                    severity = if (f.confidence >= 0.85f) Severity.HIGH else Severity.MEDIUM,
                    title = "Possible hidden camera — ${methodLabel(f.method)}",
                    detail = "${f.location}. ${f.detail}",
                    kind = EventKind.PLATFORM, dedupeKey = "camera|${f.id}"
                ),
                dedupeWindowMs = 6 * 60 * 60_000L
            )
        }
    }

    fun clear(method: CameraFindMethod? = null) {
        if (method == null) _findings.value = emptyList()
        else _findings.update { current -> current.filter { it.method != method } }
    }

    fun methodLabel(m: CameraFindMethod): String = when (m) {
        CameraFindMethod.LENS_GLINT -> "lens glint"
        CameraFindMethod.IR_ILLUMINATOR -> "IR illuminator"
        CameraFindMethod.NETWORK -> "camera on the Wi-Fi"
        CameraFindMethod.BLE_SETUP_AP -> "Bluetooth setup beacon"
        CameraFindMethod.WIFI_SETUP_AP -> "Wi-Fi setup hotspot"
        CameraFindMethod.MAGNETIC -> "magnetic anomaly"
    }

    // ── Optical ──────────────────────────────────────────────────────────────

    /** A fresh session id per camera bind, so track ids from different binds never collide. */
    @Volatile private var opticalSession = 0L

    fun opticalStarting(mode: OpticalMode) {
        opticalSession = System.currentTimeMillis()
        _optical.value = OpticalState(mode = mode, running = true, torchOn = mode.torch)
    }

    fun publishOptical(state: OpticalState) { _optical.value = state }

    fun opticalError(mode: OpticalMode, message: String?) {
        _optical.value = OpticalState(mode = mode, running = false, error = message ?: "Camera could not be opened")
    }

    fun opticalStopped(mode: OpticalMode) { _optical.value = OpticalState(mode = mode, running = false) }

    /** Called by the analyzer when a spot has held still long enough. */
    fun opticalCandidate(context: Context, mode: OpticalMode, blob: GlintBlob) {
        val (lat, lon) = fix(context)
        val px = (blob.x * 100).roundToInt().coerceIn(0, 100)
        val py = (blob.y * 100).roundToInt().coerceIn(0, 100)
        val method = if (mode == OpticalMode.GLINT) CameraFindMethod.LENS_GLINT else CameraFindMethod.IR_ILLUMINATOR
        val detail = if (mode == OpticalMode.GLINT)
            "A steady round pinpoint held still for ${blob.persistence} frames while the scene held still. A lens returns torchlight straight back along its own axis; a shiny edge slides as you move. Step closer: look for a dark pinhole behind the spot."
        else
            "A point of light in a dark scene held still for ${blob.persistence} frames. The eye sees nothing there; a night-vision camera lights the room with infrared LEDs and the front camera's weak IR filter lets them through."
        report(
            CameraFinding(
                id = "optical|${mode.name.lowercase(Locale.US)}|$opticalSession|${blob.id}",
                ts = System.currentTimeMillis(), method = method, confidence = blob.confidence,
                location = "In the viewfinder, $px % from the left and $py % from the top",
                detail = detail, lat = lat, lon = lon
            )
        )
    }

    // ── Network: cameras on the local Wi-Fi ──────────────────────────────────

    private var networkJob: Job? = null

    fun startNetworkScan(context: Context) {
        if (_network.value.running) return
        val app = context.applicationContext
        networkJob = scope.launch { runCatching { scanNetwork(app) } }
    }

    fun cancelNetworkScan() { networkJob?.cancel() }

    private enum class PortState { OPEN, CLOSED, FILTERED }

    /** The ports a first pass hits on every address: the camera protocols plus the two every web UI uses. */
    private val STAGE1_PORTS = intArrayOf(554, 80, 8080, 37777, 34567)
    /** Hosts that answered the first pass are then asked about the rest. */
    private val STAGE2_PORTS = intArrayOf(8554, 10554, 81, 88, 8000, 8081, 8899, 9000, 1935)
    private val HTTP_PORTS = setOf(80, 81, 88, 8000, 8080, 8081, 8899, 9000)
    private val RTSP_PORTS = setOf(554, 8554, 10554)

    private class HttpSignature(val regex: Regex, val label: String, val confidence: Float)

    /** Camera and recorder fingerprints in an HTTP response: titles, Server headers, auth realms, login pages. */
    private val HTTP_SIGNATURES = listOf(
        HttpSignature(Regex("hikvision|hik-connect|/doc/page/login\\.asp|ds-2cd|ds-7"), "Hikvision camera or recorder", 0.92f),
        HttpSignature(Regex("dahua|dh_web|webplugin\\.exe|ipc-hdw|ipc-hfw|imou"), "Dahua / Imou camera", 0.92f),
        HttpSignature(Regex("reolink"), "Reolink camera", 0.92f),
        HttpSignature(Regex("amcrest"), "Amcrest camera", 0.92f),
        HttpSignature(Regex("foscam|ipcam client"), "Foscam camera", 0.92f),
        HttpSignature(Regex("tapo|tp-link ipc|tplink.*camera"), "TP-Link Tapo camera", 0.9f),
        HttpSignature(Regex("ezviz"), "EZVIZ camera", 0.92f),
        HttpSignature(Regex("axis communications|axis.*network camera|/axis-cgi/"), "Axis network camera", 0.92f),
        HttpSignature(Regex("vivotek|mobotix|arecont|geovision|hanwha|wisenet|uniview|tiandy|avigilon|bosch.*camera|panasonic.*network camera|sony.*network camera"), "Professional IP camera", 0.9f),
        HttpSignature(Regex("ubnt|unifi protect|aircam|unifi video"), "Ubiquiti camera", 0.85f),
        HttpSignature(Regex("wyze|yi home|yi_home|kami|wansview|sricam|tenvis|zmodo|lorex|swann|annke|night owl|eufy|arlo|blink"), "Consumer security camera", 0.88f),
        HttpSignature(Regex("netwave|goahead-webs|uc-httpd|boa/0\\.9|xmeye|sofia|v380|yoosee|icsee|camhi|hichip|cloudcam|p2p.*camera"), "Budget IP camera firmware (Netwave / GoAhead / XMeye class)", 0.85f),
        HttpSignature(Regex("mjpg-streamer|mjpeg-streamer|motion-jpeg|videostream\\.cgi|/snapshot\\.cgi|/video\\.cgi|/cgi-bin/hi3510|hi3516|hi3518"), "Camera video stream endpoint", 0.85f),
        HttpSignature(Regex("ip ?camera|ipcam|network camera|web ?camera|webcam|surveillance|cctv"), "Describes itself as a camera", 0.8f),
        HttpSignature(Regex("onvif"), "ONVIF camera interface", 0.8f),
        HttpSignature(Regex("blue iris|zoneminder|shinobi|frigate|motioneye|synology surveillance|agent dvr|ispy"), "Video surveillance server", 0.75f),
        HttpSignature(Regex("\\bnvr\\b|\\bdvr\\b|\\bxvr\\b|video recorder"), "Video recorder (NVR/DVR)", 0.7f)
    )

    private class MdnsHit(val ip: String, val type: String, val name: String, val label: String, val confidence: Float)

    private val MDNS_TYPES = listOf("_rtsp._tcp.", "_axis-video._tcp.", "_psia._tcp.", "_onvif._tcp.", "_hap._tcp.", "_http._tcp.")
    private val MDNS_CAMERA_NAME = Regex("cam(era)?\\b|ipc|wyze|reolink|arlo|blink|eufy|tapo|ezviz|hik|dahua|imou|amcrest|foscam|nest cam|yi home|kami|doorbell|nvr|dvr|axis", RegexOption.IGNORE_CASE)

    private class HostHit(val ip: String) {
        val openPorts: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        @Volatile var rtsp: String? = null
        @Volatile var rtspPort: Int = 0
        @Volatile var http: HttpSignature? = null
        @Volatile var httpServer: String? = null
        @Volatile var httpPort: Int = 0
        @Volatile var onvif: String? = null
        val mdns = Collections.synchronizedList(ArrayList<MdnsHit>())
    }

    /**
     * Sweeps the Wi-Fi subnet the phone is on for anything that speaks camera.
     *
     * Three independent probes run together: a TCP connect sweep of the camera ports
     * (RTSP, the Dahua and XMeye recorder ports, the web ports) with banners fetched
     * from whatever answers; an ONVIF WS-Discovery multicast probe, which every
     * ONVIF camera must answer; and mDNS browsing for `_rtsp`, `_axis-video`, `_psia`,
     * `_onvif`, HomeKit cameras (`_hap` with category 17/18) and web servers whose
     * name says camera. A host with only a web UI and no camera fingerprint is not
     * reported — that is every printer and router. Everything is bound to the Wi-Fi
     * [Network] so mobile data cannot swallow the probes.
     */
    suspend fun scanNetwork(context: Context): List<CameraFinding> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val wifiNet: Network? = cm?.let { m ->
            @Suppress("DEPRECATION")
            runCatching { m.allNetworks.toList() }.getOrDefault(emptyList()).firstOrNull { n ->
                m.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }
        val link: LinkProperties? = wifiNet?.let { cm?.getLinkProperties(it) }
        val v4 = link?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        if (wifiNet == null || link == null || v4 == null) {
            _network.update { it.copy(running = false, error = "Not on Wi-Fi — join the venue's network first, then scan", phase = "") }
            return@withContext emptyList()
        }
        val selfIp = v4.address.hostAddress ?: ""
        val gateway = link.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val ssid = Registry.wifiStatus.value.connection?.ssid
            ?: runCatching {
                @Suppress("DEPRECATION")
                app.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.trim('"')
            }.getOrNull()?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }

        // The /24 round our own address, even on a wider network: 254 hosts is a
        // sweep, 65 000 is a denial of service on the venue's router.
        val ipInt = ipToInt(v4.address.address)
        val prefix = max(v4.prefixLength, 24)
        val mask = if (prefix >= 32) -1 else (-1 shl (32 - prefix))
        val base = ipInt and mask
        val count = if (prefix >= 32) 1 else (1 shl (32 - prefix))
        val hosts = (1 until count - 1).map { base + it }.filter { it != ipInt }.map { intToIp(it) }
        val subnet = "${intToIp(base)}/$prefix"

        _network.value = NetworkScanProgress(
            running = true, phase = "Probing ${hosts.size} addresses", hostsTotal = hosts.size,
            subnet = subnet, ssid = ssid, lastScanTs = _network.value.lastScanTs
        )

        val hits = ConcurrentHashMap<String, HostHit>()
        fun hit(ip: String): HostHit = hits.computeIfAbsent(ip) { HostHit(ip) }
        val (lat, lon) = fix(app)
        val out = ArrayList<CameraFinding>()
        try {
            coroutineScope {
                val onvif = async { runCatching { onvifProbe(wifiNet, 3_000L) }.getOrDefault(emptyMap()) }
                val mdns = async { runCatching { discoverMdns(app, 4_500L) }.getOrDefault(emptyList()) }

                // Stage 1: every address, the five ports that matter. A refused
                // connection means the host is up; a timeout means nobody's home.
                val alive = ConcurrentHashMap.newKeySet<String>()
                val sem = Semaphore(64)
                val done = AtomicInteger()
                hosts.map { ip ->
                    launch {
                        var up = false
                        for (port in STAGE1_PORTS) {
                            val st = sem.withPermit { probe(wifiNet, ip, port, 350) }
                            if (st != PortState.FILTERED) up = true
                            if (st == PortState.OPEN) hit(ip).openPorts.add(port)
                        }
                        if (up) alive.add(ip)
                        val d = done.incrementAndGet()
                        _network.update { it.copy(hostsDone = d, liveHosts = alive.size) }
                    }
                }.joinAll()

                // Stage 2: the live hosts get the long port list and banners.
                _network.update { it.copy(phase = "Fingerprinting ${alive.size} live hosts", hostsDone = 0, hostsTotal = alive.size, liveHosts = alive.size) }
                val done2 = AtomicInteger()
                alive.map { ip ->
                    launch {
                        for (port in STAGE2_PORTS) {
                            if (sem.withPermit { probe(wifiNet, ip, port, 350) } == PortState.OPEN) hit(ip).openPorts.add(port)
                        }
                        val h = hits[ip]
                        if (h != null) {
                            for (port in h.openPorts.filter { it in RTSP_PORTS }.sorted()) {
                                val banner = sem.withPermit { rtspBanner(wifiNet, ip, port) }
                                if (banner != null) { h.rtsp = banner; h.rtspPort = port; break }
                            }
                            for (port in h.openPorts.filter { it in HTTP_PORTS }.sorted()) {
                                val body = sem.withPermit { httpBanner(wifiNet, ip, port) } ?: continue
                                val lower = body.lowercase(Locale.US)
                                val sig = HTTP_SIGNATURES.firstOrNull { it.regex.containsMatchIn(lower) }
                                if (h.httpServer == null) h.httpServer = headerValue(body, "Server")
                                if (sig != null && (h.http == null || sig.confidence > h.http!!.confidence)) { h.http = sig; h.httpPort = port }
                            }
                        }
                        _network.update { it.copy(hostsDone = done2.incrementAndGet()) }
                    }
                }.joinAll()

                _network.update { it.copy(phase = "Waiting for ONVIF and mDNS answers") }
                for ((ip, scopes) in onvif.await()) hit(ip).onvif = scopes
                for (m in mdns.await()) hit(m.ip).mdns.add(m)
            }

            // Judge each host.
            val now = System.currentTimeMillis()
            for (h in hits.values) {
                var conf = 0f
                val reasons = ArrayList<String>()
                h.onvif?.let { conf = max(conf, 0.95f); reasons += "Answered an ONVIF discovery probe ($it)" }
                h.rtsp?.let { conf = max(conf, 0.85f); reasons += "RTSP video server on port ${h.rtspPort}: $it" }
                h.http?.let {
                    conf = max(conf, it.confidence)
                    val server = h.httpServer
                    val serverNote = if (server != null) ", server \"$server\"" else ""
                    reasons += "${it.label} (web UI on port ${h.httpPort}$serverNote)"
                }
                for (m in h.mdns.toList()) { conf = max(conf, m.confidence); reasons += "${m.label} — advertises \"${m.name}\" as ${m.type.trimEnd('.')}" }
                if (37777 in h.openPorts && h.http == null) { conf = max(conf, 0.5f); reasons += "Dahua-protocol port 37777 open" }
                if (34567 in h.openPorts && h.http == null) { conf = max(conf, 0.5f); reasons += "XMeye/Sofia recorder port 34567 open" }
                if (1935 in h.openPorts) { conf = max(conf, 0.35f); reasons += "RTMP streaming port 1935 open" }
                if (h.rtsp == null && h.openPorts.any { it in RTSP_PORTS }) { conf = max(conf, 0.4f); reasons += "RTSP port ${h.openPorts.first { it in RTSP_PORTS }} open but silent" }
                if (conf <= 0f) continue
                // The router runs a web UI and sometimes a media port; only hard
                // evidence makes it a camera.
                if (h.ip == gateway && conf < 0.8f) continue
                val ports = h.openPorts.sorted().joinToString(", ")
                out += CameraFinding(
                    id = "net|${h.ip}", ts = now, method = CameraFindMethod.NETWORK, confidence = conf,
                    location = "${h.ip} on ${ssid ?: "this Wi-Fi"}" + if (h.ip == gateway) " (the router)" else "",
                    detail = reasons.joinToString(". ") + (if (ports.isNotEmpty()) ". Open ports: $ports" else "") +
                        ". A camera on the venue's network is streaming to somebody; if you did not put it there, ask who did.",
                    lat = lat, lon = lon
                )
            }
            out.sortByDescending { it.confidence }
            // Replace the previous network sweep rather than stacking stale hosts.
            _findings.update { current -> (out + current.filter { it.method != CameraFindMethod.NETWORK }).take(FINDINGS_CAP) }
            out.forEach { f -> if (f.confidence >= ALERT_CONFIDENCE) report(f) }
            _network.update { it.copy(found = out.size, error = null) }
            out
        } finally {
            _network.update { it.copy(running = false, phase = "", lastScanTs = System.currentTimeMillis(), hostsDone = it.hostsTotal) }
        }
    }

    private fun ipToInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    private fun intToIp(i: Int): String = "${(i ushr 24) and 255}.${(i ushr 16) and 255}.${(i ushr 8) and 255}.${i and 255}"

    private fun probe(network: Network, ip: String, port: Int, timeoutMs: Int): PortState {
        val s: Socket = try { network.socketFactory.createSocket() } catch (_: Exception) { return PortState.FILTERED }
        return try {
            s.connect(InetSocketAddress(ip, port), timeoutMs)
            PortState.OPEN
        } catch (e: ConnectException) {
            // Refused is a live host with the port closed; unreachable is an empty address.
            if (e.message?.contains("ECONNREFUSED") == true || e.message?.contains("refused", ignoreCase = true) == true) PortState.CLOSED else PortState.FILTERED
        } catch (_: SocketTimeoutException) {
            PortState.FILTERED
        } catch (_: Exception) {
            PortState.FILTERED
        } finally {
            runCatching { s.close() }
        }
    }

    /** `OPTIONS` is the one RTSP request every server answers, logged in or not. */
    private fun rtspBanner(network: Network, ip: String, port: Int): String? {
        val s: Socket = try { network.socketFactory.createSocket() } catch (_: Exception) { return null }
        return try {
            s.connect(InetSocketAddress(ip, port), 700)
            s.soTimeout = 1_500
            s.getOutputStream().apply {
                write("OPTIONS rtsp://$ip:$port/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: Aegis\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val buf = ByteArray(1024)
            val n = s.getInputStream().read(buf)
            if (n <= 0) return null
            val text = String(buf, 0, n, Charsets.ISO_8859_1)
            if (!text.startsWith("RTSP/")) return null
            val status = text.lineSequence().firstOrNull()?.trim() ?: return null
            val server = headerValue(text, "Server")
            if (server != null) "$status, server \"$server\"" else status
        } catch (_: Exception) {
            null
        } finally {
            runCatching { s.close() }
        }
    }

    /** The first 4 KB of `GET /` — enough for the Server header, the realm and the title. */
    private fun httpBanner(network: Network, ip: String, port: Int): String? {
        val s: Socket = try { network.socketFactory.createSocket() } catch (_: Exception) { return null }
        return try {
            s.connect(InetSocketAddress(ip, port), 700)
            s.soTimeout = 1_500
            s.getOutputStream().apply {
                write("GET / HTTP/1.0\r\nHost: $ip\r\nUser-Agent: Aegis\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val input = s.getInputStream()
            val buf = ByteArray(4096)
            var total = 0
            val deadline = SystemClock.elapsedRealtime() + 2_000L
            while (total < buf.size && SystemClock.elapsedRealtime() < deadline) {
                val n = try { input.read(buf, total, buf.size - total) } catch (_: SocketTimeoutException) { break }
                if (n < 0) break
                total += n
            }
            if (total <= 0) null else String(buf, 0, total, Charsets.ISO_8859_1)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { s.close() }
        }
    }

    private fun headerValue(response: String, name: String): String? {
        val head = response.substringBefore("\r\n\r\n")
        return head.lineSequence().drop(1)
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
    }

    /**
     * WS-Discovery: one multicast `Probe` for `NetworkVideoTransmitter` to
     * 239.255.255.250:3702; every ONVIF device answers with a unicast `ProbeMatch`
     * carrying its scopes (name, hardware) and service address.
     */
    private fun onvifProbe(network: Network, listenMs: Long): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        val sock = DatagramSocket()
        try {
            runCatching { network.bindSocket(sock) }
            sock.soTimeout = 400
            val probe = """<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""
                .toByteArray(Charsets.UTF_8)
            val group = InetAddress.getByName("239.255.255.250")
            sock.send(DatagramPacket(probe, probe.size, group, 3702))
            // A second probe a moment later catches devices that missed the first.
            val deadline = SystemClock.elapsedRealtime() + listenMs
            var resent = false
            val buf = ByteArray(8192)
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!resent && SystemClock.elapsedRealtime() > deadline - listenMs / 2) {
                    resent = true
                    runCatching { sock.send(DatagramPacket(probe, probe.size, group, 3702)) }
                }
                val pkt = DatagramPacket(buf, buf.size)
                try { sock.receive(pkt) } catch (_: SocketTimeoutException) { continue }
                val ip = pkt.address?.hostAddress ?: continue
                val xml = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                if (!xml.contains("ProbeMatch", ignoreCase = true)) continue
                val name = Regex("onvif://www\\.onvif\\.org/name/([^ <\"]+)").find(xml)?.groupValues?.get(1)?.let { urlDecode(it) }
                val hardware = Regex("onvif://www\\.onvif\\.org/hardware/([^ <\"]+)").find(xml)?.groupValues?.get(1)?.let { urlDecode(it) }
                val xaddr = Regex("<[^>]*XAddrs>([^<]+)<").find(xml)?.groupValues?.get(1)?.trim()?.substringBefore(' ')
                val desc = listOfNotNull(name, hardware, xaddr).distinct().joinToString(" · ").ifEmpty { "ONVIF device" }
                found.putIfAbsent(ip, desc.take(120))
            }
        } finally {
            runCatching { sock.close() }
        }
        return found
    }

    private fun urlDecode(s: String): String = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** Browses the camera-shaped mDNS service types for [listenMs]; resolves one hit at a time, as NsdManager insists. */
    private suspend fun discoverMdns(app: Context, listenMs: Long): List<MdnsHit> {
        val nsd = app.getSystemService(NsdManager::class.java) ?: return emptyList()
        val hits = Collections.synchronizedList(ArrayList<MdnsHit>())
        val resolveLock = Mutex()
        coroutineScope {
            for (type in MDNS_TYPES) {
                launch {
                    val listener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) {}
                        override fun onDiscoveryStopped(serviceType: String) {}
                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                            // Cheap pre-filter: a web server is only interesting if its name says camera.
                            if (type == "_http._tcp." && !MDNS_CAMERA_NAME.containsMatchIn(serviceInfo.serviceName ?: "")) return
                            launch {
                                val resolved = resolveLock.withLock { resolveMdns(nsd, serviceInfo) } ?: return@launch
                                classifyMdns(type, resolved)?.let { hits.add(it) }
                            }
                        }
                    }
                    val started = runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
                    if (!started) return@launch
                    try { delay(listenMs) } finally { runCatching { nsd.stopServiceDiscovery(listener) } }
                }
            }
        }
        return hits.toList()
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveMdns(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(2_500L) {
        suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
            val ok = runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) { if (cont.isActive) cont.resume(serviceInfo) }
                })
            }.isSuccess
            if (!ok && cont.isActive) cont.resume(null)
        }
    }

    @Suppress("DEPRECATION")
    private fun classifyMdns(type: String, info: NsdServiceInfo): MdnsHit? {
        val ip = info.host?.hostAddress?.substringBefore('%') ?: return null
        if (info.host !is Inet4Address) return null
        val name = info.serviceName ?: ""
        return when (type) {
            "_rtsp._tcp." -> MdnsHit(ip, type, name, "RTSP video stream advertised over mDNS", 0.8f)
            "_axis-video._tcp." -> MdnsHit(ip, type, name, "Axis camera advertised over mDNS", 0.9f)
            "_psia._tcp." -> MdnsHit(ip, type, name, "PSIA camera interface advertised over mDNS", 0.85f)
            "_onvif._tcp." -> MdnsHit(ip, type, name, "ONVIF camera advertised over mDNS", 0.9f)
            "_hap._tcp." -> {
                // HomeKit accessory category: 17 is an IP camera, 18 a video doorbell.
                val ci = info.attributes?.get("ci")?.let { String(it, Charsets.UTF_8).trim() }
                when (ci) {
                    "17" -> MdnsHit(ip, type, name, "HomeKit IP camera", 0.9f)
                    "18" -> MdnsHit(ip, type, name, "HomeKit video doorbell", 0.85f)
                    else -> null
                }
            }
            "_http._tcp." -> if (MDNS_CAMERA_NAME.containsMatchIn(name)) MdnsHit(ip, type, name, "Web server named like a camera", 0.6f) else null
            else -> null
        }
    }

    // ── Wireless: unprovisioned cameras announcing themselves ────────────────

    private class ApRule(pattern: String, val label: String, val confidence: Float) {
        val regex = Regex(pattern)
    }

    /**
     * SSIDs a camera broadcasts before it has been given the venue's Wi-Fi. Matched
     * against the lower-cased whole name, anchored at the start: an ordinary network
     * that happens to contain "cam" (Camden, campus) must not fire.
     */
    private val AP_RULES = listOf(
        ApRule("^(ipc|ipcam|ip_cam|ip-cam|ipcamera|ip_camera|netcam|hdcam|hd_cam|hd-cam|minicam|mini_cam|spycam|spy_cam|smartcam|smart_cam|wificam|wifi_cam|wifi-cam|camhi|cam_hi|camera|cam)[-_ ]?[0-9a-f]{4,}$", "Generic IP camera setup hotspot", 0.85f),
        ApRule("^(a9|q7|q8|q15|sq\\d{1,2}|x5|xd|hdq|ccq|cmdc|bpr|lookcam|hdwificam(pro)?|pccam|p2p|eye4|ac\\d{2})[-_ ]?[0-9a-z]{2,}$", "Covert mini camera (A9 / SQ / Q7 class) in setup mode", 0.95f),
        ApRule("^(a9|q7|sq\\d{1,2}|lookcam|hdwificam(pro)?)$", "Covert mini camera (A9 / SQ / Q7 class) in setup mode", 0.9f),
        ApRule("^(wyze|wyzecam|wyze_cam)([-_ ].*)?$", "Wyze camera in setup mode", 0.9f),
        ApRule("^(yi|kami)[-_ ]?(home|cam|dome|outdoor|smart|pro)[-_ ]?.*$", "YI / Kami camera", 0.9f),
        ApRule("^reolink([-_ ].*)?$", "Reolink camera", 0.9f),
        ApRule("^amcrest([-_ ].*)?$", "Amcrest camera", 0.9f),
        ApRule("^foscam([-_ ].*)?$", "Foscam camera", 0.9f),
        ApRule("^(tapo_c\\d|tapo_d\\d|tapo_cam|tp-link_ipc|tplink_ipc|tp-link_camera).*$", "TP-Link Tapo camera", 0.9f),
        ApRule("^(ezviz|hik-|hikvision|ds-2cd|ds-2de|hik_).*$", "Hikvision / EZVIZ camera", 0.9f),
        ApRule("^(dh-|dahua|imou|ipc-hdw|ipc-hfw|dh_ipc).*$", "Dahua / Imou camera", 0.9f),
        ApRule("^(arlo|blink|eufy_cam|eufycam|eufy[-_ ]?security|ring-|ring_|ringsetup|lorex|swann|zmodo|annke|night ?owl|wansview|sricam|tenvis|vimtag|dlink.*cam|dcs-\\d{3,4})([-_ ].*)?$", "Consumer security camera in setup mode", 0.85f),
        ApRule("^(v380|yoosee|icsee|xm-|xmeye|gw_|carecam|camhipro|hichip|ipc365|cloudedge|cloudcam|littlelf|tuya_ipc|tuyasmart|smartlife|smart_life|jooan|besder|anran|hiseeu|zosi|sv3c|xvim|iegeek)([-_ ].*)?$", "Budget Wi-Fi camera (V380 / Yoosee / iCSee / XMeye / Tuya) in setup mode", 0.85f),
        ApRule("^(mi camera|mi_camera|xiaomi_cam|xiaomi-cam|mijia_cam|chuangmi|isa-camera|isa_camera|imilab|aqara[-_ ]?cam|aqara[-_ ]?g\\d)([-_ ].*)?$", "Xiaomi / Aqara camera", 0.85f),
        ApRule("^(nvr|dvr|hdnvr|xvr)[-_ ]?[0-9a-z]*$", "Video recorder (NVR/DVR) hotspot", 0.7f),
        ApRule("^direct-[0-9a-z]{2}-.*(cam|ipc|camera).*$", "Wi-Fi Direct camera", 0.7f),
        ApRule("^(dashcam|dash_cam|dash-cam|viofo|70mai|nextbase|blackvue|ddpai|thinkware|azdome|vantrue|rove|garmin[-_ ]?dash).*$", "Dash camera hotspot (usually a parked car)", 0.5f),
        ApRule("^(gopro|insta360|dji[-_ ]|osmo|akaso|hero\\d).*$", "Action camera hotspot", 0.45f),
        ApRule("^(canon|nikon|sony|fujifilm|panasonic|olympus|lumix|om-d|eos)[-_ ].*$", "Handheld camera's hotspot", 0.3f)
    )

    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val CAMERA_TOKENS = setOf("cam", "ipc", "ipcam", "camera", "webcam", "spycam", "minicam", "hdcam", "netcam", "nvr", "dvr")

    private var wifiJob: Job? = null

    fun startWifiSetupScan(context: Context) {
        if (_wireless.value.wifiScanning) return
        val app = context.applicationContext
        wifiJob = scope.launch { runCatching { scanWifiSetupAps(app) } }
    }

    /**
     * Refreshes the platform's scan cache and reads it for camera setup hotspots. An
     * unprovisioned camera is usually an *open* access point with the maker's name
     * and the last bytes of its MAC; a venue's own Wi-Fi never looks like that.
     */
    suspend fun scanWifiSetupAps(context: Context): List<CameraFinding> {
        val app = context.applicationContext
        _wireless.update { it.copy(wifiScanning = true, wifiError = null) }
        val out = ArrayList<CameraFinding>()
        try {
            val wifi = app.getSystemService(WifiManager::class.java)
            if (wifi == null) { _wireless.update { it.copy(wifiError = "No Wi-Fi service on this device") }; return out }
            if (!wifi.isWifiEnabled) { _wireless.update { it.copy(wifiError = "Wi-Fi is turned off") }; return out }
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                _wireless.update { it.copy(wifiError = "Location permission needed to read network names") }; return out
            }
            if (runCatching { app.getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull() != true) {
                _wireless.update { it.copy(wifiError = "Location is turned off — Android hides network names without it") }; return out
            }
            WifiScanner.requestScan(app)
            @Suppress("DEPRECATION")
            val results = try { wifi.scanResults } catch (_: SecurityException) { null } ?: emptyList()
            val (lat, lon) = fix(app)
            val now = System.currentTimeMillis()
            for (r in results) {
                @Suppress("DEPRECATION")
                val raw = r.SSID?.trim()?.trim('"').orEmpty()
                val bssid = r.BSSID?.lowercase(Locale.US) ?: continue
                if (raw.isEmpty()) continue
                val ssid = raw.lowercase(Locale.US)
                val open = WifiScanner.securityOf(r.capabilities) == WifiSecurity.OPEN
                val rule = AP_RULES.firstOrNull { it.regex.matches(ssid) }
                var conf = rule?.confidence ?: 0f
                var label = rule?.label
                if (rule == null && open) {
                    // An open network whose first word is a camera word: not a maker we
                    // know, but nothing else broadcasts an open "CAM-3F9A".
                    val tokens = ssid.split(NON_ALNUM).filter { it.isNotEmpty() }
                    if (tokens.isNotEmpty() && tokens.first() in CAMERA_TOKENS) { conf = 0.6f; label = "Open hotspot named like a camera" }
                }
                if (label == null) continue
                if (open) conf = min(1f, conf + 0.05f)
                val metres = approxMetres(r.level)
                out += CameraFinding(
                    id = "wifiap|$bssid", ts = now, method = CameraFindMethod.WIFI_SETUP_AP, confidence = conf,
                    location = "\"$raw\" at ${r.level} dBm — roughly ${fmtMetres(metres)} away" + if (r.level >= -50) ", in this room" else "",
                    detail = "$label. ${if (open) "Open network" else "Encrypted network"}, ${WifiScanner.bandFor(r.frequency)}, BSSID $bssid. " +
                        "A camera broadcasts its own hotspot until someone pairs it with the venue's Wi-Fi; one that is still broadcasting was planted recently or never finished setup. Walk toward the strongest signal.",
                    lat = lat, lon = lon
                )
            }
            out.sortByDescending { it.confidence }
            _findings.update { current -> (out + current.filter { it.method != CameraFindMethod.WIFI_SETUP_AP }).take(FINDINGS_CAP) }
            out.forEach { f -> if (f.confidence >= ALERT_CONFIDENCE) report(f) }
            _wireless.update { it.copy(wifiScannedTs = now, wifiApsSeen = results.size, wifiError = null) }
            return out
        } finally {
            _wireless.update { it.copy(wifiScanning = false) }
        }
    }

    private class BleRule(pattern: String, val label: String, val confidence: Float) {
        val regex = Regex(pattern)
    }

    /** Advertised BLE names, lower-cased, anchored at the start. */
    private val BLE_NAME_RULES = listOf(
        BleRule("^(wyze|wyzecam|wyze_cam).*", "Wyze camera pairing over Bluetooth", 0.85f),
        BleRule("^(arlo|blink|eufy|eufycam|eufy_cam|reolink|amcrest|foscam|tapo|ezviz|imou|lorex|swann|zmodo|annke|wansview|vimtag).*", "Security camera pairing over Bluetooth", 0.8f),
        BleRule("^(nest cam|nest_cam|google nest cam|nest doorbell|ring video doorbell|ring doorbell|ring-cam|ring cam).*", "Nest / Ring camera pairing over Bluetooth", 0.8f),
        BleRule("^(yi|kami)[-_ ](home|cam|dome|outdoor|smart|pro).*", "YI / Kami camera pairing over Bluetooth", 0.8f),
        BleRule("^(ipc|ipcam|ip_cam|ip-cam|camhi|v380|yoosee|icsee|xmeye|lookcam|a9[-_ ]|sq\\d{1,2}[-_ ]|minicam|spycam|hdcam).*", "Budget / covert Wi-Fi camera pairing over Bluetooth", 0.85f),
        BleRule("^(mi camera|mi_camera|xiaomi camera|chuangmi|imilab|aqara camera|aqara-cam|aqara g\\d).*", "Xiaomi / Aqara camera pairing over Bluetooth", 0.8f),
        BleRule("^(camera|cam|webcam)[-_ ]?[0-9a-f]{4,}$", "Device named like a camera pairing over Bluetooth", 0.7f),
        BleRule("^(gopro|insta360|osmo|dji|akaso)[-_ ].*|^(gopro|insta360)$", "Action camera (GoPro / Insta360 / DJI)", 0.5f),
        BleRule("^(70mai|viofo|blackvue|nextbase|ddpai|thinkware|dashcam|dash cam).*", "Dash camera", 0.45f)
    )

    /** 16-bit service UUIDs that mark a device as unprovisioned IoT; weak alone, so they rank low. */
    private val BLE_SERVICE_RULES = mapOf(
        "0000fea6-0000-1000-8000-00805f9b34fb" to ("GoPro camera control service" to 0.55f),
        "0000fd50-0000-1000-8000-00805f9b34fb" to ("Tuya smart-home device (budget cameras, plugs, bulbs) advertising for setup" to 0.3f),
        "0000fe95-0000-1000-8000-00805f9b34fb" to ("Xiaomi Mi Home device advertising for setup" to 0.25f),
        "0000ffff-0000-1000-8000-00805f9b34fb" to ("Espressif BluFi — an unprovisioned Wi-Fi IoT module" to 0.3f)
    )

    private var bleJob: Job? = null

    fun startBleListening(context: Context, durationMs: Long = 20_000L) {
        if (_wireless.value.bleListening) return
        val app = context.applicationContext
        bleJob = scope.launch { runCatching { listenBle(app, durationMs) } }
    }

    fun stopBleListening() { bleJob?.cancel() }

    /**
     * Listens for [durationMs] to every BLE advertisement in range — its own
     * low-latency scan plus whatever the tracker service is already publishing on
     * [Registry.scans] — and reports the ones that name a camera or carry a
     * provisioning service. Names come from the advertisement, not from
     * `BluetoothDevice.getName`, which would need the CONNECT permission.
     */
    suspend fun listenBle(context: Context, durationMs: Long = 20_000L) {
        val app = context.applicationContext
        val until = System.currentTimeMillis() + durationMs
        _wireless.update { it.copy(bleListening = true, bleUntilTs = until, bleDevicesSeen = 0, bleError = null) }
        val seen = Collections.synchronizedSet(HashSet<String>())
        val reported = Collections.synchronizedSet(HashSet<String>())
        val (lat, lon) = fix(app)
        val handle: (ScanResult) -> Unit = { r ->
            val address = r.device?.address
            if (address != null) {
                if (seen.add(address)) _wireless.update { it.copy(bleDevicesSeen = seen.size) }
                val record = r.scanRecord
                val name = record?.deviceName?.trim()?.takeIf { it.isNotEmpty() }
                val lower = name?.lowercase(Locale.US)
                var label: String? = null
                var conf = 0f
                if (lower != null) BLE_NAME_RULES.firstOrNull { it.regex.matches(lower) }?.let { label = it.label; conf = it.confidence }
                if (label == null) {
                    record?.serviceUuids?.forEach { pu ->
                        BLE_SERVICE_RULES[pu.uuid.toString().lowercase(Locale.US)]?.let { (l, c) -> if (c > conf) { label = l; conf = c } }
                    }
                }
                val finalLabel = label
                if (finalLabel != null && reported.add(address)) {
                    val metres = approxMetres(r.rssi)
                    report(
                        CameraFinding(
                            id = "ble|$address", ts = System.currentTimeMillis(), method = CameraFindMethod.BLE_SETUP_AP, confidence = conf,
                            location = "${name ?: "Unnamed device"} ($address) at ${r.rssi} dBm — roughly ${fmtMetres(metres)} away" + if (r.rssi >= -50) ", in this room" else "",
                            detail = "$finalLabel. Cameras advertise over Bluetooth while they wait for an app to hand them Wi-Fi credentials; a working camera has usually stopped. Follow the signal strength to find it.",
                            lat = lat, lon = lon
                        )
                    )
                }
            }
        }
        try {
            coroutineScope {
                launch {
                    withTimeoutOrNull(durationMs) {
                        bleScanFlow(app)
                            .catch { e -> _wireless.update { it.copy(bleError = e.message ?: "Bluetooth scan failed") } }
                            .collect { handle(it) }
                    }
                }
                launch { withTimeoutOrNull(durationMs) { Registry.scans.collect { handle(it) } } }
            }
        } finally {
            _wireless.update { it.copy(bleListening = false, bleUntilTs = 0L) }
        }
    }

    private fun bleScanFlow(app: Context): Flow<ScanResult> = callbackFlow {
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { close(IllegalStateException("Bluetooth is off")); return@callbackFlow }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            close(SecurityException("Nearby devices permission not granted")); return@callbackFlow
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { close(IllegalStateException("Bluetooth LE scanner unavailable")); return@callbackFlow }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { trySend(result) }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { trySend(it) } }
            override fun onScanFailed(errorCode: Int) { close(IllegalStateException("Bluetooth scan failed (code $errorCode)")) }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setLegacy(false).setReportDelay(0).build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) { close(e); return@callbackFlow }
        catch (e: IllegalStateException) { close(e); return@callbackFlow }
        awaitClose {
            try { scanner.stopScan(callback) } catch (_: SecurityException) {} catch (_: IllegalStateException) {}
        }
    }

    // ── Magnetic: a sweep with the phone's compass ───────────────────────────

    private class MagneticListener(private val context: Context) : SensorEventListener {
        companion object {
            const val WARMUP = 40
            const val WINDOW = 96
            const val MIN_DELTA_UT = 18f
            const val SUSTAIN_MS = 250L
            const val COOLDOWN_MS = 1_500L
            const val ALPHA = 0.03f
            const val FULL_CONFIDENCE_UT = 200f
        }

        private val window = FloatArray(WINDOW)
        private var wi = 0
        private var wn = 0
        private var baseline = 0f
        private var warm = 0
        private var onsetTs = 0L
        private var triggered = false
        private var peak = 0f
        private var episodeId = 0L
        private var episodeEnded = 0L
        @Volatile var unreliable = false
        private val lat: Double?
        private val lon: Double?

        init {
            val (la, lo) = fix(context)
            lat = la; lon = lo
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            unreliable = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
        }

        private fun push(v: Float) {
            window[wi] = v
            wi = (wi + 1) % WINDOW
            if (wn < WINDOW) wn++
        }

        private fun sigma(): Float {
            if (wn < 8) return 0f
            var s = 0.0
            for (i in 0 until wn) s += window[i]
            val m = s / wn
            var v = 0.0
            for (i in 0 until wn) { val d = window[i] - m; v += d * d }
            return sqrt(v / wn).toFloat()
        }

        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
            val mag = sqrt(x * x + y * y + z * z)
            val now = SystemClock.elapsedRealtime()

            if (warm < WARMUP) {
                warm++
                baseline = if (warm == 1) mag else baseline + (mag - baseline) / warm
                push(mag)
                _magnetic.value = MagneticState(active = true, fieldUt = mag, baselineUt = baseline, unreliable = unreliable, warmingUp = true)
                return
            }

            val threshold = max(MIN_DELTA_UT, 4f * sigma())
            val delta = abs(mag - baseline)
            if (!triggered) {
                if (delta > threshold) {
                    if (onsetTs == 0L) onsetTs = now
                    else if (now - onsetTs >= SUSTAIN_MS && now - episodeEnded >= COOLDOWN_MS) {
                        triggered = true
                        peak = delta
                        episodeId = System.currentTimeMillis()
                        emit(threshold)
                    }
                } else {
                    onsetTs = 0L
                    // The baseline only learns from quiet samples, so the thing we
                    // are hunting cannot teach the detector to ignore it.
                    baseline += ALPHA * (mag - baseline)
                    push(mag)
                }
            } else {
                if (delta > peak) { peak = delta; emit(threshold) }
                if (delta < threshold / 2f) {
                    triggered = false
                    onsetTs = 0L
                    episodeEnded = now
                }
            }
            _magnetic.value = MagneticState(
                active = true, fieldUt = mag, baselineUt = baseline, deltaUt = delta, thresholdUt = threshold,
                peakUt = peak, triggered = triggered, unreliable = unreliable, warmingUp = false
            )
        }

        private fun emit(threshold: Float) {
            // A magnet is not a camera: speakers, mounts and door catches all move the
            // needle. This is a hint about where to look, so it caps well below alert level.
            val conf = (peak / FULL_CONFIDENCE_UT).coerceIn(0.15f, 0.6f)
            report(
                CameraFinding(
                    id = "mag|$episodeId", ts = episodeId, method = CameraFindMethod.MAGNETIC, confidence = conf,
                    location = "Where the phone was when the needle jumped",
                    detail = "Field rose ${peak.roundToInt()} µT above the ${baseline.roundToInt()} µT baseline (trigger ${threshold.roundToInt()} µT). " +
                        "Covert cameras carry a speaker magnet or a magnetic mount; so do real speakers, door catches and steel studs. Look for a lens at this spot.",
                    lat = lat, lon = lon
                )
            )
        }
    }

    private var magListener: MagneticListener? = null
    private var magManager: SensorManager? = null

    /** Starts the magnetometer sweep; call from the main thread (sensor events land on it). */
    fun startMagnetic(context: Context) {
        stopMagnetic()
        val app = context.applicationContext
        val sm = app.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (sm == null || sensor == null) {
            _magnetic.value = MagneticState(active = false, error = "This phone has no magnetometer")
            return
        }
        val l = MagneticListener(app)
        val ok = runCatching { sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME) }.getOrDefault(false)
        if (!ok) {
            _magnetic.value = MagneticState(active = false, error = "Magnetometer refused to start")
            return
        }
        magListener = l
        magManager = sm
        _magnetic.value = MagneticState(active = true, warmingUp = true)
    }

    fun stopMagnetic() {
        magListener?.let { l -> runCatching { magManager?.unregisterListener(l) } }
        magListener = null
        magManager = null
        _magnetic.update { it.copy(active = false, triggered = false, warmingUp = false) }
    }

    // ── Guided sweep ─────────────────────────────────────────────────────────

    val SWEEP_STEPS: List<SweepStep> = listOf(
        SweepStep("sightlines", "Stand where you are exposed",
            "Bed, shower, toilet, desk, the spot where you change. A camera has to see its target — stand there and look back at everything that can see you. That is the search area.", "Eyes"),
        SweepStep("lights", "Lights off, blinds closed",
            "Let your eyes adjust for a minute, then look slowly around at eye height and up. Most covert cameras have a tiny status LED or an IR glow that is lost under room lighting.", "Eyes"),
        SweepStep("glint", "Glint sweep with the torch",
            "OPTICAL tab, Lens glint mode. Hold the phone at arm's length and pan slowly: a lens throws the torch straight back as a steady pinpoint; a screw head or glass edge flashes and slides. Circles that turn red have held still.", "OPTICAL"),
        SweepStep("ir", "Infrared sweep in the dark",
            "OPTICAL tab, IR illuminator mode, room dark. Point the front camera at smoke detectors, clocks, chargers, outlets and vents. A night-vision camera lights the room with LEDs you cannot see; the phone can.", "OPTICAL"),
        SweepStep("objects", "Check the usual disguises",
            "Smoke detectors, USB chargers and power strips, alarm clocks, air fresheners, picture frames, tissue boxes, books, plant pots, screws with a hole in the head. Anything with a dark pinhole facing the room, especially facing the bed or shower.", "Hands · torch"),
        SweepStep("outlets", "Outlets, vents and ceiling fixtures",
            "Pull chargers out and look at their faces. Shine the torch into vents and around the edges of ceiling lights and detectors; a lens behind a grille still glints.", "Torch"),
        SweepStep("mirrors", "Mirror test",
            "Fingernail on the glass: a gap between the nail and its reflection is a normal mirror. No gap, or a dark room showing through when you cup the torch against the glass, is a two-way mirror.", "Hands · torch"),
        SweepStep("network", "Scan the venue's Wi-Fi",
            "Join the venue's network and run the NETWORK tab. A camera streaming to its owner speaks RTSP or ONVIF on the LAN and usually has a web UI with the maker's name on it.", "NETWORK"),
        SweepStep("wireless", "Listen for setup beacons",
            "WIRELESS tab: a camera that has not been paired broadcasts its own open hotspot or a Bluetooth name. A planted one that was never finished is loud.", "WIRELESS"),
        SweepStep("magnet", "Magnetic pass along surfaces",
            "Gauge on, phone's back flat to the surface, slide slowly along walls behind the bed, under tables, along shelves. A speaker magnet or a magnetic mount jumps the needle; drywall and wood do not.", "GUIDED gauge")
    )

    fun toggleSweepStep(id: String) {
        _sweepDone.update { done -> if (id in done) done - id else done + id }
    }

    fun resetSweep() { _sweepDone.value = emptySet() }

    const val ANALOG_RF_NOTE: String =
        "What this phone cannot see: analogue wireless cameras. Cheap video senders on 900 MHz, 1.2 GHz, 2.4 GHz and 5.8 GHz " +
        "broadcast FM video with no Wi-Fi, no Bluetooth and no network, and a camera recording to a memory card transmits nothing at all. " +
        "A phone's radios decode only Wi-Fi, Bluetooth, NFC and cellular — there is no spectrum analyser in it. " +
        "For those, the physical sweep here (glint, IR, magnet, hands) is the method; a dedicated RF detector adds the radio side."

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Our position: the scan service's fix if it has one, else the platform's last known. */
    private fun fix(context: Context): Pair<Double?, Double?> {
        val s = Registry.status.value
        if (s.lat != null && s.lon != null) return s.lat to s.lon
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null to null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null to null
        val loc = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        return loc?.latitude to loc?.longitude
    }

    /** Log-distance path loss with a −59 dBm one-metre reference, which is what BLE and Wi-Fi beacons roughly show. */
    fun approxMetres(rssi: Int): Double = 10.0.pow((-59.0 - rssi) / 20.0)

    fun fmtMetres(m: Double): String = when {
        m < 1.0 -> "under a metre"
        m < 10.0 -> "${m.roundToInt()} m"
        else -> "${(m / 5).roundToInt() * 5} m"
    }
}
