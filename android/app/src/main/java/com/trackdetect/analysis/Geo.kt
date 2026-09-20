package com.trackdetect.analysis

import android.location.Location
import com.trackdetect.LatLon
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val EARTH_RADIUS_M = 6371008.8

/** Two observations this far apart prove the emitter physically moved with you. */
const val FOLLOW_DISPLACEMENT_M = 300.0

/** Fixes closer together than this are treated as the same place. */
const val PLACE_RADIUS_M = 100.0

/** Below this speed we are not travelling, so co-presence proves nothing. */
const val MOVING_SPEED_MS = 1.5

data class Fix(val lat: Double, val lon: Double, val speed: Float?, val time: Long)

fun Location.toFix() = Fix(latitude, longitude, if (hasSpeed()) speed else null, System.currentTimeMillis())

fun haversine(a: Fix, b: Fix): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        sin(dLon / 2) * sin(dLon / 2) * cos(lat1) * cos(lat2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

/**
 * The set of places one device was heard from. Kept small by collapsing fixes
 * that land in the same spot, so the pairwise span stays cheap to compute.
 */
class ObservationArea(private val maxPlaces: Int = 60) {

    private class Place(val lat: Double, val lon: Double, var count: Int)

    private val places = ArrayList<Place>()
    private var spanCache: Double? = 0.0

    val size: Int get() = places.size

    fun add(fix: Fix) {
        for (p in places) {
            if (haversine(Fix(p.lat, p.lon, null, 0), fix) <= PLACE_RADIUS_M) {
                p.count++
                return
            }
        }
        spanCache = null
        places.add(Place(fix.lat, fix.lon, 1))

        // Drop the least-visited place rather than the oldest: somewhere passed
        // through once matters less than somewhere you keep returning to.
        if (places.size > maxPlaces) {
            var worst = 0
            for (i in 1 until places.size) if (places[i].count < places[worst].count) worst = i
            places.removeAt(worst)
        }
    }

    /**
     * Widest separation between any two places this device was heard from.
     * This single number is what separates a tracker on your car from a
     * neighbour's tag on the other side of a wall.
     */
    fun span(): Double {
        spanCache?.let { return it }
        if (places.size < 2) {
            spanCache = 0.0
            return 0.0
        }
        var max = 0.0
        for (i in places.indices) {
            for (j in i + 1 until places.size) {
                val d = haversine(
                    Fix(places[i].lat, places[i].lon, null, 0),
                    Fix(places[j].lat, places[j].lon, null, 0)
                )
                if (d > max) max = d
            }
        }
        spanCache = max
        return max
    }

    fun movedWithUs(): Boolean = span() >= FOLLOW_DISPLACEMENT_M

    fun points(): List<LatLon> = places.map { LatLon(it.lat, it.lon) }
}

/** Where we have been, and whether we are actually going anywhere. */
class LocationTrack {

    var current: Fix? = null
        private set

    var travelledM: Double = 0.0
        private set

    private var previous: Fix? = null

    fun update(fix: Fix) {
        previous = current
        current = fix
        previous?.let {
            val d = haversine(it, fix)
            if (d > 5) travelledM += d
        }
    }

    fun hasFix(): Boolean = current != null

    fun isMoving(): Boolean {
        val c = current ?: return false
        c.speed?.let { return it >= MOVING_SPEED_MS }
        val p = previous ?: return false
        val dt = (c.time - p.time) / 1000.0
        if (dt <= 0) return false
        return haversine(p, c) / dt >= MOVING_SPEED_MS
    }
}
