'use strict'

const { spawn } = require('child_process')
const readline = require('readline')
const EventEmitter = require('events')

const { SDR, SDR_PROFILES } = require('../config')

// Wraps rtl_power / hackrf_sweep and turns their CSV into aligned sweeps.
//
// Both tools emit one row per frequency chunk:
//   date, time, hzLow, hzHigh, hzStep, samples, dB, dB, dB, ...
// A full pass over the requested range is many rows. A pass is complete when
// the frequency wraps back to a chunk we already have, which works for both
// tools regardless of how they timestamp rows.
class SDRScanner extends EventEmitter {
  constructor(opts = {}) {
    super()
    this._profileId = opts.profile || 'rtlsdr'
    this._profile = SDR_PROFILES[this._profileId] || SDR_PROFILES.rtlsdr
    this._deviceIndex = opts.deviceIndex != null ? opts.deviceIndex : 0
    this._gain = opts.gain != null ? opts.gain : SDR.gain
    this._proc = null
    this._rl = null
    this._band = null
    this._mode = null
    this._stopping = false

    this._reset()
  }

  _reset() {
    this._acc = null
    this._accLows = new Set()
    this._accStart = 0
  }

  get running() {
    return this._proc != null
  }

  get band() {
    return this._band
  }

  get mode() {
    return this._mode
  }

  get profile() {
    return this._profile
  }

  // mode 'survey': cover the whole band, hopping. Finds emitters but misses
  // individual bursts in proportion to how wide the band is.
  // mode 'watch': park on one tuner-width window centred in the band. Every
  // burst inside that window is seen, which is what timing analysis needs.
  start(band, mode = 'survey') {
    this.stop()
    this._stopping = false
    this._band = band
    this._mode = mode
    this._reset()

    const range = this._rangeFor(band, mode)
    const binHz = mode === 'watch' ? SDR.watchBinHz : SDR.surveyBinHz
    const integration = mode === 'watch' ? SDR.watchIntegrationSec : SDR.surveyIntegrationSec

    const { cmd, args } = this._buildCommand(range, binHz, integration)

    let proc
    try {
      proc = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'] })
    } catch (err) {
      this.emit('unsupported', `${cmd} could not be started: ${err.message}`)
      return false
    }

    this._proc = proc

    proc.on('error', (err) => {
      this._proc = null
      if (err.code === 'ENOENT') {
        this.emit('unsupported',
          `${cmd} not found. Install it (rtl-sdr or hackrf tools) and make sure the dongle is plugged in.`)
      } else {
        this.emit('error', err)
      }
    })

    // Both tools narrate to stderr; only surface the parts that mean something.
    let stderrBuf = ''
    proc.stderr.on('data', (chunk) => {
      stderrBuf += chunk.toString()
      const lines = stderrBuf.split('\n')
      stderrBuf = lines.pop()
      for (const line of lines) {
        const l = line.trim()
        if (!l) continue
        if (/no supported devices found|usb_open error|failed to open/i.test(l)) {
          this.emit('unsupported', l)
        } else if (/error|resource busy|permission/i.test(l)) {
          this.emit('error', new Error(l))
        } else {
          this.emit('info', l)
        }
      }
    })

    this._rl = readline.createInterface({ input: proc.stdout })
    this._rl.on('line', (line) => this._onLine(line, range, binHz))

    proc.on('close', (code) => {
      this._proc = null
      if (this._rl) { this._rl.close(); this._rl = null }
      if (!this._stopping) this.emit('closed', code)
    })

    this.emit('started', {
      band,
      mode,
      range,
      binHz,
      integration,
      tool: this._profile.tool,
      dutyCycle: this._dutyCycle(range),
    })
    return true
  }

  // In survey mode the receiver only hears sweepBw of the band at any instant.
  // That ratio is the chance of catching any one burst, and the user deserves
  // to see it rather than assume full coverage.
  _dutyCycle(range) {
    const width = range.highHz - range.lowHz
    if (width <= 0) return 1
    return Math.min(1, this._profile.sweepBw / width)
  }

