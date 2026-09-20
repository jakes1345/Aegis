package com.trackdetect

enum class Threat { NONE, LOW, MEDIUM, HIGH, CRITICAL }

enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }

enum class FollowConfidence { NONE, NO_POSITION, NOT_MOVED_ENOUGH, CONFIRMED }

data class TrackerType(
    val id: String,
    val label: String,
    val brand: String,
    val threat: Threat,
    val notes: String
)

data class LatLon(val lat: Double, val lon: Double)

data class Detection(
    val key: String,
    val address: String,
    val name: String,
    val rssi: Int,
    val tracker: TrackerType?,
    val threat: Threat,
    val score: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val sightings: Int,
    val persistent: Boolean,
    val following: Boolean,
    val confidence: FollowConfidence,
    val displacementM: Double,
    val places: Int,
    val rotations: Int,
    val addresses: Int,
    val approxMetres: Double?,
    val points: List<LatLon> = emptyList()
) {
    val identified: Boolean get() = tracker != null
}

data class ScanStatus(
    val scanning: Boolean = false,
    val bluetoothOn: Boolean = true,
    val hasFix: Boolean = false,
    val moving: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    val travelledM: Double = 0.0,
    val error: String? = null
)

// --- Cellular / IMSI catcher ------------------------------------------------

/** Radio access technology, ranked so a forced downgrade can be recognised. */
enum class Rat(val label: String, val rank: Int) {
    UNKNOWN("unknown", 0),
    GSM("2G GSM", 2),
    CDMA("2G CDMA", 2),
    UMTS("3G UMTS", 3),
    LTE("4G LTE", 4),
    NR5G("5G NR", 5)
}

data class ServingCell(
    val mcc: String?,
    val mnc: String?,
    val tac: String?,
    val cellId: String,
    val rat: Rat,
    val signalDbm: Int?,
    val neighbors: Int?,
    val ts: Long
) {
    val key: String get() = "${mcc ?: "?"}-${mnc ?: "?"}-${tac ?: "?"}-$cellId"
}

data class CatcherFinding(
    val id: String,
    val severity: Severity,
    val title: String,
    val detail: String
)

data class CellStatus(
    val available: Boolean = false,
    val reason: String? = null,
    val cell: ServingCell? = null,
    val findings: List<CatcherFinding> = emptyList(),
    val score: Int = 0,
    val level: Threat = Threat.NONE,
    val mature: Boolean = false,
    val maturity: Float = 0f,
    val knownCells: Int = 0,
    val observations: Int = 0
)

// --- Timeline ---------------------------------------------------------------

enum class EventKind { FOLLOWING, CATCHER, NFC_TAG, SCAN_START, SCAN_STOP }

data class TimelineEvent(
    val id: String,
    val kind: EventKind,
    val ts: Long,
    val title: String,
    val detail: String,
    val severity: Severity,
    val lat: Double? = null,
    val lon: Double? = null
)

// --- NFC / HF RFID ----------------------------------------------------------

data class NfcTag(
    val uid: String,
    val techs: List<String>,
    val type: String,
    val payload: String?,
    val suspicious: Boolean,
    val note: String,
    val ts: Long
)

// --- Map --------------------------------------------------------------------

data class DeviceTrail(
    val key: String,
    val name: String,
    val threat: Threat,
    val following: Boolean,
    val points: List<LatLon>
)

data class CellMarker(
    val label: String,
    val point: LatLon,
    val level: Threat
)

data class MapData(
    val track: List<LatLon> = emptyList(),
    val devices: List<DeviceTrail> = emptyList(),
    val cells: List<CellMarker> = emptyList()
)
