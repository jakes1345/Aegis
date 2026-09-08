#!/usr/bin/env node
'use strict'

const BLEScanner = require('./scanner/ble')
const DeviceAnalyzer = require('./detectors/analyzer')
const PersistenceTracker = require('./analysis/persistence')
const Dashboard = require('./ui/dashboard')

const scanner = new BLEScanner()
const analyzer = new DeviceAnalyzer()
const persistence = new PersistenceTracker()
const dashboard = new Dashboard()

dashboard.render()
dashboard.log('Track Detect starting up...')
dashboard.log('Initializing Bluetooth scanner. This may take a moment.')
dashboard.log('{gray-fg}Linux: requires sudo or CAP_NET_RAW capability{/}')
dashboard.log('{gray-fg}Press {bold}I{/} for full detection guide including GSM/satellite methods{/}')

// Purge stale device records every 30 minutes
setInterval(() => {
  persistence.clearOld(30 * 60 * 1000)
}, 30 * 60 * 1000)

scanner.on('started', () => {
  dashboard.setStatus('SCANNING', 'green')
  dashboard.log('{green-fg}Bluetooth scanner active{/}')
  dashboard.log('Monitoring for: AirTag | Tile | SmartTag | Chipolo | Unknown BLE')
})

scanner.on('stopped', (reason) => {
  const msgs = {
    bluetooth_off: 'Bluetooth is off. Enable Bluetooth and restart.',
    unsupported: 'No Bluetooth adapter found. Attach a BT dongle and retry.',
    unauthorized: 'Permission denied. On Linux run: sudo node src/main.js',
    resetting: 'Bluetooth adapter is resetting. Please wait...',
  }
  dashboard.setStatus('STOPPED', 'red')
  dashboard.log(`{red-fg}Scanner stopped: ${msgs[reason] || reason}{/}`, 'error')
})

scanner.on('warning', (msg) => {
  dashboard.log(`{yellow-fg}Warning: ${msg}{/}`, 'warn')
})

scanner.on('error', (err) => {
  dashboard.log(`{red-fg}Error: ${err.message}{/}`, 'error')
  if (err.message.includes('noble not installed')) {
    dashboard.log('{yellow-fg}Run: npm install{/}', 'warn')
  }
})

scanner.on('device', (peripheral) => {
  try {
    const analyzed = analyzer.analyze(peripheral)
    if (analyzed.benign) return

    const tracked = persistence.update(analyzed)
    dashboard.updateDevice(tracked)

    if (tracked.newAlert) {
      dashboard.addAlert(tracked)
    }
  } catch (_) {
    // ignore malformed advertisement packets
  }
})

scanner.start()

function shutdown() {
  scanner.stop()
  dashboard.destroy()
  process.exit(0)
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
