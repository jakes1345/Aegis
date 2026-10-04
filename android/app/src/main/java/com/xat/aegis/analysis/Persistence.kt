package com.xat.aegis.analysis

import com.xat.aegis.Detection
import com.xat.aegis.FollowConfidence
import com.xat.aegis.LatLon
import com.xat.aegis.Threat
import com.xat.aegis.TrackerType
import com.xat.aegis.detect.BleNames
import com.xat.aegis.detect.DultResult
import kotlin.math.max
import kotlin.math.min

const val PERSIST_THRESHOLD_MS = 10 * 60 * 1000L
const val PERSIST_MIN_SIGHTINGS = 5

/** Ceiling on simultaneously tracked devices — see [Tracker.prune]. */
private const val MAX_ENTRIES = 4000

/** How long a confirmed follower is remembered after its last sighting — see [Tracker.prune]. */
const val FOLLOWING_RETENTION_MS = 7 * 24 * 3600_000L

/** Ceiling on distinct service names remembered per device; a row cannot show more anyway. */
private const val MAX_SERVICES = 8

/**
 * Tracks how long a device stays with you and — where there is a position fix —
 * whether it actually travelled with you.
 *
 * The distinction is the whole point of this app. "Seen five times over ten
 * minutes" describes a tracker in your bumper and it equally describes a
 * neighbour's Tile through a wall. Only displacement separates them, so with no
 * position source the stronger claim is simply never made.
 */
