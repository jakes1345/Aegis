#!/usr/bin/env node
'use strict'

const BLEScanner = require('./scanner/ble')
const WiFiScanner = require('./scanner/wifi')
const SDRScanner = require('./scanner/sdr')
const { GPSSource } = require('./scanner/gps')
const CellularScanner = require('./scanner/cellular')

const DeviceAnalyzer = require('./detectors/analyzer')
const WiFiAnalyzer = require('./detectors/wifi-analyzer')
const IMSICatcherDetector = require('./detectors/imsi-catcher')

const PersistenceTracker = require('./analysis/persistence')
const RFTracker = require('./analysis/rf-tracker')
const { SpectrumAnalyzer } = require('./analysis/spectrum')
const { IdentityResolver } = require('./analysis/identity')
const { LocationTrack } = require('./analysis/geo')
const { describePeriod } = require('./analysis/periodicity')

const Dashboard = require('./ui/dashboard')
const { Store } = require('./store')
const { SDR_PROFILES, PRESETS, BANDS, bandById, reachable } = require('./config')

// How long the receiver spends looking wide before committing to one
// frequency. Survey finds candidates; only watch mode can time them.
const SURVEY_DWELL_MS = 3 * 60 * 1000
const WATCH_DWELL_MS = 8 * 60 * 1000

function parseArgs(argv) {
  const opts = {
    profile: 'rtlsdr',
    preset: 'vehicle',
    gain: 'auto',
    band: null,
    ble: true,
    wifi: true,
    sdr: true,
    cell: true,
    gps: true,
    gpsDevice: null,
    atDevice: null,
  }
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i]
    const next = () => argv[++i]
    switch (a) {
      case '--profile': opts.profile = next(); break
      case '--preset': opts.preset = next(); break
      case '--gain': opts.gain = next(); break
      case '--band': opts.band = next(); break
      case '--gps-device': opts.gpsDevice = next(); break
      case '--at-device': opts.atDevice = next(); break
      case '--no-ble': opts.ble = false; break
      case '--no-wifi': opts.wifi = false; break
      case '--no-sdr': opts.sdr = false; break
      case '--no-cell': opts.cell = false; break
      case '--no-gps': opts.gps = false; break
      case '-h':
      case '--help': opts.help = true; break
      default: break
    }
  }
  return opts
}

function printHelp() {
  const profiles = Object.keys(SDR_PROFILES).join(', ')
  const presets = Object.entries(PRESETS).map(([k, v]) => `    ${k.padEnd(10)} ${v.label}`).join('\n')
  const bands = BANDS.map(b =>
    `    ${b.id.padEnd(16)} ${(b.minHz / 1e6).toFixed(1)}-${(b.maxHz / 1e6).toFixed(1)} MHz  ${b.label}`
  ).join('\n')

  process.stdout.write(`track-detect — detect whether something is tracking you

Usage: track-detect [options]

Options:
  --profile <id>     SDR hardware: ${profiles} (default rtlsdr)
  --preset <id>      What to sweep for (default vehicle)
  --band <id>        Lock the SDR to one band in watch mode
  --gain <n|auto>    SDR gain (default auto)
  --gps-device <p>   NMEA serial device, e.g. /dev/ttyACM0
  --at-device <p>    Modem AT port, e.g. /dev/ttyUSB2
  --no-ble --no-wifi --no-sdr --no-cell --no-gps
                     Disable a subsystem

Presets:
${presets}

Bands:
${bands}

Requirements:
  BLE      root on Linux (sudo), or macOS Bluetooth permission
  WiFi     nmcli (Linux) or airport (macOS) — no root needed
  RF       rtl_power (rtl-sdr) or hackrf_sweep, plus the dongle
  Cellular mmcli (ModemManager), or adb with an Android phone
  GPS      gpsd, or a modem with GNSS, or an NMEA serial device

Without a position source nothing is ever reported as FOLLOWING,
only as PERSISTENT — the tool will not claim what it cannot show.
`)
}

const opts = parseArgs(process.argv)
if (opts.help) {
  printHelp()
  process.exit(0)
}

const dashboard = new Dashboard()
const locationTrack = new LocationTrack()
const persistence = new PersistenceTracker()
const identities = new IdentityResolver()
const rfTracker = new RFTracker()
const spectrum = new SpectrumAnalyzer()

const bleAnalyzer = new DeviceAnalyzer()
const wifiAnalyzer = new WiFiAnalyzer()

