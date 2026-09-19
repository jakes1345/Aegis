'use strict'

const { GEO } = require('../config')

const EARTH_RADIUS_M = 6371008.8

function toRad(deg) {
  return (deg * Math.PI) / 180
}

// Great-circle distance in metres.
function haversine(a, b) {
  if (!a || !b) return null
  const dLat = toRad(b.lat - a.lat)
  const dLon = toRad(b.lon - a.lon)
  const lat1 = toRad(a.lat)
  const lat2 = toRad(b.lat)
  const h =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2)
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)))
}

// The set of places one emitter was observed from. Kept small by collapsing
// fixes that land in the same spot, so the pairwise span stays cheap.
class ObservationArea {
  constructor(maxPoints = GEO.maxLocationsPerDevice) {
    this._points = []
    this._maxPoints = maxPoints
    this._spanCache = 0
  }

  add(fix) {
    if (!fix || typeof fix.lat !== 'number' || typeof fix.lon !== 'number') return false

    for (const p of this._points) {
      if (haversine(p, fix) <= GEO.locationClusterM) {
        p.count++
        p.lastSeen = fix.ts || Date.now()
        return false
      }
    }

    this._spanCache = null
    this._points.push({
      lat: fix.lat,
      lon: fix.lon,
      count: 1,
      firstSeen: fix.ts || Date.now(),
      lastSeen: fix.ts || Date.now(),
    })

    // Drop the least-visited cluster rather than the oldest: a place seen once
    // in passing matters less than one we keep returning to.
    if (this._points.length > this._maxPoints) {
      let worst = 0
      for (let i = 1; i < this._points.length; i++) {
        if (this._points[i].count < this._points[worst].count) worst = i
      }
      this._points.splice(worst, 1)
      this._spanCache = null
    }
    return true
  }

  get count() {
    return this._points.length
  }

  get points() {
    return this._points
  }

  // Largest distance between any two places this emitter was heard from.
  // This is the number that separates "my neighbour's Tile" from "it is on my car".
  // Cached because it is read on every detection and is quadratic in points.
  span() {
    if (this._spanCache !== null) return this._spanCache
    if (this._points.length < 2) {
      this._spanCache = 0
      return 0
    }
    let max = 0
    for (let i = 0; i < this._points.length; i++) {
      for (let j = i + 1; j < this._points.length; j++) {
        const d = haversine(this._points[i], this._points[j])
        if (d > max) max = d
      }
    }
    this._spanCache = max
    return max
  }

  movedWithUs() {
    return this.span() >= GEO.followDisplacementM
  }

  toJSON() {
    return this._points
  }

  static fromJSON(points, maxPoints) {
    const area = new ObservationArea(maxPoints)
    area._points = Array.isArray(points) ? points.slice() : []
    area._spanCache = null
    return area
  }
}

// Where we have been, and whether we are actually going anywhere. Without this
// every stationary detection looks like a follow.
class LocationTrack {
  constructor() {
    this._current = null
    this._history = []
    this._distanceM = 0
  }

  update(fix) {
    if (!fix || typeof fix.lat !== 'number' || typeof fix.lon !== 'number') return
    const prev = this._current
    this._current = { ...fix, ts: fix.ts || Date.now() }

    if (prev) {
      const d = haversine(prev, this._current)
      if (d != null && d > 5) this._distanceM += d
    }

    this._history.push(this._current)
    if (this._history.length > 5000) this._history.splice(0, 1000)
  }

  current() {
    return this._current
  }

  hasFix() {
    return this._current != null
  }

  get totalDistanceM() {
    return this._distanceM
  }

  // Prefer the receiver's own speed; fall back to differencing recent fixes.
  speedMs() {
    if (!this._current) return null
    if (typeof this._current.speed === 'number' && this._current.speed >= 0) {
      return this._current.speed
    }
    const n = this._history.length
    if (n < 2) return null
    const a = this._history[n - 2]
    const b = this._history[n - 1]
    const dt = (b.ts - a.ts) / 1000
    if (dt <= 0) return null
    const d = haversine(a, b)
    return d == null ? null : d / dt
  }

  isMoving() {
    const s = this.speedMs()
    return s != null && s >= GEO.movingSpeedMs
  }

  // How far we have travelled since a given moment — the window in which a
  // co-present device had the chance to prove it is following.
  displacementSince(ts) {
    if (!this._current) return 0
    let start = null
    for (const f of this._history) {
      if (f.ts >= ts) { start = f; break }
    }
    if (!start) return 0
    return haversine(start, this._current) || 0
  }
}

module.exports = { haversine, ObservationArea, LocationTrack }
