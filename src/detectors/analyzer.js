'use strict'

const { SIGNATURES, BENIGN } = require('./signatures')

// TX power reference at 1 meter (typical BLE device)
const TX_POWER_REF = -59

class DeviceAnalyzer {
  analyze(peripheral) {
    const adv = peripheral.advertisement || {}

    for (const sig of BENIGN) {
      if (sig.match(adv)) {
        return this._build(peripheral, null, true)
      }
    }

    for (const sig of SIGNATURES) {
      if (sig.match(adv)) {
        return this._build(peripheral, sig, false)
      }
    }

    return this._build(peripheral, null, false)
  }

  _build(peripheral, sig, benign) {
    const adv = peripheral.advertisement || {}
    const mfr = adv.manufacturerData

    return {
      id: peripheral.address || peripheral.uuid || String(Math.random()),
      address: peripheral.address || 'unknown',
      name: adv.localName || (sig ? sig.name : null) || 'Unknown Device',
      rssi: peripheral.rssi || 0,
      trackerType: sig ? sig.id : null,
      trackerInfo: sig || null,
      benign,
      threat: benign ? 'NONE' : (sig ? sig.threat : 'UNKNOWN'),
      threatScore: this._score(sig, benign, peripheral.rssi),
      distance: this._estimateDistance(peripheral.rssi),
      manufacturerHex: mfr ? mfr.toString('hex') : null,
      serviceUuids: adv.serviceUuids || [],
    }
  }

  _score(sig, benign, rssi) {
    if (benign) return 0
    if (!sig) return 10 // unknown device, low base

    const base = { CRITICAL: 88, HIGH: 68, MEDIUM: 44, LOW: 20 }
    let score = base[sig.threat] || 30

    // Strong signal = closer device = more likely attached to you
    if (rssi > -55) score = Math.min(100, score + 10)
    else if (rssi > -70) score = Math.min(100, score + 5)

    return score
  }

  _estimateDistance(rssi) {
    if (!rssi || rssi === 0) return 'unknown'
    const ratio = rssi / TX_POWER_REF
    if (ratio < 1.0) {
      const d = Math.pow(ratio, 10)
      return d < 0.1 ? '<0.1m' : `~${d.toFixed(1)}m`
    }
    const dist = 0.89976 * Math.pow(ratio, 7.7095) + 0.111
    if (dist < 1) return `~${(dist * 100).toFixed(0)}cm`
    if (dist > 30) return '>30m'
    return `~${dist.toFixed(1)}m`
  }
}

module.exports = DeviceAnalyzer
