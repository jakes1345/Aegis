#!/usr/bin/env node
'use strict'

const BLEScanner = require('./scanner/ble')
const WiFiScanner = require('./scanner/wifi')
const DeviceAnalyzer = require('./detectors/analyzer')
const WiFiAnalyzer = require('./detectors/wifi-analyzer')
const PersistenceTracker = require('./analysis/persistence')
const Dashboard = require('./ui/dashboard')

const bleScanner = new BLEScanner()
const wifiScanner = new WiFiScanner()
const bleAnalyzer = new DeviceAnalyzer()
const wifiAnalyzer = new WiFiAnalyzer()
const persistence = new PersistenceTracker()
const dashboard = new Dashboard()

dashboard.render()
dashboard.log('Track Detect starting up...')
dashboard.log('Initializing BLE + WiFi scanners...')
dashboard.log('{gray-fg}Linux: sudo required for BLE. WiFi uses nmcli (no sudo needed).{/}')
dashboard.log('{gray-fg}Press {bold}I{/} for full detection guide incl. GSM/satellite methods{/}')

// Purge stale records every 30 minutes
setInterval(() => persistence.clearOld(30 * 60 * 1000), 30 * 60 * 1000)

// ── BLE ──────────────────────────────────────────────────────────────────────

bleScanner.on('started', () => {
  dashboard.log('{green-fg}◉ BLE scanner active — AirTag | Tile | SmartTag | Chipolo{/}')
})

bleScanner.on('stopped', (reason) => {
  const msgs = {
    bluetooth_off: 'Bluetooth is off. Enable Bluetooth and restart.',
    unsupported: 'No Bluetooth adapter found. Attach a BT dongle and retry.',
    unauthorized: 'BLE permission denied. On Linux run: sudo node src/main.js',
    resetting: 'Bluetooth adapter is resetting...',
  }
  dashboard.log(`{red-fg}BLE stopped: ${msgs[reason] || reason}{/}`, 'error')
})

bleScanner.on('warning', (msg) => {
  dashboard.log(`{yellow-fg}BLE warning: ${msg}{/}`, 'warn')
})

bleScanner.on('error', (err) => {
  dashboard.log(`{red-fg}BLE error: ${err.message}{/}`, 'error')
  if (err.message.includes('noble not installed')) {
    dashboard.log('{yellow-fg}Run: npm install{/}', 'warn')
  }
})

bleScanner.on('device', (peripheral) => {
  try {
    const analyzed = bleAnalyzer.analyze(peripheral)
    if (analyzed.benign) return

    const tracked = persistence.update(analyzed)
    dashboard.updateDevice(tracked)

    if (tracked.newAlert) dashboard.addAlert(tracked)
  } catch (_) {}
})

// ── WiFi ─────────────────────────────────────────────────────────────────────

wifiScanner.on('started', (tool) => {
  dashboard.log(`{green-fg}◉ WiFi scanner active via ${tool} — OBD trackers | GPS hotspots{/}`)
})

wifiScanner.on('unsupported', (reason) => {
  dashboard.log(`{gray-fg}WiFi scan unavailable: ${reason}{/}`)
})

wifiScanner.on('error', (err) => {
  dashboard.log(`{yellow-fg}WiFi scan error: ${err.message}{/}`, 'warn')
})

wifiScanner.on('network', (network) => {
  try {
    const analyzed = wifiAnalyzer.analyze(network)
    if (analyzed.benign) return

    const tracked = persistence.update(analyzed)
    dashboard.updateDevice(tracked)

    if (tracked.newAlert) dashboard.addAlert(tracked)
  } catch (_) {}
})

// ── Start ─────────────────────────────────────────────────────────────────────

bleScanner.start()
wifiScanner.start()

function shutdown() {
  bleScanner.stop()
  wifiScanner.stop()
  dashboard.destroy()
  process.exit(0)
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
