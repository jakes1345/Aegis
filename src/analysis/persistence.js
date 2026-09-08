'use strict'

const FOLLOW_THRESHOLD_MS = 10 * 60 * 1000  // 10 minutes before flagging as "following"
const FOLLOW_MIN_APPEARANCES = 5              // must appear at least 5 times
const RSSI_HISTORY_MAX = 60

class PersistenceTracker {
  constructor() {
    this._devices = new Map()
  }

  update(device) {
    const id = device.id
    const now = Date.now()

    if (!this._devices.has(id)) {
      const entry = {
        ...device,
        firstSeen: now,
        lastSeen: now,
        appearances: 1,
        rssiHistory: [device.rssi],
        following: false,
        followDuration: 0,
        alert: !device.benign && device.trackerType !== null,
        newAlert: !device.benign && device.trackerType !== null,
        alertMessage: null,
        alertTime: device.trackerType !== null ? now : null,
      }
      if (entry.newAlert) {
        entry.alertMessage = this._buildAlert(entry)
      }
      this._devices.set(id, entry)
      return entry
    }

    const existing = this._devices.get(id)
    const wasFollowing = existing.following
    const hadAlert = existing.alert

    existing.lastSeen = now
    existing.appearances++
    existing.rssi = device.rssi
    existing.rssiHistory.push(device.rssi)
    if (existing.rssiHistory.length > RSSI_HISTORY_MAX) {
      existing.rssiHistory.shift()
    }

    // Update detection properties in case we now identify it
    if (!existing.trackerType && device.trackerType) {
      existing.trackerType = device.trackerType
      existing.trackerInfo = device.trackerInfo
      existing.threat = device.threat
    }
    existing.threatScore = device.threatScore
    existing.distance = device.distance

    const duration = now - existing.firstSeen
    const isFollowing = (
      !existing.benign &&
      duration >= FOLLOW_THRESHOLD_MS &&
      existing.appearances >= FOLLOW_MIN_APPEARANCES
    )

    existing.following = isFollowing
    existing.followDuration = duration

    // Boost score for confirmed following behavior
    if (isFollowing) {
      existing.threatScore = Math.min(100, existing.threatScore + 15)
      if (existing.trackerType) {
        existing.threatScore = Math.min(100, existing.threatScore + 10)
      }
    }

    // Generate alert: on first detection of tracker, or when following is newly confirmed
    const shouldAlert = !existing.benign && (existing.trackerType !== null || isFollowing)
    const becameFollowing = isFollowing && !wasFollowing

    existing.newAlert = shouldAlert && (!hadAlert || becameFollowing)
    if (existing.newAlert) {
      existing.alert = true
      existing.alertMessage = this._buildAlert(existing)
      existing.alertTime = now
    }

    return existing
  }

  _buildAlert(device) {
    const mins = Math.floor((device.followDuration || 0) / 60000)
    const followStr = device.following && mins > 0 ? ` - following for ${mins}m` : ''

    if (device.trackerType === 'airtag') {
      return `Apple AirTag detected${followStr} - CHECK YOUR VEHICLE AND BELONGINGS`
    }
    if (device.trackerType === 'findmy_compatible') {
      return `Apple Find My tracker detected${followStr}`
    }
    if (device.trackerType === 'tile') {
      return `Tile tracker detected${followStr}`
    }
    if (device.trackerType === 'samsung_smarttag') {
      return `Samsung SmartTag detected${followStr}`
    }
    if (device.trackerType === 'chipolo') {
      return `Chipolo tracker detected${followStr}`
    }
    if (device.trackerType === 'pebblebee') {
      return `Pebblebee tracker detected${followStr}`
    }
    if (device.following) {
      return `Unknown BLE device following for ${mins}m - investigate (${device.address})`
    }
    return `Tracker detected: ${device.name} [${device.address}]`
  }

  avgRssi(device) {
    if (!device.rssiHistory || device.rssiHistory.length === 0) return device.rssi
    const sum = device.rssiHistory.reduce((a, b) => a + b, 0)
    return Math.round(sum / device.rssiHistory.length)
  }

  // Returns true if RSSI is very stable (variance < 5 dBm) - suggests physically attached
  isStableSignal(device) {
    const h = device.rssiHistory
    if (!h || h.length < 5) return false
    const avg = h.reduce((a, b) => a + b, 0) / h.length
    const variance = h.reduce((a, b) => a + Math.pow(b - avg, 2), 0) / h.length
    return variance < 25 // std dev < 5 dBm
  }

  getAllDevices() {
    return Array.from(this._devices.values())
  }

  clearOld(maxAgeMs = 30 * 60 * 1000) {
    const cutoff = Date.now() - maxAgeMs
    for (const [id, device] of this._devices) {
      if (device.lastSeen < cutoff) {
        this._devices.delete(id)
      }
    }
  }
}

module.exports = PersistenceTracker
