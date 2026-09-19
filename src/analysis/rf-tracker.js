'use strict'

const periodicity = require('./periodicity')
const { ObservationArea } = require('./geo')
const { SDR, GEO } = require('../config')

const RAT_NARROWBAND_HZ = 250e3

// Groups detections into persistent emitters and decides which of them behave
// like a tracker rather than like the rest of the radio environment.
//
// The discriminators, in order of how much they actually prove:
//
//   1. It moved with you. An emitter heard from two places 300 m apart is
//      physically travelling with you. Nothing else comes close as evidence.
//   2. It transmits on a fixed schedule. Trackers beacon; phones do not.
//   3. It is narrowband and in an uplink band. That is a modem talking out,
//      not a tower talking down.
//
// Periodicity alone is weak on its own — a smart meter on 915 MHz is perfectly
// periodic and perfectly innocent — so it is scored low until movement or an
// uplink band corroborates it.
class RFTracker {
  constructor(opts = {}) {
    this._emitters = new Map()
    this._nextId = 1
    this._eventMergeMs = opts.eventMergeMs || SDR.eventMergeMs
  }

  ingest(signals, context = {}) {
    const touched = []
    for (const sig of signals) {
      const emitter = this._match(sig, context)
      this._update(emitter, sig, context)
      touched.push(emitter)
    }
    return touched
  }

  _tolerance(sig, context) {
    const step = context.stepHz || SDR.watchBinHz
    return Math.max(2 * step, 50e3, sig.bandwidthHz / 2)
  }

  _match(sig, context) {
    const tol = this._tolerance(sig, context)
    for (const emitter of this._emitters.values()) {
      const overlap =
        sig.centerHz >= emitter.lowHz - tol && sig.centerHz <= emitter.highHz + tol
      const reverse =
        emitter.centerHz >= sig.lowHz - tol && emitter.centerHz <= sig.highHz + tol
      if (overlap || reverse) return emitter
    }

    const id = `rf${this._nextId++}`
    const emitter = {
      id,
      kind: 'rf',
      centerHz: sig.centerHz,
      lowHz: sig.lowHz,
      highHz: sig.highHz,
      bandwidthHz: sig.bandwidthHz,
      band: context.band || null,
      category: context.band ? context.band.category : 'unknown',
      firstSeen: sig.tsMs,
      lastSeen: sig.tsMs,
      detections: 0,
      eventStarts: [],
      lastEventTs: -Infinity,
      excessHistory: [],
      peakExcessDb: sig.excessDb,
      area: new ObservationArea(),
      observedWhileMoving: false,
      periodicity: null,
      classification: 'unclassified',
      threat: 'UNKNOWN',
      threatScore: 0,
      reasons: [],
      seenInModes: new Set(),
    }
    this._emitters.set(id, emitter)
    return emitter
  }

  _update(emitter, sig, context) {
    emitter.lastSeen = sig.tsMs
    emitter.detections++
    emitter.bandwidthHz = Math.max(emitter.bandwidthHz, sig.bandwidthHz)
    emitter.lowHz = Math.min(emitter.lowHz, sig.lowHz)
    emitter.highHz = Math.max(emitter.highHz, sig.highHz)
    emitter.peakExcessDb = Math.max(emitter.peakExcessDb, sig.excessDb)
    if (context.mode) emitter.seenInModes.add(context.mode)
    if (context.band) {
      emitter.band = context.band
      emitter.category = context.band.category
    }

    // Track the centre with a slow EMA so one noisy sweep cannot drag it.
    emitter.centerHz = emitter.centerHz * 0.85 + sig.centerHz * 0.15

    emitter.excessHistory.push(sig.excessDb)
    if (emitter.excessHistory.length > 120) emitter.excessHistory.shift()

    // Consecutive sweeps of one transmission are one event, not several.
    // Timing analysis needs transmission starts, not sweep counts.
    if (sig.tsMs - emitter.lastEventTs > this._eventMergeMs) {
      emitter.eventStarts.push(sig.tsMs)
      if (emitter.eventStarts.length > 400) emitter.eventStarts.shift()
    }
    emitter.lastEventTs = sig.tsMs

    if (context.fix) emitter.area.add(context.fix)
    if (context.isMoving) emitter.observedWhileMoving = true

    emitter.periodicity = periodicity.analyze(emitter.eventStarts)
    this._classify(emitter)
  }

