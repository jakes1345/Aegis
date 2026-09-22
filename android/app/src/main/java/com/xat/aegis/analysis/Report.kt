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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Report {

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun share(
        context: Context,
        status: ScanStatus,
        detections: List<Detection>,
        timeline: List<TimelineEvent>,
        cell: CellStatus,
        nfc: List<NfcTag>
    ): Intent {
        val text = buildReport(status, detections, timeline, cell, nfc)
        val file = File(context.cacheDir, "aegis_${System.currentTimeMillis()}.txt")
        file.writeText(text)
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Track Detect Evidence Report")
            putExtra(Intent.EXTRA_TEXT, text.take(2000))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun buildReport(
        status: ScanStatus,
        detections: List<Detection>,
        timeline: List<TimelineEvent>,
        cell: CellStatus,
        nfc: List<NfcTag>
    ) = buildString {
        appendLine("=== TRACK DETECT EVIDENCE REPORT ===")
        appendLine("Generated: ${sdf.format(Date())}")
        appendLine()

        appendLine("LOCATION")
        appendLine("  GPS: ${if (status.hasFix) "%.5f, %.5f".format(status.lat, status.lon) else "No fix"}")
        appendLine("  Travelled: %.2f km".format(status.travelledM / 1000.0))
        appendLine()

        appendLine("TRACKED DEVICES (${detections.size} total)")
        for (d in detections) {
            appendLine()
            appendLine("  ${d.name}")
            appendLine("    Address : ${d.address}")
            appendLine("    Status  : ${if (d.following) "*** CONFIRMED FOLLOWING ***" else if (d.persistent) "Persistent" else "Brief"}")
            appendLine("    Threat  : ${d.threat}  Score: ${d.score}")
            appendLine("    Sightings: ${d.sightings}  Places: ${d.places}  Displacement: ${d.displacementM.toInt()} m")
            if (d.tracker != null) appendLine("    Type    : ${d.tracker.label} (${d.tracker.brand})")
            if (d.rotations > 0) appendLine("    MAC rotations: ${d.rotations}")
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
            for (f in cell.findings) appendLine("    [${f.severity}] ${f.title}")
            appendLine("    Detail: ${cell.findings.joinToString("; ") { it.detail }}")
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
}
