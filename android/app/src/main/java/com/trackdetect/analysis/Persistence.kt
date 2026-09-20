package com.trackdetect.analysis

import com.trackdetect.Detection
import com.trackdetect.FollowConfidence
import com.trackdetect.Threat
import com.trackdetect.TrackerType

const val PERSIST_THRESHOLD_MS = 10 * 60 * 1000L
const val PERSIST_MIN_SIGHTINGS = 5

/**
 * Tracks how long a device stays with you and — where there is a position fix —
 * whether it actually travelled with you.
 *
 * The distinction is the whole point of this app. "Seen five times over ten
 * minutes" describes a tracker in your bumper and it equally describes a
 * neighbour's Tile through a wall. Only displacement separates them, so with no
 * position source the stronger claim is simply never made.
 */
class Tracker {

    private class Entry(val key: String) {
        var address = ""
        var name = ""
        var rssi = 0
        var tracker: TrackerType? = null
        var approxMetres: Double? = null
        var firstSeen = 0L
        var lastSeen = 0L
        var sightings = 0
        var rotations = 0
        var addresses = 1
        var following = false
        var persistent = false
        var score = 0
        var confidence = FollowConfidence.NONE
        val area = ObservationArea()
    }

    data class Observation(val detection: Detection, val becameFollowing: Boolean)

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun observe(
        key: String,
        address: String,
        name: String,
        rssi: Int,
        tracker: TrackerType?,
        approxMetres: Double?,
        rotations: Int,
        addresses: Int,
        fix: Fix?,
        hasPosition: Boolean,
        now: Long
    ): Observation {
        val entry = entries.getOrPut(key) {
            Entry(key).apply { firstSeen = now }
        }

        val wasFollowing = entry.following

        entry.address = address
        entry.name = name
        entry.rssi = rssi
        entry.approxMetres = approxMetres
        entry.rotations = rotations
        entry.addresses = addresses
        entry.lastSeen = now
        entry.sightings++
        // A device only becomes identifiable once it advertises something we match.
        if (entry.tracker == null && tracker != null) entry.tracker = tracker

        fix?.let { entry.area.add(it) }

        val duration = now - entry.firstSeen
        entry.persistent =
            duration >= PERSIST_THRESHOLD_MS && entry.sightings >= PERSIST_MIN_SIGHTINGS

        val movedWithUs = entry.area.movedWithUs()
        entry.following = entry.persistent && movedWithUs

        val confidence = when {
            entry.following -> FollowConfidence.CONFIRMED
            !entry.persistent -> FollowConfidence.NONE
            !hasPosition -> FollowConfidence.NO_POSITION
            else -> FollowConfidence.NOT_MOVED_ENOUGH
        }

        var score = when (entry.tracker?.threat) {
            Threat.CRITICAL -> 88
            Threat.HIGH -> 68
            Threat.MEDIUM -> 44
            Threat.LOW -> 20
            else -> 10
        }
        if (rssi > -55) score += 10 else if (rssi > -70) score += 5
        if (entry.following) {
            score += 30
            if (entry.tracker != null) score += 10
        } else if (entry.persistent) {
            score += 10
        }
        // Rotating its address while staying with you is what a tracker built to
        // defeat exactly this kind of detection does.
        if (entry.rotations > 0) score += 8
        score = score.coerceIn(0, 100)
        entry.score = score
        entry.confidence = confidence

        val threat = when {
            entry.following -> Threat.CRITICAL
            entry.tracker != null -> entry.tracker!!.threat
            entry.persistent -> Threat.MEDIUM
            else -> Threat.LOW
        }

        val detection = Detection(
            key = entry.key,
            address = entry.address,
            name = entry.name,
            rssi = entry.rssi,
            tracker = entry.tracker,
            threat = threat,
            score = score,
            firstSeen = entry.firstSeen,
            lastSeen = entry.lastSeen,
            sightings = entry.sightings,
            persistent = entry.persistent,
            following = entry.following,
            confidence = confidence,
            displacementM = entry.area.span(),
            places = entry.area.size,
            rotations = entry.rotations,
            addresses = entry.addresses,
            approxMetres = entry.approxMetres,
            points = entry.area.points()
        )

        return Observation(detection, entry.following && !wasFollowing)
    }

    @Synchronized
    fun snapshot(now: Long): List<Detection> = entries.values
        .map { entry ->
            Detection(
                key = entry.key,
                address = entry.address,
                name = entry.name,
                rssi = entry.rssi,
                tracker = entry.tracker,
                threat = when {
                    entry.following -> Threat.CRITICAL
                    entry.tracker != null -> entry.tracker!!.threat
                    entry.persistent -> Threat.MEDIUM
                    else -> Threat.LOW
                },
                score = entry.score,
                firstSeen = entry.firstSeen,
                lastSeen = entry.lastSeen,
                sightings = entry.sightings,
                persistent = entry.persistent,
                following = entry.following,
                confidence = entry.confidence,
                displacementM = entry.area.span(),
                places = entry.area.size,
                rotations = entry.rotations,
                addresses = entry.addresses,
                approxMetres = entry.approxMetres,
                points = entry.area.points()
            )
        }

    /**
     * Drops devices that have gone quiet — except anything that proved it travels
     * with you, because trackers sleep between reports and forgetting one would
     * throw away the only evidence that matters.
     */
    @Synchronized
    fun prune(now: Long, maxAgeMs: Long = 30 * 60 * 1000L) {
        val cutoff = now - maxAgeMs
        entries.entries.removeAll { it.value.lastSeen < cutoff && !it.value.following }
    }
}
