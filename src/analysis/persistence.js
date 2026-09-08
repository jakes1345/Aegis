'use strict'

const { PERSISTENCE, GEO } = require('../config')
const { ObservationArea } = require('./geo')

// Tracks how long a device stays with you, and — where a position source
// exists — whether it actually travelled with you.
//
// The distinction matters more than anything else in this tool. "Seen five
// times over ten minutes" describes a tracker in your bumper and it equally
// describes your neighbour's Tile through the wall. Only displacement
// separates them: a transmitter heard from two places 300 m apart is on your
// vehicle, full stop.
//
// With no position source, that claim cannot be made, so it is not made. Such
// devices are reported as PERSISTENT and the UI says why the stronger claim is
// unavailable, rather than crying wolf.
class PersistenceTracker {
  constructor() {
    this._devices = new Map()
  }

  update(device, context = {}) {
    const key = context.identity ? context.identity.identityId : device.id
    const now = Date.now()
    const fix = context.fix || null
    const track = context.locationTrack || null

    let entry = this._devices.get(key)

    if (!entry) {
      entry = {
        ...device,
        key,
        identity: context.identity || null,
        firstSeen: now,
        lastSeen: now,
        appearances: 1,
        rssiHistory: [device.rssi],
        area: new ObservationArea(),
        persistent: false,
        following: false,
        followConfidence: 'none',
        displacementM: 0,
        followDuration: 0,
        addressRotations: 0,
        alert: !device.benign && device.trackerType !== null,
        newAlert: !device.benign && device.trackerType !== null,
        alertMessage: null,
        alertTime: device.trackerType !== null ? now : null,
      }
      if (fix) entry.area.add(fix)
      if (entry.newAlert) entry.alertMessage = this._buildAlert(entry)
      this._devices.set(key, entry)
      return entry
    }

    const wasFollowing = entry.following
    const wasPersistent = entry.persistent
    const hadAlert = entry.alert

    entry.lastSeen = now
    entry.appearances++
    entry.rssi = device.rssi
    entry.address = device.address
    entry.rssiHistory.push(device.rssi)
    if (entry.rssiHistory.length > PERSISTENCE.rssiHistoryMax) entry.rssiHistory.shift()

    if (context.identity) {
      entry.identity = context.identity
      entry.addressRotations = context.identity.rotations
    }

    if (fix) entry.area.add(fix)

    // A device only becomes identifiable once it advertises something we match.
    if (!entry.trackerType && device.trackerType) {
      entry.trackerType = device.trackerType
      entry.trackerInfo = device.trackerInfo
      entry.threat = device.threat
    }
    entry.threatScore = device.threatScore
    entry.distance = device.distance

    const duration = now - entry.firstSeen
    entry.followDuration = duration
    entry.displacementM = entry.area.span()

    entry.persistent =
      !entry.benign &&
      duration >= PERSISTENCE.followThresholdMs &&
      entry.appearances >= PERSISTENCE.followMinAppearances

    const hasPosition = !!(track && track.hasFix())
    const movedWithUs = entry.area.movedWithUs()

    // We travelled, and it was still there at the far end.
    entry.following = entry.persistent && movedWithUs
    entry.followConfidence = entry.following
      ? 'confirmed'
      : entry.persistent
        ? (hasPosition ? 'insufficient_movement' : 'no_position_source')
        : 'none'

    if (entry.following) {
      entry.threatScore = Math.min(100, entry.threatScore + 30)
      if (entry.trackerType) entry.threatScore = Math.min(100, entry.threatScore + 10)
    } else if (entry.persistent) {
      entry.threatScore = Math.min(100, entry.threatScore + 10)
    }

    if (entry.addressRotations > 0) {
      // Rotating its address while staying with you is what a tracker built to
      // avoid exactly this kind of detection does.
      entry.threatScore = Math.min(100, entry.threatScore + 8)
    }

    const shouldAlert = !entry.benign && (entry.trackerType !== null || entry.following || entry.persistent)
    const becameFollowing = entry.following && !wasFollowing
    const becamePersistent = entry.persistent && !wasPersistent

    entry.newAlert = shouldAlert && (!hadAlert || becameFollowing || becamePersistent)
    if (entry.newAlert) {
      entry.alert = true
      entry.alertMessage = this._buildAlert(entry)
      entry.alertTime = now
    }

    return entry
  }

  _buildAlert(device) {
    const mins = Math.floor((device.followDuration || 0) / 60000)
    const names = {
      airtag: 'Apple AirTag',
      findmy_thirdparty: 'Apple Find My tracker',
      tile: 'Tile tracker',
      samsung_smarttag: 'Samsung SmartTag',
      samsung_smarttag2: 'Samsung SmartTag2',
      chipolo: 'Chipolo tracker',
      pebblebee: 'Pebblebee tracker',
      orbit: 'Orbit/KeySmart tracker',
      nut_tracker: 'Nut tracker',
      generic_gps_ble: 'GPS tracker (BLE config)',
      eddystone: 'Eddystone beacon',
      wifi_tracker_ssid: 'WiFi GPS tracker',
      wifi_tracker_oui: 'WiFi tracker module',
    }
    const label = names[device.trackerType] || (device.scanType === 'wifi' ? 'Unknown WiFi device' : 'Unknown BLE device')

    if (device.following) {
      return `${label} CONFIRMED FOLLOWING — travelled ${Math.round(device.displacementM)} m with you over ${mins}m. It is physically on you or your vehicle.`
    }
    if (device.persistent && device.followConfidence === 'no_position_source') {
      return `${label} persistent for ${mins}m (${device.appearances}x) — no GPS, so cannot confirm it is following`
    }
    if (device.persistent) {
      return `${label} persistent for ${mins}m — you have not moved far enough yet to confirm`
    }
    return `${label} detected: ${device.name} [${device.address}]`
  }

  avgRssi(device) {
    if (!device.rssiHistory || device.rssiHistory.length === 0) return device.rssi
    const sum = device.rssiHistory.reduce((a, b) => a + b, 0)
    return Math.round(sum / device.rssiHistory.length)
  }

  // A signal that barely varies while you drive is not being received through
  // changing geometry — it is bolted to the same object as the receiver.
  isStableSignal(device) {
    const h = device.rssiHistory
    if (!h || h.length < 5) return false
    const avg = h.reduce((a, b) => a + b, 0) / h.length
    const variance = h.reduce((a, b) => a + Math.pow(b - avg, 2), 0) / h.length
    return variance < 25
  }

  getAllDevices() {
    return Array.from(this._devices.values())
  }

  clearOld(maxAgeMs = 30 * 60 * 1000) {
    const cutoff = Date.now() - maxAgeMs
    for (const [key, device] of this._devices) {
      // Anything that has proven it moves with you is kept regardless of
      // silence — trackers sleep between reports.
      if (device.lastSeen < cutoff && !device.following) this._devices.delete(key)
    }
  }
}

module.exports = PersistenceTracker
module.exports.GEO = GEO
