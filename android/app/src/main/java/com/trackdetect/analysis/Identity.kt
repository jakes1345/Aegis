package com.trackdetect.analysis

import android.bluetooth.le.ScanRecord
import java.security.MessageDigest
import kotlin.math.abs

/**
 * BLE addresses rotate. Tracking by MAC alone means a tracker that changes
 * address every fifteen minutes looks like a stream of unrelated strangers, and
 * the "present for thirty minutes" test never fires.
 *
 * So devices are fingerprinted on the parts of an advertisement that do not
 * rotate. For Apple Find My specifically, everything after the first two bytes
 * of the payload is the rotating public key, leaving only the type and length —
 * coarse enough that two AirTags fingerprint identically, which is exactly why
 * a rotation is only linked when there is precisely one candidate.
 */
object Fingerprint {

    fun of(record: ScanRecord?): String? {
        if (record == null) return null
        val parts = ArrayList<String>()

        val mfr = record.manufacturerSpecificData
        if (mfr != null && mfr.size() > 0) {
            for (i in 0 until mfr.size()) {
                val company = mfr.keyAt(i)
                val payload = mfr.valueAt(i) ?: continue
                val head = payload.take(2).joinToString("") { "%02x".format(it) }
                parts.add("m:%04x:%s:%d".format(company, head, payload.size))
            }
        }

        record.serviceUuids
            ?.map { it.uuid.toString() }
            ?.sorted()
            ?.takeIf { it.isNotEmpty() }
            ?.let { parts.add("s:" + it.joinToString(",")) }

        record.deviceName?.takeIf { it.isNotBlank() }?.let { parts.add("n:$it") }

        val tx = record.txPowerLevel
        if (tx != Int.MIN_VALUE) parts.add("t:$tx")

        if (parts.isEmpty()) return null

        val digest = MessageDigest.getInstance("SHA-1").digest(parts.joinToString("|").toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

class IdentityResolver(
    private val rotationWindowMs: Long = 3 * 60 * 1000,
    private val quietMs: Long = 10_000,
    private val rssiDelta: Int = 12
) {

    class Identity(val id: String, val fingerprint: String?) {
        val addresses = LinkedHashSet<String>()
        var currentAddress: String = ""
        var lastSeen: Long = 0
        var lastRssi: Int = 0
        var rotations: Int = 0
    }

    data class Resolution(
        val identity: Identity,
        val rotated: Boolean,
        val previousAddress: String?
    )

    private val byId = HashMap<String, Identity>()
    private val byAddress = HashMap<String, String>()
    private val byFingerprint = HashMap<String, MutableSet<String>>()
    private var counter = 0

    // resolve() runs on the Bluetooth callback thread and prune() on the service's
    // coroutine, so every access to the three maps below has to be guarded. Without
    // this the pruning pass iterates a map the scan thread is writing to and the
    // service dies on ConcurrentModificationException mid-scan.
    @Synchronized
    fun prune(now: Long, maxAgeMs: Long = 30 * 60_000L) {
        val cutoff = now - maxAgeMs
        byId.values.filter { it.lastSeen < cutoff }.map { it.id }.forEach { forget(it) }

        // A thirty-minute window in a station or a shopping centre, with every
        // privacy-conscious device rotating its address every quarter hour, is enough
        // to accumulate a very large number of one-off identities. Cap it by dropping
        // the least recently heard — they are the ones least likely to be on you.
        if (byId.size > MAX_IDENTITIES) {
            byId.values
                .sortedBy { it.lastSeen }
                .take(byId.size - MAX_IDENTITIES)
                .map { it.id }
                .forEach { forget(it) }
        }
    }

    private fun forget(id: String) {
        val identity = byId.remove(id) ?: return
        identity.addresses.forEach { byAddress.remove(it) }
        if (identity.fingerprint != null) {
            byFingerprint[identity.fingerprint]?.let { set ->
                set.remove(id)
                if (set.isEmpty()) byFingerprint.remove(identity.fingerprint)
            }
        }
    }

    /** Forgets every identity. Used by "clear all data". */
    @Synchronized
    fun clear() {
        byId.clear()
        byAddress.clear()
        byFingerprint.clear()
    }

    @Synchronized
    fun resolve(address: String, rssi: Int, fingerprint: String?, now: Long): Resolution {
        // byId may not hold the id if a prune raced ahead of a stale byAddress entry;
        // fall through to re-creating the identity rather than throwing.
        byAddress[address]?.let { id -> byId[id] }?.let { identity ->
            identity.lastSeen = now
            identity.lastRssi = rssi
            return Resolution(identity, false, null)
        }

        if (fingerprint != null) {
            val candidates = byFingerprint[fingerprint]
                ?.mapNotNull { byId[it] }
                ?.filter { candidate ->
                    if (candidate.addresses.contains(address)) return@filter false
                    val quietFor = now - candidate.lastSeen
                    // Still advertising means it is a different device, not a rotation.
                    if (quietFor < quietMs) return@filter false
                    if (quietFor > rotationWindowMs) return@filter false
                    abs(candidate.lastRssi - rssi) <= rssiDelta
                }
                ?: emptyList()

            // Ambiguity is not evidence. Two identical trackers in range stay two
            // identities rather than being silently merged into one.
            if (candidates.size == 1) {
                val identity = candidates[0]
                val previous = identity.currentAddress
                identity.addresses.add(address)
                identity.currentAddress = address
                identity.lastSeen = now
                identity.lastRssi = rssi
                identity.rotations++
                byAddress[address] = identity.id
                return Resolution(identity, true, previous)
            }
        }

        val identity = Identity("id${++counter}", fingerprint)
        identity.addresses.add(address)
        identity.currentAddress = address
        identity.lastSeen = now
        identity.lastRssi = rssi
        byId[identity.id] = identity
        byAddress[address] = identity.id
        if (fingerprint != null) {
            byFingerprint.getOrPut(fingerprint) { HashSet() }.add(identity.id)
        }
        return Resolution(identity, false, null)
    }

    private companion object {
        /** Ceiling on simultaneously remembered identities — see [prune]. */
        const val MAX_IDENTITIES = 4000
    }
}
