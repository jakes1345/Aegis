'use strict'

const { CELLULAR, GEO } = require('../config')
const { haversine } = require('../analysis/geo')
const { RAT_RANK } = require('../scanner/cellular')

const BASELINE_MATURITY = 20
const STATIONARY_M = 200

// Weights are deliberately unequal. A forced downgrade to 2G is close to
// conclusive; an unfamiliar cell ID on its own just means you drove somewhere
// new. Nothing here fires on a single observation.
const CHECKS = {
  rat_downgrade: 35,
  cellid_tac_mismatch: 30,
  tac_change_stationary: 25,
  signal_outlier: 22,
  unknown_cell: 20,
  ephemeral_cell: 20,
  cell_flapping: 18,
  no_neighbors: 15,
}

function areaKey(fix) {
  if (!fix) return 'nowhere'
  // ~110 m of latitude per 0.001 degree — close enough to the cluster radius.
  return `${fix.lat.toFixed(3)},${fix.lon.toFixed(3)}`
}

// Detects cell-site simulators by comparing what the baseband reports now
// against what it has reported here before.
//
// There is no public database lookup and no API key. The baseline is built
// from your own observations and kept on disk, which means it is accurate for
// the places you actually go and works with no network connection. The cost is
// that it needs warming up: the checks that depend on familiarity stay silent
// until enough observations exist to make "unfamiliar" meaningful.
class IMSICatcherDetector {
  constructor(store) {
    this._store = store
    const data = store.load({ version: 1, observations: 0, cells: {}, areas: {} })
    if (!data.cells) data.cells = {}
    if (!data.areas) data.areas = {}
    if (!data.observations) data.observations = 0

    this._previous = null
    this._recentServing = []
    this._lastSignature = null
  }

  get baseline() {
    return this._store.data
  }

  get mature() {
    return this._store.data.observations >= BASELINE_MATURITY
  }

  get maturity() {
    return Math.min(1, this._store.data.observations / BASELINE_MATURITY)
  }

