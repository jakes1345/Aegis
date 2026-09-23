package com.xat.aegis

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
    /**
     * How far the baseline has come, 0..1, in separate visits to areas rather than
     * poll counts. The visit-gated heuristics need five or more visits to an area
     * before they can fire, so the bar is scaled to match what "Mature" means.
     */
    val maturity: Float = 0f,
    val knownCells: Int = 0,
    /** Separate visits recorded across every tracking area. */
    val visits: Int = 0
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

/** What the NFC tab can show of the vault right now. */
sealed interface VaultState {
    /** Not unlocked this session, or re-locked when the app left the screen. */
    data object Locked : VaultState
    /** Unlock in progress (authentication or decryption running). */
    data object Unlocking : VaultState
    data class Unlocked(val cards: List<VaultCard>) : VaultState
    /**
     * The vault could not be opened. Nothing was overwritten. [retryable] is false
     * when the key is gone for good and trying again cannot help; [erasable] is true
     * when there is stored data the user may choose to delete to start over.
     */
    data class Failed(val message: String, val retryable: Boolean, val erasable: Boolean) : VaultState
}

/** The card currently armed for Host Card Emulation. */
data class ArmedCard(val id: String, val label: String)

/**
 * A change to the stored vault, as data rather than a closure, so that a pending
 * write survives the Activity that started it being recreated: the authentication
 * result names the operation and whichever Activity instance is alive carries it out.
 */
sealed interface VaultOp {
    /** Store [card]; refused when a card with the same UID exists unless [replace]. */
    data class Add(val card: VaultCard, val replace: Boolean) : VaultOp
    data class Remove(val id: String) : VaultOp
}

/** What the user is being asked to confirm their identity for. */
sealed interface VaultAuthPurpose {
    data class Unlock(val attempt: Int) : VaultAuthPurpose
    data class Write(val op: VaultOp, val attempt: Int) : VaultAuthPurpose
    data object Erase : VaultAuthPurpose
}

/** The outcome of a BiometricPrompt, published for the live Activity to act on. */
sealed interface VaultAuthOutcome {
    data object Succeeded : VaultAuthOutcome
    /** [message] is null when the user simply cancelled. */
    data class Failed(val message: String?) : VaultAuthOutcome
}

data class VaultAuthResult(val purpose: VaultAuthPurpose, val outcome: VaultAuthOutcome)

/**
 * A save that would overwrite a card already in the vault with the same UID. The
 * NFC tab asks before it goes ahead.
 */
data class ReplacePrompt(val tag: NfcTag, val label: String, val existing: VaultCard)

// --- WiFi anomaly -----------------------------------------------------------

data class WifiAnomaly(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val reason: String,       // e.g. "known_catcher_ssid", "carrier_open_network", "open_twin_of_secured"
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
    /**
     * When [findings] were last produced by an actual scan, or 0 when nothing has
     * checked yet. The Device tab must not claim "no issues" on the strength of a
     * default empty list.
     */
    val findingsScannedTs: Long = 0L,
    /**
     * Number of audio recordings active on the device that are actually receiving
     * audio. Android does not reveal which app owns a recording to a third-party app,
     * so this is a count, not a list. Recordings the system has silenced (an app
     * recording from the background, or while another app holds the mic) are left
     * out: nothing reaches them.
     */
    val activeRecordings: Int = 0,
    /**
     * Whether another app holds a camera open. A boolean, not a count: opening one
     * physical camera makes its logical and multi-camera siblings unavailable too, so
     * counting ids reported "3 cameras in use" for one app taking a photo.
     */
    val cameraInUse: Boolean = false
) {
    val sensorActive: Boolean get() = activeRecordings > 0 || cameraInUse

    /**
     * A live microphone or camera is worth a HIGH, not a CRITICAL: it is what every
     * video call and voice memo looks like. CRITICAL is reserved for findings that
     * are themselves CRITICAL.
     */
    val level: Threat get() = when {
        findings.any { it.severity == Severity.CRITICAL } -> Threat.CRITICAL
        sensorActive || findings.any { it.severity == Severity.HIGH } -> Threat.HIGH
        findings.any { it.severity == Severity.MEDIUM } -> Threat.MEDIUM
        findings.isNotEmpty() -> Threat.LOW
        else -> Threat.NONE
    }
}