const cellStore = new Store('cell-baseline.json')
const imsiDetector = new IMSICatcherDetector(cellStore)

dashboard.render()
dashboard.log('{bold}Track Detect{/} starting')
dashboard.log('{gray-fg}Press I for what this can and cannot detect{/}')

function context() {
  return {
    fix: locationTrack.current(),
    locationTrack,
    isMoving: locationTrack.isMoving(),
  }
}

// ── Position ─────────────────────────────────────────────────────────────────

if (opts.gps) {
  const gps = new GPSSource({ nmeaDevice: opts.gpsDevice })

  gps.on('started', (source) => {
    dashboard.setSubsystem('GPS', 'ok', source)
    dashboard.log(`{green-fg}Position source: ${source}{/}`)
  })

  gps.on('fix', (fix) => {
    locationTrack.update(fix)
    dashboard.setPosition(fix, locationTrack.isMoving())
  })

  gps.on('unavailable', (reason) => {
    dashboard.setSubsystem('GPS', 'warn', 'none')
    dashboard.log(`{yellow-fg}${reason}{/}`, 'warn')
  })

  gps.on('warning', (msg) => dashboard.log(`{yellow-fg}GPS: ${msg}{/}`, 'warn'))

  gps.start()
  process.on('exit', () => gps.stop())
} else {
  dashboard.setSubsystem('GPS', 'off', 'disabled')
}

// ── Bluetooth LE ─────────────────────────────────────────────────────────────

if (opts.ble) {
  const bleScanner = new BLEScanner()

  bleScanner.on('started', () => {
    dashboard.setSubsystem('BLE', 'ok', 'scanning')
    dashboard.log('{green-fg}BLE scanning — AirTag, Tile, SmartTag, Chipolo, Pebblebee{/}')
  })

  bleScanner.on('stopped', (reason) => {
    const msgs = {
      bluetooth_off: 'Bluetooth is off — enable it and restart',
      unsupported: 'No Bluetooth adapter found',
      unauthorized: 'BLE permission denied — on Linux run with sudo',
      resetting: 'Bluetooth adapter resetting',
    }
    dashboard.setSubsystem('BLE', 'error', reason)
    dashboard.log(`{red-fg}BLE stopped: ${msgs[reason] || reason}{/}`, 'error')
  })

  bleScanner.on('warning', (msg) => dashboard.log(`{yellow-fg}BLE: ${msg}{/}`, 'warn'))

  bleScanner.on('error', (err) => {
    dashboard.setSubsystem('BLE', 'error', err.message)
    dashboard.log(`{red-fg}BLE error: ${err.message}{/}`, 'error')
    if (err.message.includes('noble not installed')) {
      dashboard.log('{yellow-fg}Run: npm install{/}', 'warn')
    }
  })

  bleScanner.on('device', (peripheral) => {
    try {
      const analyzed = bleAnalyzer.analyze(peripheral)
      if (analyzed.benign) return

      // Resolve the rotating address to a stable identity before persistence,
      // or a tracker that changes MAC never accumulates any history.
      const identity = identities.resolve(analyzed)
      if (identity.rotated) {
        dashboard.log(
          `{yellow-fg}Address rotation: ${identity.previousAddress} → ${analyzed.address} (same device){/}`,
          'warn'
        )
      }

      const tracked = persistence.update(analyzed, { ...context(), identity })
      dashboard.updateDevice(tracked)
      if (tracked.newAlert) dashboard.addAlert(tracked)
    } catch (_) {}
  })

  bleScanner.start()
  process.on('exit', () => bleScanner.stop())
} else {
  dashboard.setSubsystem('BLE', 'off', 'disabled')
}

// ── WiFi ─────────────────────────────────────────────────────────────────────

if (opts.wifi) {
  const wifiScanner = new WiFiScanner()

  wifiScanner.on('started', (tool) => {
    dashboard.setSubsystem('WiFi', 'ok', tool)
    dashboard.log(`{green-fg}WiFi scanning via ${tool} — OBD dongles, GPS hotspots{/}`)
  })

  wifiScanner.on('unsupported', (reason) => {
    dashboard.setSubsystem('WiFi', 'warn', 'unavailable')
    dashboard.log(`{gray-fg}WiFi scan unavailable: ${reason}{/}`)
  })

  wifiScanner.on('error', (err) => dashboard.log(`{yellow-fg}WiFi: ${err.message}{/}`, 'warn'))

  wifiScanner.on('network', (network) => {
    try {
      const analyzed = wifiAnalyzer.analyze(network)
      if (analyzed.benign) return
      const tracked = persistence.update(analyzed, context())
      dashboard.updateDevice(tracked)
      if (tracked.newAlert) dashboard.addAlert(tracked)
    } catch (_) {}
  })

  wifiScanner.start()
  process.on('exit', () => wifiScanner.stop())
} else {
  dashboard.setSubsystem('WiFi', 'off', 'disabled')
}