  observe(cell, fix, locationTrack) {
    const data = this._store.data
    const findings = []
    const now = cell.ts || Date.now()
    const ak = areaKey(fix)
    const area = data.areas[ak]
    const known = data.cells[cell.key]
    const prev = this._previous

    const displacement = prev && prev.fix && fix ? haversine(prev.fix, fix) : null
    const stationary = displacement != null ? displacement < STATIONARY_M
      : (locationTrack ? !locationTrack.isMoving() : false)

    // 1. Forced downgrade. 2G has no mutual authentication, so a catcher that
    //    wants your IMSI pushes you down to it. A genuine network does not
    //    demote you in a place that has always had LTE.
    if (area && area.bestRatRank >= 4 && cell.ratRank > 0 && cell.ratRank <= 3) {
      findings.push({
        id: 'rat_downgrade',
        severity: 'CRITICAL',
        title: `Downgraded to ${(cell.rat || 'legacy').toUpperCase()} where ${area.bestRat ? area.bestRat.toUpperCase() : 'LTE'} is normal`,
        detail:
          'Your modem dropped to a legacy technology at a location that has always had a faster one. ' +
          '2G and 3G lack the mutual authentication that stops a fake tower impersonating your network, ' +
          'so forcing a downgrade is the standard first move of an IMSI catcher.',
      })
    }

    // 2. Same cell ID, different area code. Real cells do not move between
    //    tracking areas; a spoofed identity does.
    if (known && cell.tac && known.tac && known.tac !== cell.tac) {
      findings.push({
        id: 'cellid_tac_mismatch',
        severity: 'CRITICAL',
        title: `Cell ${cell.cellId} now claims TAC ${cell.tac} (previously ${known.tac})`,
        detail:
          'A cell ID that has changed its tracking area code is reporting an identity inconsistent with ' +
          'its own history. Genuine cells keep a stable identity; this pattern fits a device replaying ' +
          'or fabricating one.',
      })
    }

    // 3. Area code churn without moving. Re-registration costs battery and is
    //    exactly what a catcher provokes to pull your IMSI.
    if (prev && prev.cell.tac && cell.tac && prev.cell.tac !== cell.tac && stationary) {
      findings.push({
        id: 'tac_change_stationary',
        severity: 'HIGH',
        title: `Tracking area changed (${prev.cell.tac} to ${cell.tac}) without moving`,
        detail:
          displacement != null
            ? `You moved ${Math.round(displacement)} m since the last reading, which is not far enough to leave a tracking area. Forced re-registration is how a catcher makes your phone identify itself.`
            : 'The tracking area changed while stationary. Forced re-registration is how a catcher makes your phone identify itself.',
      })
    }

    // 4. Overpowering signal. A catcher has to out-shout the real network to
    //    win your phone, and it is standing much closer than a tower.
    if (area && area.maxSignalDbm != null && cell.signalDbm != null &&
        cell.signalDbm > area.maxSignalDbm + CELLULAR.signalOutlierDb) {
      findings.push({
        id: 'signal_outlier',
        severity: 'HIGH',
        title: `Serving cell is ${Math.round(cell.signalDbm - area.maxSignalDbm)} dB stronger than anything seen here before`,
        detail:
          `Strongest previously recorded here: ${Math.round(area.maxSignalDbm)} dBm. Now: ${Math.round(cell.signalDbm)} dBm. ` +
          'A catcher must overpower the legitimate network to capture handsets, and it is far closer than a real tower.',
      })
    }

    // 5. Unfamiliar cell — only meaningful once the baseline knows this place.
    if (!known && this.mature && area && Object.keys(area.cells || {}).length >= 2) {
      findings.push({
        id: 'unknown_cell',
        severity: 'MEDIUM',
        title: `Unrecognised cell ${cell.cellId} (TAC ${cell.tac || '?'}) at a familiar location`,
        detail:
          `You have recorded ${Object.keys(area.cells).length} cells here before and this is not one of them. ` +
          'On its own this is weak — networks do add cells — but combined with any other finding it matters.',
      })
    }

    // 6. No neighbours. A real handset in a real network almost always sees
    //    other cells; catchers commonly suppress the neighbour list to stop
    //    you handing back to the genuine network.
    if (cell.neighbors === 0) {
      findings.push({
        id: 'no_neighbors',
        severity: 'MEDIUM',
        title: 'Serving cell reports no neighbouring cells',
        detail:
          'A handset camped on a real network normally sees other cells nearby. An empty neighbour list ' +
          'prevents your phone handing back to the legitimate network, which is a common catcher configuration.',
      })
    }

    // 7. Flapping between serving cells while parked.
    this._recentServing.push({ key: cell.key, ts: now, stationary })
    const cutoff = now - CELLULAR.flapWindowMs
    this._recentServing = this._recentServing.filter(r => r.ts >= cutoff)
    const distinctWhileStill = new Set(
      this._recentServing.filter(r => r.stationary).map(r => r.key)
    )
    if (distinctWhileStill.size >= CELLULAR.flapThreshold) {
      findings.push({
        id: 'cell_flapping',
        severity: 'MEDIUM',
        title: `Handed between ${distinctWhileStill.size} different cells in ${Math.round(CELLULAR.flapWindowMs / 60000)} minutes without moving`,
        detail:
          'Repeated re-selection while stationary is abnormal. Each handover is another opportunity to ' +
          'make your handset transmit its identity.',
      })
    }

    // 8. A cell that appeared, served us briefly and vanished — reviewed
    //    against history rather than the current reading.
    for (const ephemeral of this._findEphemeral(now, cell.key)) {
      findings.push({
        id: 'ephemeral_cell',
        severity: 'HIGH',
        title: `Cell ${ephemeral.cellId} served you for ${Math.round((ephemeral.lastSeen - ephemeral.firstSeen) / 60000)} min and has not been seen since`,
        detail:
          'A cell that appears, takes traffic and disappears does not behave like fixed infrastructure. ' +
          'It behaves like equipment that was driven away.',
      })
    }

    this._record(cell, fix, ak)
    this._previous = { cell, fix, ts: now }

    let score = 0
    for (const f of findings) score += CHECKS[f.id] || 10
    score = Math.min(100, score)

    const level =
      score >= 60 ? 'CRITICAL' :
      score >= 38 ? 'HIGH' :
      score >= 20 ? 'MEDIUM' :
      score > 0 ? 'LOW' : 'NONE'

    const signature = findings.map(f => f.id).sort().join('|')
    const isNew = signature !== '' && signature !== this._lastSignature
    this._lastSignature = signature

    return {
      cell,
      findings,
      score,
      level,
      isNew,
      mature: this.mature,
      maturity: this.maturity,
      knownCells: Object.keys(data.cells).length,
      observations: data.observations,
    }
  }

