package app.clairix.nav

/** Where the router learns how fast traffic is moving on a directed segment ("<wayId>:<dir>"). */
interface SpeedSource {
    /** Current speed in km/h from recent probes, or null when nobody has driven it lately. */
    fun liveKph(segmentKey: String): Double?

    /** Historical speed in km/h for the hour of week containing [epochSec], or null when unknown. */
    fun typicalKph(segmentKey: String, epochSec: Long): Double?

    companion object {
        /** No traffic knowledge at all: the router falls back to posted limits and road classes. */
        val NONE: SpeedSource = object : SpeedSource {
            override fun liveKph(segmentKey: String): Double? = null
            override fun typicalKph(segmentKey: String, epochSec: Long): Double? = null
        }
    }
}

data class LatLon(val lat: Double, val lon: Double)

/**
 * A point snapped onto an edge: [fraction] is how far along the edge's geometry (0 = from node,
 * 1 = to node) the projection lies, [bearingDeg] the direction of travel of the segment it hit.
 */
data class EdgeHit(val edge: Int, val distanceM: Double, val fraction: Double, val bearingDeg: Double)

object EdgeFlags {
    const val TOLL = 1
    const val ROUNDABOUT = 2
    const val LINK = 4
    const val UNPAVED = 8
    const val FORWARD = 16
    const val SIGNALS = 32
    const val STOP = 64
}

enum class RouteProfile { FASTEST, SHORTEST, NO_TOLLS, NO_HIGHWAYS, ECO }

data class Route(
    val profile: RouteProfile,
    val edges: IntArray,
    val geometry: List<LatLon>,
    val distanceM: Double,
    val durationSec: Double,
    val freeFlowDurationSec: Double,
    val tollM: Double,
    val fuelLiters: Double,
    val maneuvers: List<Maneuver>,
) {
    // IntArray compares by identity in a generated equals; routes are equal when they follow the same edges.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Route) return false
        return profile == other.profile && edges.contentEquals(other.edges) && geometry == other.geometry &&
            distanceM == other.distanceM && durationSec == other.durationSec &&
            freeFlowDurationSec == other.freeFlowDurationSec && tollM == other.tollM &&
            fuelLiters == other.fuelLiters && maneuvers == other.maneuvers
    }

    override fun hashCode(): Int {
        var h = profile.hashCode()
        h = 31 * h + edges.contentHashCode()
        h = 31 * h + geometry.hashCode()
        h = 31 * h + distanceM.hashCode()
        h = 31 * h + durationSec.hashCode()
        return h
    }
}

data class Maneuver(
    val type: ManeuverType,
    val atIndex: Int,
    val distanceFromStartM: Double,
    val streetName: String?,
    val exitNumber: Int?,
    val instruction: String,
)

enum class ManeuverType {
    DEPART, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN,
    ROUNDABOUT, MERGE, RAMP_LEFT, RAMP_RIGHT, KEEP_LEFT, KEEP_RIGHT, ARRIVE
}