  _rangeFor(band, mode) {
    if (mode !== 'watch') {
      return { lowHz: band.minHz, highHz: band.maxHz }
    }
    const bw = this._profile.sweepBw
    const width = band.maxHz - band.minHz
    if (width <= bw) return { lowHz: band.minHz, highHz: band.maxHz }
    const centre = (band.minHz + band.maxHz) / 2
    return { lowHz: Math.round(centre - bw / 2), highHz: Math.round(centre + bw / 2) }
  }

  // Park watch mode on a specific frequency found during survey.
  watchAround(centreHz, band) {
    const bw = this._profile.sweepBw
    const synthetic = {
      id: `${band ? band.id : 'custom'}_watch`,
      label: `${(centreHz / 1e6).toFixed(3)} MHz watch`,
      minHz: Math.round(centreHz - bw / 2),
      maxHz: Math.round(centreHz + bw / 2),
      category: band ? band.category : 'custom',
      notes: band ? band.notes : '',
      parent: band ? band.id : null,
    }
    return this.start(synthetic, 'watch')
  }

  _buildCommand(range, binHz, integration) {
    if (this._profile.tool === 'hackrf_sweep') {
      const loMHz = Math.floor(range.lowHz / 1e6)
      const hiMHz = Math.ceil(range.highHz / 1e6)
      const args = ['-f', `${loMHz}:${hiMHz}`, '-w', String(Math.round(binHz))]
      if (this._gain !== 'auto') {
        args.push('-l', '24', '-g', String(this._gain))
      }
      return { cmd: 'hackrf_sweep', args }
    }

    const args = [
      '-f', `${Math.round(range.lowHz)}:${Math.round(range.highHz)}:${Math.round(binHz)}`,
      '-i', String(integration),
      '-d', String(this._deviceIndex),
    ]
    // rtl_power has no "auto" keyword — omitting -g is what selects AGC.
    if (this._gain !== 'auto') args.push('-g', String(this._gain))
    args.push('-')
    return { cmd: 'rtl_power', args }
  }

  _onLine(line, range, binHz) {
    if (!line || line[0] === '#') return
    const parts = line.split(',')
    if (parts.length < 7) return

    const lowHz = Number(parts[2])
    const stepHz = Number(parts[4])
    if (!isFinite(lowHz) || !isFinite(stepHz) || stepHz <= 0) return

    // Frequency wrapped back to a chunk already in this pass -> pass complete.
    if (this._accLows.has(lowHz)) this._flush(range, binHz)

    if (!this._acc) {
      const count = Math.max(1, Math.ceil((range.highHz - range.lowHz) / stepHz))
      this._acc = {
        startHz: range.lowHz,
        stepHz,
        bins: new Float32Array(count).fill(NaN),
        filled: 0,
      }
      this._accStart = Date.now()
    }

    const acc = this._acc
    const base = Math.round((lowHz - acc.startHz) / acc.stepHz)

    for (let i = 6; i < parts.length; i++) {
      const db = Number(parts[i])
      if (!isFinite(db)) continue
      const idx = base + (i - 6)
      if (idx < 0 || idx >= acc.bins.length) continue
      if (Number.isNaN(acc.bins[idx])) acc.filled++
      acc.bins[idx] = db
    }

    this._accLows.add(lowHz)
  }

  _flush(range, binHz) {
    const acc = this._acc
    if (!acc || acc.filled === 0) {
      this._reset()
      return
    }
    this.emit('sweep', {
      tsMs: this._accStart,
      durationMs: Date.now() - this._accStart,
      startHz: acc.startHz,
      stepHz: acc.stepHz,
      bins: acc.bins,
      filled: acc.filled,
      binHz,
      band: this._band,
      mode: this._mode,
      dutyCycle: this._dutyCycle(range),
    })
    this._reset()
  }

  stop() {
    if (!this._proc) return
    this._stopping = true
    try { this._proc.kill('SIGTERM') } catch (_) {}
    if (this._rl) { try { this._rl.close() } catch (_) {} this._rl = null }
    this._proc = null
    this._reset()
  }
}

module.exports = SDRScanner
