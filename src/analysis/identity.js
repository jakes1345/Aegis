'use strict'

const crypto = require('crypto')
const { PERSISTENCE } = require('../config')

// BLE addresses rotate. Tracking a device by MAC alone means a tracker that
// changes address every 15 minutes looks like a stream of unrelated strangers,
// and the "seen for 30 minutes" test never fires.
//
// The fix is to fingerprint the parts of an advertisement that do *not* rotate
// and use timing to link an address that goes quiet to one that appears.
//
// For Apple Find My specifically, everything after the first four bytes of
// manufacturer data is the rotating public key, so only the company ID, type
// and length are usable. That is coarse — two AirTags fingerprint identically —
// which is why a link is only claimed when exactly one candidate fits.
function fingerprint(device) {
  const parts = []

  const mfr = device.manufacturerHex
  if (mfr && mfr.length >= 8) {
    // Company ID + type + length. Stable across key rotation.
    parts.push(`m:${mfr.slice(0, 8)}`)
    parts.push(`ml:${mfr.length}`)
  } else if (mfr) {
    parts.push(`m:${mfr}`)
  }

  const uuids = (device.serviceUuids || [])
    .map(u => String(u).toLowerCase().replace(/-/g, ''))
    .sort()
  if (uuids.length) parts.push(`s:${uuids.join(',')}`)

  if (device.name && device.name !== 'Unknown Device') parts.push(`n:${device.name}`)
  if (device.txPowerLevel != null) parts.push(`t:${device.txPowerLevel}`)

  if (parts.length === 0) return null

  return crypto.createHash('sha1').update(parts.join('|')).digest('hex').slice(0, 16)
}

class IdentityResolver {
  constructor(opts = {}) {
    this._identities = new Map()
    this._byAddress = new Map()
    this._byFingerprint = new Map()
    this._nextId = 1
    this._gapMs = opts.macRotationGapMs || PERSISTENCE.macRotationGapMs
    this._rssiDelta = opts.macRotationRssiDelta || PERSISTENCE.macRotationRssiDelta
    // An address still advertising is not an address that rotated away.
    this._quietMs = opts.quietMs || 10000
  }

  resolve(device) {
    const now = Date.now()
    const address = device.address
    const fp = fingerprint(device)

    const existingId = this._byAddress.get(address)
    if (existingId) {
      const identity = this._identities.get(existingId)
      identity.lastSeen = now
      identity.lastRssi = device.rssi
      const addr = identity.addresses.get(address)
      if (addr) { addr.lastSeen = now; addr.rssi = device.rssi }
      return this._view(identity, false)
    }

    if (fp) {
      const candidates = []
      for (const id of this._byFingerprint.get(fp) || []) {
        const identity = this._identities.get(id)
        if (!identity) continue
        if (identity.addresses.has(address)) continue

        const quietFor = now - identity.lastSeen
        if (quietFor < this._quietMs) continue          // still talking: different device
        if (quietFor > this._gapMs) continue            // too long ago to link

        if (
          identity.lastRssi != null && device.rssi != null &&
          Math.abs(identity.lastRssi - device.rssi) > this._rssiDelta
        ) continue                                      // different distance: different device

        candidates.push(identity)
      }

      // Ambiguity is not evidence. Two identical trackers in range must stay
      // two identities rather than be silently merged into one.
      if (candidates.length === 1) {
        const identity = candidates[0]
        const previousAddress = identity.currentAddress
        identity.addresses.set(address, { firstSeen: now, lastSeen: now, rssi: device.rssi })
        identity.currentAddress = address
        identity.lastSeen = now
        identity.lastRssi = device.rssi
        identity.rotations.push({ from: previousAddress, to: address, ts: now })
        this._byAddress.set(address, identity.id)
        return this._view(identity, true)
      }
    }

    const id = `id${this._nextId++}`
    const identity = {
      id,
      fingerprint: fp,
      currentAddress: address,
      addresses: new Map([[address, { firstSeen: now, lastSeen: now, rssi: device.rssi }]]),
      firstSeen: now,
      lastSeen: now,
      lastRssi: device.rssi,
      rotations: [],
    }
    this._identities.set(id, identity)
    this._byAddress.set(address, id)
    if (fp) {
      if (!this._byFingerprint.has(fp)) this._byFingerprint.set(fp, new Set())
      this._byFingerprint.get(fp).add(id)
    }
    return this._view(identity, false)
  }

  _view(identity, rotated) {
    return {
      identityId: identity.id,
      fingerprint: identity.fingerprint,
      rotated,
      addressCount: identity.addresses.size,
      addresses: Array.from(identity.addresses.keys()),
      previousAddress: rotated
        ? identity.rotations[identity.rotations.length - 1].from
        : null,
      rotations: identity.rotations.length,
      firstSeen: identity.firstSeen,
    }
  }

  get(id) {
    return this._identities.get(id) || null
  }

  clearOld(maxAgeMs) {
    const cutoff = Date.now() - maxAgeMs
    for (const [id, identity] of this._identities) {
      if (identity.lastSeen >= cutoff) continue
      this._identities.delete(id)
      for (const addr of identity.addresses.keys()) this._byAddress.delete(addr)
      if (identity.fingerprint) {
        const set = this._byFingerprint.get(identity.fingerprint)
        if (set) {
          set.delete(id)
          if (set.size === 0) this._byFingerprint.delete(identity.fingerprint)
        }
      }
    }
  }
}

module.exports = { IdentityResolver, fingerprint }
