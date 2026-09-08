'use strict'

const { SDR } = require('../config')

function median(values) {
  if (values.length === 0) return NaN
  const arr = Array.prototype.slice.call(values).sort((a, b) => a - b)
  const mid = arr.length >> 1
  return arr.length % 2 ? arr[mid] : (arr[mid - 1] + arr[mid]) / 2
}

// Turns a stream of sweeps into discrete signals.
//
// Two corrections matter, and skipping either produces constant false alarms:
//
//  1. Per-sweep baseline. AGC and thermal drift move the whole trace up and
//     down together. Subtracting each sweep's own median removes that without
//     touching a narrowband signal, which barely shifts the median at all.
//
//  2. Per-bin floor. Every band has permanent structure — a nearby base
//     station uplink, a pager, a harmonic. Comparing a bin to its own history
//     rather than to its neighbours means those never register as new.
class SpectrumAnalyzer {
  constructor(opts = {}) {
    this._thresholdDb = opts.detectThresholdDb != null ? opts.detectThresholdDb : SDR.detectThresholdDb
    this._window = opts.floorWindow || SDR.floorWindow
    this._signalMergeHz = opts.signalMergeHz || SDR.signalMergeHz
    this._maxBwHz = opts.maxTrackerBwHz || SDR.maxTrackerBwHz
    this._shape = null
    this._history = []
    this._sweeps = 0
  }

  get warmedUp() {
    return this._history.length >= Math.min(8, this._window)
  }

  get progress() {
    const need = Math.min(8, this._window)
    return Math.min(1, this._history.length / need)
  }

  get sweepCount() {
    return this._sweeps
  }

  reset() {
    this._shape = null
    this._history = []
    this._sweeps = 0
  }

  push(sweep) {
    const shape = `${sweep.startHz}:${sweep.stepHz}:${sweep.bins.length}`
    if (shape !== this._shape) {
      this._shape = shape
      this._history = []
    }
    this._sweeps++

    const bins = sweep.bins
    const n = bins.length

    const valid = []
    for (let i = 0; i < n; i++) {
      if (!Number.isNaN(bins[i])) valid.push(bins[i])
    }
    if (valid.length < 8) return { signals: [], warmedUp: this.warmedUp }

    const baseline = median(valid)

    const normalized = new Float32Array(n)
    for (let i = 0; i < n; i++) {
      normalized[i] = Number.isNaN(bins[i]) ? NaN : bins[i] - baseline
    }

    this._history.push(normalized)
    if (this._history.length > this._window) this._history.shift()

    if (!this.warmedUp) {
      return { signals: [], warmedUp: false, baseline, progress: this.progress }
    }

    const floor = this._floor(n)

    const excess = new Float32Array(n)
    for (let i = 0; i < n; i++) {
      excess[i] = Number.isNaN(normalized[i]) || Number.isNaN(floor[i])
        ? NaN
        : normalized[i] - floor[i]
    }

    const signals = this._group(excess, normalized, baseline, sweep)
    return { signals, warmedUp: true, baseline, noiseFloorDb: baseline }
  }

  _floor(n) {
    const floor = new Float32Array(n)
    const scratch = new Array(this._history.length)
    for (let i = 0; i < n; i++) {
      let k = 0
      for (let h = 0; h < this._history.length; h++) {
        const v = this._history[h][i]
        if (!Number.isNaN(v)) scratch[k++] = v
      }
      floor[i] = k === 0 ? NaN : median(scratch.slice(0, k))
    }
    return floor
  }

  _group(excess, normalized, baseline, sweep) {
    const stepHz = sweep.stepHz
    const gapBins = Math.max(1, Math.round(this._signalMergeHz / stepHz))
    const signals = []

    let start = -1
    let gap = 0

    const close = (endIdx) => {
      if (start < 0) return
      const lo = start
      const hi = endIdx
      let peakIdx = lo
      let peakExcess = -Infinity
      let weightSum = 0
      let freqWeighted = 0

      for (let i = lo; i <= hi; i++) {
        const e = excess[i]
        if (Number.isNaN(e)) continue
        if (e > peakExcess) { peakExcess = e; peakIdx = i }
        if (e > 0) {
          // Weight by linear power so the centroid is not dragged by skirts.
          const w = Math.pow(10, e / 10)
          weightSum += w
          freqWeighted += w * (sweep.startHz + (i + 0.5) * stepHz)
        }
      }

      const bandwidthHz = (hi - lo + 1) * stepHz
      if (weightSum > 0 && bandwidthHz <= this._maxBwHz) {
        signals.push({
          centerHz: freqWeighted / weightSum,
          peakHz: sweep.startHz + (peakIdx + 0.5) * stepHz,
          lowHz: sweep.startHz + lo * stepHz,
          highHz: sweep.startHz + (hi + 1) * stepHz,
          bandwidthHz,
          excessDb: peakExcess,
          absoluteDb: normalized[peakIdx] + baseline,
          bins: hi - lo + 1,
          tsMs: sweep.tsMs,
        })
      }
      start = -1
      gap = 0
    }

    for (let i = 0; i < excess.length; i++) {
      const hot = !Number.isNaN(excess[i]) && excess[i] >= this._thresholdDb
      if (hot) {
        if (start < 0) start = i
        gap = 0
      } else if (start >= 0) {
        gap++
        if (gap > gapBins) close(i - gap)
      }
    }
    if (start >= 0) close(excess.length - 1)

    return signals
  }
}

module.exports = { SpectrumAnalyzer, median }