  _classify(emitter) {
    const reasons = []
    let score = 0

    const p = emitter.periodicity
    const isPeriodic = !!(p && p.periodic)
    const movedWithUs = emitter.area.movedWithUs()
    const spanM = emitter.area.span()
    const narrowband = emitter.bandwidthHz <= RAT_NARROWBAND_HZ
    const uplink = emitter.category === 'cellular' || emitter.category === 'satellite'

    if (movedWithUs) {
      score += 45
      reasons.push(
        `Heard from ${emitter.area.count} locations up to ${Math.round(spanM)} m apart — this transmitter is physically travelling with you`
      )
    } else if (emitter.area.count > 1) {
      reasons.push(`Heard from ${emitter.area.count} locations, max separation ${Math.round(spanM)} m — not yet enough to prove it moves with you`)
    }

    if (isPeriodic) {
      const common = periodicity.matchesCommonInterval(p.periodMs)
      score += uplink ? 32 : 14
      reasons.push(
        `Transmits on a schedule: every ${periodicity.describePeriod(p.periodMs)} ` +
        `(${p.matched}/${p.total} intervals, jitter ${Math.round(p.jitterMs / 1000)}s)`
      )
      if (common) {
        score += 8
        reasons.push(`Interval matches a stock tracker reporting rate (${common}s)`)
      }
    }

    if (uplink) {
      score += 12
      reasons.push(
        emitter.category === 'satellite'
          ? 'Transmitting in a satellite terminal uplink band — only a device talking to a satellite uses these'
          : 'Transmitting in a cellular uplink band — this is a modem sending, not a tower broadcasting'
      )
    }

    if (narrowband && uplink) {
      score += 10
      reasons.push(`Narrowband emission (${Math.round(emitter.bandwidthHz / 1e3)} kHz) — consistent with NB-IoT / LTE-M / GSM, the radios used in asset trackers`)
    }

    if (emitter.category === 'ism' && isPeriodic && !movedWithUs) {
      reasons.push('Periodic ISM traffic is common and usually benign (smart meters, weather stations, TPMS). Movement evidence is needed before this means anything.')
    }

    if (emitter.category === 'rfid') {
      reasons.push('UHF RFID reader interrogation carrier detected. This is fixed infrastructure (parking garage, toll gantry, border checkpoint, warehouse portal) scanning for passive RFID tags in range. It cannot track you — it can only read a tag you carry. If you passed through a checkpoint and this signal appeared, your RFID-equipped credential (key fob, pass, vehicle sticker) was scanned.')
    }

    let classification = 'unclassified'
    if (emitter.category === 'rfid') {
      classification = movedWithUs ? 'rfid_mobile_reader' : 'rfid_reader'
    } else if (movedWithUs && (isPeriodic || uplink)) {
      classification = emitter.category === 'satellite' ? 'satellite_tracker' : 'mobile_tracker'
    } else if (isPeriodic && uplink) {
      classification = emitter.category === 'satellite' ? 'satellite_beacon' : 'cellular_beacon'
    } else if (movedWithUs) {
      classification = 'mobile_emitter'
    } else if (isPeriodic) {
      classification = 'periodic_emitter'
    }

    score = Math.max(0, Math.min(100, Math.round(score)))

    emitter.threatScore = score
    emitter.classification = classification
    emitter.reasons = reasons
    emitter.threat =
      score >= 80 ? 'CRITICAL' :
      score >= 60 ? 'HIGH' :
      score >= 35 ? 'MEDIUM' :
      score > 0 ? 'LOW' : 'NONE'

    emitter.name = this._name(emitter)
    return emitter
  }

  _name(emitter) {
    const mhz = (emitter.centerHz / 1e6).toFixed(3)
    const labels = {
      mobile_tracker: 'Tracker on your vehicle',
      satellite_tracker: 'Satellite tracker on you',
      cellular_beacon: 'Cellular beacon',
      satellite_beacon: 'Satellite beacon',
      rfid_reader: 'UHF RFID reader portal',
      rfid_mobile_reader: 'Mobile UHF RFID reader (moving with you)',
      mobile_emitter: 'Emitter moving with you',
      periodic_emitter: 'Periodic emitter',
      unclassified: 'RF emitter',
    }
    return `${labels[emitter.classification]} ${mhz} MHz`
  }

  // Frequencies worth parking watch mode on: strong or already suspicious,
  // ranked so the scarce receiver time goes where it pays off.
  candidates(limit = 5) {
    return Array.from(this._emitters.values())
      .filter(e => e.detections >= 2)
      .sort((a, b) => {
        if (b.threatScore !== a.threatScore) return b.threatScore - a.threatScore
        return b.detections - a.detections
      })
      .slice(0, limit)
  }

  emitters() {
    return Array.from(this._emitters.values())
  }

  get(id) {
    return this._emitters.get(id) || null
  }

  clearOld(maxAgeMs) {
    const cutoff = Date.now() - maxAgeMs
    for (const [id, e] of this._emitters) {
      // An emitter that proved it travels with us is worth keeping even when
      // it goes quiet — trackers sleep between reports.
      if (e.lastSeen < cutoff && !e.area.movedWithUs()) this._emitters.delete(id)
    }
  }
}

module.exports = RFTracker
module.exports.RAT_NARROWBAND_HZ = RAT_NARROWBAND_HZ