// ── Cellular / IMSI catcher ──────────────────────────────────────────────────

if (opts.cell) {
  const cellScanner = new CellularScanner({ atDevice: opts.atDevice })

  cellScanner.on('started', (source) => {
    dashboard.setSubsystem('Cell', 'ok', source)
    dashboard.log(`{green-fg}Cellular monitoring via ${source}{/}`)
    dashboard.log(`{gray-fg}Baseline: ${imsiDetector.baseline.observations} readings, ${Object.keys(imsiDetector.baseline.cells).length} known cells{/}`)
  })

  cellScanner.on('unavailable', (reason) => {
    dashboard.setSubsystem('Cell', 'warn', 'no source')
    dashboard.log(`{yellow-fg}${reason}{/}`, 'warn')
  })

  cellScanner.on('cell', (cell) => {
    try {
      const result = imsiDetector.observe(cell, locationTrack.current(), locationTrack)
      dashboard.updateCellStatus(result)

      if (result.isNew && result.findings.length > 0) {
        for (const f of result.findings) {
          dashboard.addRawAlert(
            `CELL: ${f.title}`,
            f.severity === 'CRITICAL' || f.severity === 'HIGH'
          )
        }
      }
    } catch (err) {
      dashboard.log(`{red-fg}Cell analysis error: ${err.message}{/}`, 'error')
    }
  })

  cellScanner.start()
  process.on('exit', () => cellScanner.stop())
} else {
  dashboard.setSubsystem('Cell', 'off', 'disabled')
}

// ── RF sweep ─────────────────────────────────────────────────────────────────

let sdrScanner = null

