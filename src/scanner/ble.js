'use strict'

const EventEmitter = require('events')

// Minimum samples before we commit to a beacon interval estimate.
const INTERVAL_MIN_SAMPLES = 6
// Keep the last N timestamps per address to bound memory.
const INTERVAL_WINDOW = 20

class BLEScanner extends EventEmitter {
  constructor() {
    super()
    this._noble = null
    this._scanning = false
    // address → { times: number[], intervalMs: number|null }
    this._intervals = new Map()
  }

  start() {
    let noble
    try {
      noble = require('@abandonware/noble')
    } catch (e) {
      this.emit('error', new Error(
        'noble not installed. Run: npm install\n' +
        'On Linux also ensure: bluez and libbluetooth-dev are installed.'
      ))
      return
    }

    this._noble = noble

    noble.on('stateChange', (state) => {
      if (state === 'poweredOn') {
        // allowDuplicates = true so we get continuous RSSI updates
        noble.startScanning([], true)
        this._scanning = true
        this.emit('started')
      } else if (state === 'poweredOff') {
        this._scanning = false
        this.emit('stopped', 'bluetooth_off')
      } else if (state === 'unsupported') {
        this.emit('stopped', 'unsupported')
      } else if (state === 'unauthorized') {
        this.emit('stopped', 'unauthorized')
      } else {
        this.emit('stopped', state)
      }
    })

    noble.on('discover', (peripheral) => {
      this._trackInterval(peripheral)
      this.emit('device', peripheral)
    })

    noble.on('scanStop', () => {
      this._scanning = false
    })

    noble.on('warning', (msg) => {
      this.emit('warning', msg)
    })
  }

  _trackInterval(peripheral) {
    const id = peripheral.address || peripheral.uuid
    if (!id) return
    const now = Date.now()

    let entry = this._intervals.get(id)
    if (!entry) {
      entry = { times: [], intervalMs: null }
      this._intervals.set(id, entry)
    }

    entry.times.push(now)
    if (entry.times.length > INTERVAL_WINDOW) entry.times.shift()

    if (entry.times.length >= INTERVAL_MIN_SAMPLES) {
      const diffs = []
      for (let i = 1; i < entry.times.length; i++) {
        diffs.push(entry.times[i] - entry.times[i - 1])
      }
      // Median gap — more robust to missed packets than mean.
      diffs.sort((a, b) => a - b)
      entry.intervalMs = diffs[Math.floor(diffs.length / 2)]
    }

    // Attach to peripheral so analyzer.js can read it without extra state.
    peripheral._beaconIntervalMs = entry.intervalMs
  }

  get isScanning() {
    return this._scanning
  }

  stop() {
    if (this._noble && this._scanning) {
      try {
        this._noble.stopScanning()
      } catch (_) {}
      this._scanning = false
    }
  }
}

module.exports = BLEScanner
