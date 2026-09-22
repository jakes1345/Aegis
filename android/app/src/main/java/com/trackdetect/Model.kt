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
    val error: String? = null,
    val nearbyCount: Int = 0  // total BLE devices in range including transient ones
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
    val ts: Long,
    /**
     * GSM / LTE timing advance as reported by the modem, or null when the
     * technology (UMTS, NR) or the modem does not expose it. Zero means the
     * transmitter is within one TA step of you — ~550 m on GSM, ~78 m on LTE.
     */
    val timingAdvance: Int? = null
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

enum class EventKind {
    FOLLOWING, CATCHER, NFC_TAG, SCAN_START, SCAN_STOP,
    WIFI_ANOMALY,
    /** BLE tracker persisting while cell anomaly is also active — correlated surveillance. */
    CORRELATED_SURVEILLANCE
}

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

/**
 * Precise card profile resolved from ATQA + SAK bytes.
 * [hceCapable] = the phone can replay APDU conversations for this type via HCE.
 * MIFARE Classic / Ultralight / FeliCa / ISO 15693 use different protocols that
 * Android's HCE stack cannot emulate — the reader sees a different RF signal.
 */
enum class CardProfile(val label: String, val hceCapable: Boolean) {
    MIFARE_CLASSIC_1K("MIFARE Classic 1K", false),
    MIFARE_CLASSIC_4K("MIFARE Classic 4K", false),
    MIFARE_ULTRALIGHT("MIFARE Ultralight", false),
    MIFARE_DESFIRE("MIFARE DESFire", true),
    MIFARE_PLUS("MIFARE Plus SL3", true),
    ISO14443_4("ISO 14443-4", true),
    FELICA("FeliCa (NFC-F)", false),
    ISO15693("ISO 15693 (HF RFID)", false),
    EMV_VISA("Visa contactless", false),
    EMV_MASTERCARD("Mastercard contactless", false),
    EMV_AMEX("Amex contactless", false),
    EMV_OTHER("Payment card", false),
    UNKNOWN("Unknown", false)
}

data class NfcTag(
    val uid: String,
    val techs: List<String>,
    val type: String,
    val payload: String?,
    val suspicious: Boolean,
    val note: String,
    val ts: Long,
    val atqa: String? = null,
    val sak: String? = null,
    val profile: CardProfile = CardProfile.UNKNOWN,
    val paymentNetwork: String? = null,
    val skimmerFlags: List<String> = emptyList(),
    val apduPairs: List<Pair<String, String>> = emptyList()
)

// --- Card vault -------------------------------------------------------------

/**
 * A credential stored in the encrypted vault.
 * [apduPairs] holds the recorded ISO 14443-4 APDU conversation (hex cmd → hex resp)
 * for cards whose [CardProfile.hceCapable] is true. Empty for MIFARE Classic etc.
 */
data class VaultCard(
    val id: String,
    val uid: String,
    val label: String,
    val profile: CardProfile,
    val addedTs: Long,
    val apduPairs: List<Pair<String, String>> = emptyList()
)

// --- WiFi anomaly -----------------------------------------------------------

data class WifiAnomaly(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val reason: String,       // e.g. "known_catcher_ssid", "open_unsecured", "duplicate_ssid"
    val threat: Threat,
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

// --- Phone health / surveillance indicators ---------------------------------

data class PhoneHealthFinding(
    val id: String,
    val severity: Severity,
    val category: String,
    val title: String,
    val detail: String
)

data class PhoneHealth(
    val findings: List<PhoneHealthFinding> = emptyList(),
    val activeMic: List<String> = emptyList(),
    val activeCamera: List<String> = emptyList()
) {
    val level: Threat get() = when {
        activeMic.isNotEmpty() || activeCamera.isNotEmpty() -> Threat.CRITICAL
        findings.any { it.severity == Severity.CRITICAL } -> Threat.CRITICAL
        findings.any { it.severity == Severity.HIGH } -> Threat.HIGH
        findings.any { it.severity == Severity.MEDIUM } -> Threat.MEDIUM
        findings.isNotEmpty() -> Threat.LOW
        else -> Threat.NONE
    }
}
