package com.trackdetect

enum class Threat { NONE, LOW, MEDIUM, HIGH, CRITICAL }

/** How confident we are that something is actually following, and why. */
enum class FollowConfidence {
    /** Not present long enough to say anything. */
    NONE,

    /** Persistent, but there is no position fix, so the claim cannot be made. */
    NO_POSITION,

    /** Persistent, we have position, but you have not travelled far enough yet. */
    NOT_MOVED_ENOUGH,

    /** Heard from two points far enough apart to prove it travelled with you. */
    CONFIRMED
}

data class TrackerType(
    val id: String,
    val label: String,
    val brand: String,
    val threat: Threat,
    val notes: String
)

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
    val approxMetres: Double?
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
