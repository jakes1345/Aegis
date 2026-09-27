package com.xat.aegis.analysis

import com.xat.aegis.CellStatus
import com.xat.aegis.Detection
import com.xat.aegis.WifiAnomaly
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Pure Kotlin/Java encoder for structured, machine-readable threat reports.
 *
 * Deliberately free of Android SDK imports so it can be unit-tested on the JVM.
 * Every payload carries a schema version [VERSION], a random id, the wall-clock
 * time it was built and an expiry after which a receiver should discard it.
 */
object ThreatPayload {

    const val VERSION = 1

    /** Build a structured JSON payload for a BLE following tracker threat. */
    fun fromDetection(
        detection: Detection,
        reporterLat: Double = 0.0,
        reporterLon: Double = 0.0,
        gid: String = "",
        includeTrail: Boolean = false
    ): JSONObject = JSONObject().apply {
        val now = System.currentTimeMillis()
        put("v", VERSION)
        put("id", UUID.randomUUID().toString())
        put("ts", now)
        put("kind", "FOLLOWING")
        put("sev", detection.threat.name)
        put("threat", detection.threat.name)
        put("score", detection.score)
        put("gid", gid)
        val heard = detection.lastHeardAt
        if (reporterLat != 0.0 || reporterLon != 0.0) {
            put("lat", reporterLat); put("lon", reporterLon)
        } else if (heard != null) {
            put("lat", heard.lat); put("lon", heard.lon)
        }
        put("title", buildBleTitle(detection))
        put("detail", buildBleDetail(detection))
        put("exp", now + 3_600_000L)
        put("ble", buildBleSection(detection, includeTrail))
    }

    /** Build a structured JSON payload for a cell/IMSI catcher threat. */
    fun fromCell(
        cell: CellStatus,
        reporterLat: Double = 0.0,
        reporterLon: Double = 0.0,
        gid: String = ""
    ): JSONObject = JSONObject().apply {
        val now = System.currentTimeMillis()
        put("v", VERSION)
        put("id", UUID.randomUUID().toString())
        put("ts", now)
        put("kind", "CATCHER")
        put("sev", cell.level.name)
        put("threat", cell.level.name)
        put("score", cell.score)
        put("gid", gid)
        if (reporterLat != 0.0 || reporterLon != 0.0) {
            put("lat", reporterLat); put("lon", reporterLon)
        }
        val servingLabel = cell.cell?.let { "${it.mcc ?: "?"}-${it.mnc ?: "?"} TAC ${it.tac ?: "?"}" } ?: "Unknown cell"
        put("title", "Possible IMSI catcher: $servingLabel")
        put("detail", cell.findings.take(3).joinToString("; ") { it.title })
        put("exp", now + 24 * 3_600_000L)
        put("cell", buildCellSection(cell))
    }

    /** Build a structured JSON payload for a WiFi anomaly. */
    fun fromWifi(
        anomaly: WifiAnomaly,
        reporterLat: Double = 0.0,
        reporterLon: Double = 0.0,
        gid: String = ""
    ): JSONObject = JSONObject().apply {
        val now = System.currentTimeMillis()
        put("v", VERSION)
        put("id", UUID.randomUUID().toString())
        put("ts", now)
        put("kind", "WIFI_ANOMALY")
        put("sev", anomaly.threat.name)
        put("threat", anomaly.threat.name)
        put("gid", gid)
        if (reporterLat != 0.0 || reporterLon != 0.0) {
            put("lat", reporterLat); put("lon", reporterLon)
        }
        put("title", "Wi-Fi anomaly: ${anomaly.ssid}")
        put("detail", anomaly.reason)
        put("exp", now + 3_600_000L)
        put("wifi", buildWifiSection(anomaly))
    }

    private fun buildBleTitle(d: Detection): String {
        val tracker = d.tracker
        return when {
            tracker != null -> "${tracker.label} following you"
            d.following -> "Unknown tracker following you"
            d.persistent -> "Persistent unknown device"
            else -> "Suspicious BLE device"
        }
    }

    private fun buildBleDetail(d: Detection): String {
        val parts = mutableListOf<String>()
        if (d.sightings > 1) parts.add("${d.sightings} sightings")
        if (d.displacementM > 0) parts.add("${d.displacementM.toInt()}m displacement")
        if (d.rotations > 0) parts.add("${d.rotations} MAC rotations")
        d.manufacturer?.let { parts.add(it) }
        return parts.joinToString(", ")
    }

    /**
     * The BLE device block shared by [fromDetection] and [Report.toJson].
     * With [includeTrail] every distinct place the device was heard is emitted as
     * `[lat, lon]` pairs; otherwise only the most recent position, if known.
     */
    internal fun buildBleSection(d: Detection, includeTrail: Boolean): JSONObject = JSONObject().apply {
        put("addr", d.address)
        d.addressKind?.let { put("kind", it) }
        d.tracker?.let { put("sig", it.id) }
        d.manufacturer?.let { put("mfr", it) }
        d.advertisedName?.let { put("adv", it) }
        put("radio", d.radio)
        put("rssi", d.rssi)
        put("conf", d.confidence.name)
        put("first", d.firstSeen)
        put("last", d.lastSeen)
        put("n", d.sightings)
        put("disp", d.displacementM)
        put("places", d.places)
        put("rot", d.rotations)
        put("addrs", d.addresses)
        d.approxMetres?.let { put("dist", it) }
        if (d.services.isNotEmpty()) {
            put("svcs", JSONArray().also { a -> d.services.forEach { a.put(it) } })
        }
        if (includeTrail) {
            put("trail", JSONArray().also { a ->
                d.points.forEach { p -> a.put(JSONArray().apply { put(p.lat); put(p.lon) }) }
            })
        }
        d.lastHeardAt?.let { put("last_lat", it.lat); put("last_lon", it.lon) }
    }

    /** The cellular block shared by [fromCell] and [Report.toJson]. */
    internal fun buildCellSection(cell: CellStatus): JSONObject = JSONObject().apply {
        cell.cell?.let { sc ->
            put("key", sc.key)
            sc.mcc?.let { put("mcc", it) }
            sc.mnc?.let { put("mnc", it) }
            sc.tac?.let { put("tac", it) }
            put("cid", sc.cellId)
            put("rat", sc.rat.name)
            put("cell_ts", sc.ts)
            sc.pci?.let { put("pci", it) }
            sc.arfcn?.let { put("arfcn", it) }
            sc.band?.let { put("band", it) }
            sc.signalDbm?.let { put("dbm", it) }
            sc.neighbors?.let { put("nbrs", it) }
            sc.timingAdvance?.let { put("ta", it) }
        }
        cell.reason?.let { put("reason", it) }
        put("available", cell.available)
        put("score", cell.score)
        put("level", cell.level.name)
        put("mature", cell.mature)
        put("findings", JSONArray().also { a ->
            cell.findings.forEach { f ->
                a.put(JSONObject().apply {
                    put("id", f.id); put("sev", f.severity.name); put("title", f.title)
                })
            }
        })
    }

    /** The Wi-Fi block shared by [fromWifi] and [Report.toJson]. */
    internal fun buildWifiSection(w: WifiAnomaly): JSONObject = JSONObject().apply {
        put("ssid", w.ssid); put("bssid", w.bssid)
        put("reason", w.reason); put("threat", w.threat.name)
        put("rssi", w.rssi); put("ts", w.ts)
    }
}