if (opts.sdr) {
  const profile = SDR_PROFILES[opts.profile]
  if (!profile) {
    dashboard.log(`{red-fg}Unknown SDR profile "${opts.profile}"{/}`, 'error')
    dashboard.setSubsystem('SDR', 'error', 'bad profile')
  } else {
    sdrScanner = new SDRScanner({ profile: opts.profile, gain: opts.gain })

    const preset = PRESETS[opts.preset] || PRESETS.vehicle
    let plan = []

    if (opts.band) {
      const b = bandById(opts.band)
      if (!b) {
        dashboard.log(`{red-fg}Unknown band "${opts.band}"{/}`, 'error')
      } else if (!reachable(b, profile)) {
        dashboard.log(`{red-fg}${b.label} is outside the range of ${profile.name}{/}`, 'error')
      } else {
        plan = [b]
      }
    } else {
      plan = preset.survey.map(bandById).filter(Boolean)
    }

    const skipped = plan.filter(b => !reachable(b, profile))
    plan = plan.filter(b => reachable(b, profile))

    for (const b of skipped) {
      dashboard.log(
        `{yellow-fg}Skipping ${b.label} — needs a tuner reaching ${(b.maxHz / 1e6).toFixed(0)} MHz, ${profile.name} stops at ${(profile.maxHz / 1e6).toFixed(0)}{/}`,
        'warn'
      )
    }

    if (plan.length === 0) {
      dashboard.setSubsystem('SDR', 'error', 'no reachable bands')
      dashboard.log('{red-fg}No bands in this preset are reachable by the selected SDR{/}', 'error')
    } else {
      let planIndex = 0
      let phaseTimer = null
      const alertedLevel = new Map()

      const nextPhase = () => {
        if (opts.band) {
          // A locked band stays in watch mode: maximum timing fidelity, no
          // hopping, which is the right trade when the frequency is known.
          sdrScanner.start(plan[0], 'watch')
          return
        }

        const candidate = rfTracker.candidates(1)[0]
        const shouldWatch = candidate && candidate.threatScore >= 25 &&
          (!candidate.seenInModes.has('watch') || candidate.threatScore >= 60)

        if (shouldWatch) {
          dashboard.log(
            `{cyan-fg}Parking on ${(candidate.centerHz / 1e6).toFixed(3)} MHz for ${WATCH_DWELL_MS / 60000} min to measure its timing{/}`
          )
          sdrScanner.watchAround(candidate.centerHz, candidate.band)
          phaseTimer = setTimeout(nextPhase, WATCH_DWELL_MS)
        } else {
          const band = plan[planIndex % plan.length]
          planIndex++
          sdrScanner.start(band, 'survey')
          phaseTimer = setTimeout(nextPhase, SURVEY_DWELL_MS)
        }
        if (phaseTimer && phaseTimer.unref) phaseTimer.unref()
      }

      sdrScanner.on('started', (info) => {
        const pct = Math.round(info.dutyCycle * 100)
        dashboard.setSubsystem('SDR', 'ok', `${info.mode} ${info.band.id}`)
        if (info.mode === 'survey') {
          dashboard.log(
            `{green-fg}Sweeping ${info.band.label} ${(info.range.lowHz / 1e6).toFixed(1)}-${(info.range.highHz / 1e6).toFixed(1)} MHz{/} ` +
            `{gray-fg}(hopping — hears ${pct}% of the band at any instant, so short bursts are caught by chance){/}`
          )
        } else {
          dashboard.log(
            `{green-fg}Watching ${(info.range.lowHz / 1e6).toFixed(3)}-${(info.range.highHz / 1e6).toFixed(3)} MHz continuously{/} ` +
            '{gray-fg}(every transmission in this window is seen){/}'
          )
        }
      })

      sdrScanner.on('unsupported', (reason) => {
        dashboard.setSubsystem('SDR', 'warn', 'no device')
        dashboard.log(`{yellow-fg}RF sweep unavailable: ${reason}{/}`, 'warn')
        dashboard.log('{gray-fg}An RTL-SDR is the single biggest upgrade here — it is what finds a hidden GPS box.{/}')
        if (phaseTimer) clearTimeout(phaseTimer)
      })

      sdrScanner.on('error', (err) => {
        dashboard.log(`{red-fg}SDR: ${err.message}{/}`, 'error')
      })

      sdrScanner.on('closed', () => {
        dashboard.setSubsystem('SDR', 'warn', 'restarting')
      })

      sdrScanner.on('sweep', (sweep) => {
        try {
          const result = spectrum.push(sweep)
          if (!result.warmedUp) return

          if (result.signals.length === 0) return

          const ctx = {
            ...context(),
            band: sweep.band,
            mode: sweep.mode,
            stepHz: sweep.stepHz,
          }

          for (const emitter of rfTracker.ingest(result.signals, ctx)) {
            dashboard.updateEmitter(emitter)

            const previous = alertedLevel.get(emitter.id) || 'NONE'
            const rank = { NONE: 0, LOW: 1, MEDIUM: 2, HIGH: 3, CRITICAL: 4 }
            if ((rank[emitter.threat] || 0) > (rank[previous] || 0) &&
                (emitter.threat === 'HIGH' || emitter.threat === 'CRITICAL')) {
              alertedLevel.set(emitter.id, emitter.threat)
              const p = emitter.periodicity
              const timing = p && p.periodic ? `, transmitting every ${describePeriod(p.periodMs)}` : ''
              const moved = emitter.area.movedWithUs()
                ? `, and it has travelled ${Math.round(emitter.area.span())} m with you`
                : ''
              dashboard.addRawAlert(
                `RF: ${(emitter.centerHz / 1e6).toFixed(3)} MHz in ${emitter.band ? emitter.band.label : 'unknown band'}${timing}${moved}`,
                emitter.threat === 'CRITICAL'
              )
            }
          }
        } catch (err) {
          dashboard.log(`{red-fg}Sweep analysis error: ${err.message}{/}`, 'error')
        }
      })

      nextPhase()
      process.on('exit', () => {
        if (phaseTimer) clearTimeout(phaseTimer)
        sdrScanner.stop()
      })
    }
  }
} else {
  dashboard.setSubsystem('SDR', 'off', 'disabled')
}

// ── Housekeeping ─────────────────────────────────────────────────────────────

const cleanupTimer = setInterval(() => {
  persistence.clearOld(30 * 60 * 1000)
  identities.clearOld(60 * 60 * 1000)
  rfTracker.clearOld(60 * 60 * 1000)
}, 10 * 60 * 1000)
if (cleanupTimer.unref) cleanupTimer.unref()

function shutdown() {
  clearInterval(cleanupTimer)
  cellStore.close()
  dashboard.destroy()
  process.exit(0)
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
