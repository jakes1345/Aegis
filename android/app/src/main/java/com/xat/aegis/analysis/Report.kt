package com.xat.aegis.analysis

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.xat.aegis.CellStatus
import com.xat.aegis.Detection
import com.xat.aegis.NfcTag
import com.xat.aegis.ScanStatus
import com.xat.aegis.TimelineEvent
import com.xat.aegis.WifiAnomaly
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Report {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    // All timestamps are written in UTC so reports from different phones and time
    // zones can be lined up against each other and against server logs.
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply { timeZone = utc }
    private val sdfMinute = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply { timeZone = utc }
    private val sdfIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = utc }

    /** How long an exported report stays in the cache for a share target to read. */
    private const val EXPORT_RETENTION_MS = 24 * 60 * 60_000L

    fun share(
        context: Context,
        status: ScanStatus,
        detections: List<Detection>,
        timeline: List<TimelineEvent>,
        cell: CellStatus,
        nfc: List<NfcTag>,
        wifi: List<WifiAnomaly> = emptyList()
    ): Intent {
        val text = toText(status, detections, timeline, cell, nfc, wifi)
        // Each report carries the GPS locations where devices were observed, if any,
        // so earlier ones are not left lying in the cache indefinitely. They are kept
        // for a day, not deleted at once: a
        // mail client reads the attachment when it actually sends, which offline can
        // be well after the share, and deleting the file underneath it lost the report.
        val now = System.currentTimeMillis()
        context.cacheDir.listFiles { f -> f.isFile && f.name.startsWith("aegis_") && f.name.endsWith(".txt") }
            ?.filter { now - it.lastModified() > EXPORT_RETENTION_MS }
            ?.forEach { it.delete() }
        val file = File(context.cacheDir, "aegis_${now}.txt")
        file.writeText(text)
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Aegis Evidence Report")
            putExtra(Intent.EXTRA_TEXT, text.take(2000))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Human-readable evidence report. All timestamps are UTC. */
    fun toText(
        status: ScanStatus,
        detections: List<Detection>,
        timeline: List<TimelineEvent>,
        cell: CellStatus,
        nfc: List<NfcTag>,
        wifi: List<WifiAnomaly> = emptyList()
    ): String = buildString {
        val now = Date()
        appendLine("=== AEGIS EVIDENCE REPORT ===")
        appendLine("Aegis Report — generated ${sdf.format(now)}")
        appendLine()

        appendLine("LOCATION")
        appendLine("  GPS: ${if (status.hasFix) "%.5f, %.5f".format(status.lat, status.lon) else "No fix"}")
        appendLine("  Travelled: %.2f km".format(status.travelledM / 1000.0))
        appendLine()

        appendLine("TRACKED DEVICES (${detections.size} total)")
        for (d in detections) {
            appendLine()
            appendLine("  ${d.name}")
            appendLine("    Address : ${d.address}${d.addressKind?.let { " ($it)" } ?: ""}")
            appendLine("    Maker   : ${d.manufacturer ?: "not advertised"}  Radio: ${d.radio}")
            if (d.advertisedName != null) appendLine("    Adv name: ${d.advertisedName}")
            if (d.services.isNotEmpty()) appendLine("    Services: ${d.services.joinToString(" / ")}")
            appendLine("    Signal  : ${d.rssi} dBm")
            appendLine("    Status  : ${if (d.following) "*** CONFIRMED FOLLOWING ***" else if (d.persistent) "Persistent" else "Brief"}")
            appendLine("    Threat  : ${d.threat}  Score: ${d.score}")
            appendLine("    Confidence: ${d.confidence.name}")
            appendLine("    First seen: ${sdfMinute.format(Date(d.firstSeen))}  Last seen: ${sdfMinute.format(Date(d.lastSeen))}")
            appendLine("    Sightings: ${d.sightings}  Places: ${d.places}  Displacement: ${d.displacementM.toInt()} m")
            if (d.tracker != null) appendLine("    Type    : ${d.tracker.label} (${d.tracker.brand})")
            if (d.rotations > 0) appendLine("    MAC rotations: ${d.rotations}")
            appendLine("    ${d.addresses} address rotation${if (d.addresses == 1) "" else "s"}")
            if (d.points.isNotEmpty()) {
                appendLine("    LOCATIONS:")
                for (p in d.points) appendLine("      - %.6f,%.6f".format(p.lat, p.lon))
            }
        }
        appendLine()

        appendLine("CELLULAR STATUS")
        val c = cell.cell
        if (c != null) {
            appendLine("  Cell    : ${c.cellId}  RAT: ${c.rat.label}")
            appendLine("  MCC/MNC : ${c.mcc}/${c.mnc}  TAC: ${c.tac}")
            appendLine("  Signal  : ${c.signalDbm ?: "??"} dBm  Neighbours: ${c.neighbors ?: "??"}")
            appendLine("  Threat  : ${cell.level}  Score: ${cell.score}")
        } else {
            appendLine("  ${cell.reason ?: "No cell data"}")
        }
        if (cell.findings.isNotEmpty()) {
            appendLine("  Indicators (${cell.findings.size}):")
            for (f in cell.findings) appendLine("    [${f.severity}] ${f.id}: ${f.title}")
            appendLine("    Detail: ${cell.findings.joinToString("; ") { it.detail }}")
        }
        appendLine()

        appendLine("WIFI ANOMALIES (${wifi.size})")
        for (w in wifi) {
            appendLine()
            appendLine("  SSID  : ${w.ssid}")
            appendLine("  BSSID : ${w.bssid}  Signal: ${w.rssi} dBm")
            appendLine("  Reason: ${w.reason}  Threat: ${w.threat}")
            appendLine("  Seen  : ${sdf.format(Date(w.ts))}")
        }
        appendLine()

        appendLine("NFC TAGS SCANNED (${nfc.size})")
        for (t in nfc) {
            appendLine()
            appendLine("  UID  : ${t.uid}")
            appendLine("  Type : ${t.type}  Techs: ${t.techs.joinToString()}")
            if (t.payload != null) appendLine("  Data : ${t.payload}")
            appendLine("  Note : ${t.note}${if (t.suspicious) "  *** SUSPICIOUS ***" else ""}")
        }
        appendLine()

        appendLine("EVENT TIMELINE (${timeline.size} events, showing last 100)")
        for (e in timeline.take(100)) {
            appendLine("  ${sdf.format(Date(e.ts))} [${e.severity}] ${e.kind}: ${e.title}")
            if (e.detail.isNotBlank()) appendLine("    ${e.detail}")
            if (e.lat != null) appendLine("    @ %.5f, %.5f".format(e.lat, e.lon))
        }
    }

    /**
     * Machine-readable counterpart of [toText]: the same evidence as JSON, using the
     * per-threat blocks from [ThreatPayload] so a report and a shared threat payload
     * describe a device in the same shape. Pretty-printed with two-space indent.
     */
    fun toJson(
        status: ScanStatus,
        detections: List<Detection>,
        timeline: List<TimelineEvent>,
        cell: CellStatus?,
        wifi: List<WifiAnomaly> = emptyList(),
        nfc: List<NfcTag> = emptyList()
    ): String {
        val now = System.currentTimeMillis()
        val root = JSONObject()
        root.put("v", ThreatPayload.VERSION)
        root.put("app", "aegis")
        root.put("ts", now)
        root.put("ts_utc", sdfIso.format(Date(now)))

        root.put("status", JSONObject().apply {
            put("scanning", status.scanning)
            put("fix", status.hasFix)
            if (status.hasFix && status.lat != null && status.lon != null) {
                put("lat", status.lat); put("lon", status.lon)
            }
            put("travelled_m", status.travelledM)
            put("nearby", status.nearbyCount)
        })

        val dArr = JSONArray()
        for (d in detections) {
            dArr.put(ThreatPayload.buildBleSection(d, includeTrail = true).also {
                it.put("name", d.name)
                it.put("threat", d.threat.name)
                it.put("score", d.score)
                it.put("following", d.following)
                it.put("persistent", d.persistent)
                d.tracker?.let { t -> it.put("tracker", t.label); it.put("brand", t.brand) }
            })
        }
        root.put("ble", dArr)

        cell?.let { root.put("cell", ThreatPayload.buildCellSection(it)) }

        val wArr = JSONArray()
        for (w in wifi) wArr.put(ThreatPayload.buildWifiSection(w))
        root.put("wifi", wArr)

        val nArr = JSONArray()
        for (t in nfc) {
            nArr.put(JSONObject().apply {
                put("uid", t.uid); put("type", t.type)
                put("techs", JSONArray().also { a -> t.techs.forEach { a.put(it) } })
                t.payload?.let { put("payload", it) }
                put("suspicious", t.suspicious); put("note", t.note); put("ts", t.ts)
            })
        }
        root.put("nfc", nArr)

        val tArr = JSONArray()
        for (e in timeline.take(100)) {
            tArr.put(JSONObject().apply {
                put("id", e.id); put("kind", e.kind.name); put("ts", e.ts)
                put("sev", e.severity.name); put("title", e.title); put("detail", e.detail)
                e.lat?.let { put("lat", it) }; e.lon?.let { put("lon", it) }
            })
        }
        root.put("timeline", tArr)

        return root.toString(2)
    }
}
