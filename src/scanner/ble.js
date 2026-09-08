'use strict'

const EventEmitter = require('events')

class BLEScanner extends EventEmitter {
  constructor() {
    super()
    this._noble = null
    this._scanning = false
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
      this.emit('device', peripheral)
    })

    noble.on('scanStop', () => {
      this._scanning = false
    })

    noble.on('warning', (msg) => {
      this.emit('warning', msg)
    })
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