  _findEphemeral(now, currentKey) {
    const data = this._store.data
    const out = []
    for (const [key, c] of Object.entries(data.cells)) {
      if (key === currentKey) continue
      if (c.reported) continue
      const lifetime = c.lastSeen - c.firstSeen
      const gone = now - c.lastSeen
      if (
        c.count >= 2 &&
        lifetime > 0 &&
        lifetime < CELLULAR.ephemeralCellMs &&
        gone > CELLULAR.ephemeralCellMs &&
        gone < 24 * 60 * 60 * 1000
      ) {
        c.reported = true
        out.push(c)
      }
    }
    if (out.length) this._store.markDirty()
    return out
  }

  _record(cell, fix, ak) {
    const data = this._store.data
    const now = cell.ts || Date.now()
    data.observations++

    let c = data.cells[cell.key]
    if (!c) {
      c = data.cells[cell.key] = {
        mcc: cell.mcc, mnc: cell.mnc, tac: cell.tac, cellId: cell.cellId,
        rat: cell.rat, firstSeen: now, lastSeen: now, count: 0,
        maxSignalDbm: null, minSignalDbm: null, locations: [],
      }
    }
    c.lastSeen = now
    c.count++
    c.rat = cell.rat || c.rat
    if (cell.tac) c.tac = cell.tac
    if (cell.signalDbm != null) {
      c.maxSignalDbm = c.maxSignalDbm == null ? cell.signalDbm : Math.max(c.maxSignalDbm, cell.signalDbm)
      c.minSignalDbm = c.minSignalDbm == null ? cell.signalDbm : Math.min(c.minSignalDbm, cell.signalDbm)
    }

    if (fix) {
      const existing = c.locations.find(l => haversine(l, fix) <= GEO.locationClusterM)
      if (existing) existing.count++
      else if (c.locations.length < 40) {
        c.locations.push({ lat: fix.lat, lon: fix.lon, count: 1 })
      }
    }

    if (fix) {
      let area = data.areas[ak]
      if (!area) {
        area = data.areas[ak] = {
          lat: fix.lat, lon: fix.lon, cells: {},
          bestRatRank: 0, bestRat: null, maxSignalDbm: null,
        }
      }
      area.cells[cell.key] = (area.cells[cell.key] || 0) + 1
      const rank = RAT_RANK[(cell.rat || '').toLowerCase()] || 0
      if (rank > area.bestRatRank) {
        area.bestRatRank = rank
        area.bestRat = cell.rat
      }
      if (cell.signalDbm != null) {
        area.maxSignalDbm = area.maxSignalDbm == null
          ? cell.signalDbm
          : Math.max(area.maxSignalDbm, cell.signalDbm)
      }
    }

    this._store.markDirty()
  }
}

module.exports = IMSICatcherDetector
module.exports.CHECKS = CHECKS
module.exports.BASELINE_MATURITY = BASELINE_MATURITY