class Tracker(
    /**
     * Read through lambdas rather than captured once, so moving a slider in Settings
     * takes effect on the next observation instead of at the next app start. The
     * defaults keep the class usable on its own.
     */
    private val persistMs: () -> Long = { PERSIST_THRESHOLD_MS },
    private val followM: () -> Double = { FOLLOW_DISPLACEMENT_M }
) {

    private class Entry(val key: String) {
        var address = ""
        var name = ""
        // Identification is sticky: an advertisement that omits the name or the
        // manufacturer (scan responses and rotating payloads do) must not blank out
        // what an earlier packet already told us.
        var advertisedName: String? = null
        var manufacturer: String? = null
        val services = LinkedHashSet<String>()
        var radio = "BLE"
        var addressKind: String? = null
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
        /** Our position at the most recent sighting — where the map should mark it. */
        var lastHeardAt: LatLon? = null
        /** The tracker's own account of itself over DULT, once it has been asked. */
        var dult: DultResult? = null

        /**
         * Folds [other] into this entry when two keys turn out to be one device. The
         * cadence fingerprinter can only prove a rotation after a few packets, by which
         * time the new address has a few sightings under a key of its own.
         */
        fun absorb(other: Entry) {
            if (address.isEmpty()) address = other.address
            if (advertisedName == null) advertisedName = other.advertisedName
            if (manufacturer == null) manufacturer = other.manufacturer
            if (services.size < MAX_SERVICES) services.addAll(other.services)
            if (radio == "BLE" && other.radio != "BLE") radio = other.radio
            if (addressKind == null) addressKind = other.addressKind
            if (tracker == null) tracker = other.tracker
            if (dult == null || (other.dult?.answered == true && dult?.answered != true)) dult = other.dult
            if (other.lastSeen >= lastSeen) {
                rssi = other.rssi
                approxMetres = other.approxMetres
                lastHeardAt = other.lastHeardAt ?: lastHeardAt
            }
            firstSeen = if (firstSeen == 0L) other.firstSeen else min(firstSeen, other.firstSeen)
            lastSeen = max(lastSeen, other.lastSeen)
            sightings += other.sightings
            // The merge itself is one more address the device has used.
            rotations = rotations + other.rotations + 1
            addresses = addresses + other.addresses
            following = following || other.following
            persistent = persistent || other.persistent
            score = max(score, other.score)
            other.area.points().forEach { area.add(Fix(it.lat, it.lon, null, 0)) }
            name = BleNames.displayName(advertisedName, tracker, manufacturer, address)
        }
    }

    data class Observation(val detection: Detection, val becameFollowing: Boolean)

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun observe(
        key: String,
        address: String,
        rssi: Int,
        tracker: TrackerType?,
        approxMetres: Double?,
        rotations: Int,
        addresses: Int,
        fix: Fix?,
        hasPosition: Boolean,
        now: Long,
        advertisedName: String? = null,
        manufacturer: String? = null,
        services: List<String> = emptyList(),
        radio: String = "BLE",
        addressKind: String? = null
    ): Observation {
        val entry = entries.getOrPut(key) {
            Entry(key).apply { firstSeen = now }
        }

        val wasFollowing = entry.following

        entry.address = address
        entry.rssi = rssi
        entry.approxMetres = approxMetres
        // Never backwards: a cadence stitch can route a fresh identity (zero rotations)
        // onto an entry that has already been seen through several.
        entry.rotations = max(entry.rotations, rotations)
        entry.addresses = max(entry.addresses, addresses)
        entry.lastSeen = now
        entry.sightings++
        // A device only becomes identifiable once it advertises something we match.
        if (entry.tracker == null && tracker != null) entry.tracker = tracker

        advertisedName?.let { entry.advertisedName = it }
        manufacturer?.let { entry.manufacturer = it }
        if (services.isNotEmpty() && entry.services.size < MAX_SERVICES) entry.services.addAll(services)
        if (radio != "Unknown") entry.radio = radio
        addressKind?.let { entry.addressKind = it }
        entry.name = BleNames.displayName(entry.advertisedName, entry.tracker, entry.manufacturer, address)

        fix?.let {
            entry.area.add(it)
            entry.lastHeardAt = LatLon(it.lat, it.lon)
        }

        val duration = now - entry.firstSeen
        entry.persistent =
            duration >= persistMs() && entry.sightings >= PERSIST_MIN_SIGHTINGS

        val movedWithUs = entry.area.movedWithUs(followM())
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
            points = entry.area.points(),
            lastHeardAt = entry.lastHeardAt,
            advertisedName = entry.advertisedName,
            manufacturer = entry.manufacturer,
            services = entry.services.toList(),
            radio = entry.radio,
            addressKind = entry.addressKind,
            dult = entry.dult
        )

        return Observation(detection, entry.following && !wasFollowing)
    }

    /**
     * Attaches what a tracker said over DULT to its row. Partial results arrive as
     * the exchange proceeds; a later one always replaces an earlier one, except that
     * a final "refused" must not erase answers already received.
     */
    @Synchronized
    fun attachDult(key: String, result: DultResult) {
        val entry = entries[key] ?: return
        val current = entry.dult
        if (current != null && current.answered && !result.answered) {
            entry.dult = current.copy(complete = result.complete, error = result.error)
            return
        }
        entry.dult = result
    }

    /**
     * Moves everything recorded under [fromKey] into [intoKey], creating the target
     * when it does not exist yet. Returns false when there was nothing to move.
     */
    @Synchronized
    fun merge(fromKey: String, intoKey: String): Boolean {
        if (fromKey == intoKey) return false
        val from = entries.remove(fromKey) ?: return false
        val into = entries.getOrPut(intoKey) { Entry(intoKey) }
        into.absorb(from)
        return true
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
                points = entry.area.points(),
                lastHeardAt = entry.lastHeardAt,
                advertisedName = entry.advertisedName,
                manufacturer = entry.manufacturer,
                services = entry.services.toList(),
                radio = entry.radio,
                addressKind = entry.addressKind,
                dult = entry.dult
            )
        }

    /**
     * Drops devices that have gone quiet. Anything that proved it travels with you is
     * kept far longer — trackers sleep between reports and forgetting one would throw
     * away the only evidence that matters — but not forever: a follower silent for a
     * week is gone, and keeping its row would only leave a permanent CRITICAL ghost.
     */
    @Synchronized
    fun prune(now: Long, maxAgeMs: Long = 30 * 60 * 1000L) {
        val cutoff = now - maxAgeMs
        val followingCutoff = now - FOLLOWING_RETENTION_MS
        entries.entries.removeAll { (_, entry) ->
            if (entry.following) entry.lastSeen < followingCutoff
            else entry.lastSeen < cutoff
        }

        // Somewhere crowded can produce thousands of one-sighting devices inside the
        // retention window. Drop the least recently heard, but never anything that has
        // proved it travels with you or is holding a persistence pattern — those are
        // the entire point of keeping any of this.
        if (entries.size > MAX_ENTRIES) {
            entries.values
                .filterNot { it.following || it.persistent }
                .sortedBy { it.lastSeen }
                .take(entries.size - MAX_ENTRIES)
                .forEach { entries.remove(it.key) }
        }
    }

    /**
     * Keys (identity ids) of every device currently confirmed as following. The
     * identity resolver must not forget these while the tracker still remembers them.
     */
    @Synchronized
    fun followingKeys(): Set<String> =
        entries.values.filter { it.following }.mapTo(HashSet()) { it.key }

    /** Forgets every device. Used by "clear all data". */
    @Synchronized
    fun clear() = entries.clear()
}
