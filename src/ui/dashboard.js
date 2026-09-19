'use strict'

const blessed = require('blessed')
const { describePeriod } = require('../analysis/periodicity')

const THREAT_COLORS = {
  CRITICAL: 'red',
  HIGH: 'red',
  MEDIUM: 'yellow',
  LOW: 'green',
  UNKNOWN: 'yellow',
  NONE: 'gray',
}

const TYPE_TAGS = {
  ble: '{cyan-fg}B{/}',
  wifi: '{magenta-fg}W{/}',
  rf: '{yellow-fg}R{/}',
  cell: '{green-fg}C{/}',
}

const DETECTION_GUIDE = `{bold}{cyan-fg}WHAT THIS TOOL CAN AND CANNOT SEE{/}

Consumer tags are the easy case and the least likely thing a serious
person uses. Read this section before trusting a quiet screen.

{bold}{green-fg}DETECTABLE — and this tool does it{/}
 • {bold}Consumer BLE tags{/} — AirTag, Tile, SmartTag, Chipolo, Pebblebee.
   They advertise constantly and identify themselves by design.
 • {bold}Trackers that create a WiFi hotspot{/} — most OBD-II dongles and
   many standalone GPS units expose an AP for configuration.
 • {bold}Any radio that transmits on a schedule and moves with you{/} —
   this is the one that catches the hardware that matters. A hidden
   GPS box has to phone home. When it does, it transmits in a cellular
   or satellite uplink band, on a timer, from inside your car.
 • {bold}Fake cell towers (IMSI catchers){/} — not by their signal, but by
   what they do to your modem: forced 2G downgrade, unfamiliar cell,
   tracking-area churn while parked, overpowering signal, no neighbours.

{bold}{red-fg}NOT DETECTABLE — by this or any similar tool{/}
 • {bold}Carrier-side location{/}. If someone has access via the operator,
   SS7/Diameter, or a legal request, nothing transmits near you and
   there is nothing to detect. Your phone behaves completely normally.
 • {bold}Being tracked "by satellite"{/} in the sense people usually mean.
   Satellites do not scan for you. What is real and what this tool does
   look for is a satellite {bold}uplink transmitter{/} on your vehicle
   sending your position up to Iridium/Globalstar/Inmarsat.
 • {bold}Stalkerware on your own phone{/}. That is a device-integrity
   problem — check installed apps, profiles and account access.
 • {bold}A fully passive tracker{/}. A logger that records position and is
   retrieved later never transmits. Only a physical search finds it.

{bold}{yellow-fg}THE HONEST LIMIT ON "FOLLOWING"{/}
Persistence over time proves nothing on its own — your neighbour's
tracker is also present for hours. The claim that something is
{bold}following{/} you is only made when the same emitter is heard from two
places at least 300 m apart. That requires a position source: gpsd
with a USB GPS, or a cellular modem with GNSS. Without one, devices
are reported as PERSISTENT and never as FOLLOWING.

{bold}{cyan-fg}RF SWEEP — how to actually find a hidden GPS tracker{/}
Requires an RTL-SDR (about 30 USD) or a HackRF.

 {bold}Uplink, not downlink.{/} The tool sweeps the bands a device
 {bold}transmits{/} on. Downlink is useless — every phone nearby sees the
 same tower traffic.

 {bold}Survey then watch.{/} One receiver only hears ~2.4 MHz at a time.
 Survey hops across a whole band to find candidates but misses most
 individual bursts. Watch mode parks on one candidate and catches
 every transmission, which is what makes timing analysis possible.
 The tool does survey first, then parks on the best candidate.

 {bold}Bands swept:{/}
  • 698-716 / 777-787 MHz — LTE B12/B13/B17 uplink (US low band)
  • 824-915 MHz — GSM850, LTE B5/B20/B8 uplink. Highest yield.
  • 1710-1785 MHz — LTE B3 uplink (EU/Asia)
  • 1850-1915 MHz — PCS uplink (needs HackRF; beyond a stock RTL-SDR)
  • 1610-1626.5 MHz — Globalstar and Iridium uplink (SPOT, inReach)
  • 1626.5-1660.5 MHz — Inmarsat/Thuraya uplink
  • 148-150.05 MHz — ORBCOMM uplink (fleet/trailer asset trackers)
  • 433 / 868 / 915 MHz ISM — cheap beacons and LoRa tags

 {bold}What marks it as a tracker:{/} a narrowband burst, in an uplink
 band, repeating on a fixed interval (30s / 60s / 5min are stock
 firmware defaults), that is still there after you have driven away.

{bold}{cyan-fg}IMSI CATCHER DETECTION — how it works here{/}
Needs a source for your modem's serving cell:
  • ModemManager: {bold}mmcli{/} with a USB cellular modem
  • Android phone over USB with debugging on: {bold}adb{/}
  • A modem exposing an AT port (/dev/ttyUSB2 and similar)

No API key and no tower database. It learns what is normal at the
places you go and flags departures from it, so the first sessions in a
new area are quieter by design while the baseline fills in.

 {bold}Flags raised:{/}
  • Downgrade to 2G/3G where LTE is normal — 2G has no mutual auth,
    which is exactly why a catcher wants you on it
  • A cell ID whose tracking area code changed — a real cell's
    identity does not move
  • Tracking-area change while stationary — forced re-registration is
    how your IMSI gets pulled
  • Serving cell far stronger than anything recorded there before
  • Zero neighbouring cells — stops you handing back to the real network
  • A cell that served you briefly and was never seen again

{bold}{cyan-fg}PHYSICAL SEARCH — still the highest-yield method{/}
A tracker that is sleeping is invisible to RF. Look anyway:
 • Wheel wells and inner arches   • Under front/rear bumper covers
 • OBD-II port and behind dash    • Behind the licence plate
 • Under seats and trunk liner    • Engine bay near the firewall
 • Tow hitch and spare tyre well  • Roof lining on vans

Magnetic cases are the giveaway — a hard rectangular box on a flat
steel surface where nothing should be attached. Follow any wire that
does not belong; hardwired units splice into constant 12V.

{gray-fg}Press I to close | Tab to switch panels | Q to exit{/}`

class Dashboard {
  constructor() {
    this._screen = null
    this._devices = []
    this._alerts = []
    this._selectedIndex = 0
    this._showGuide = false
    this._initialized = false
    this._scanCount = 0
    this._startTime = Date.now()
    this._subsystems = {
      BLE: { state: 'off', detail: '' },
      WiFi: { state: 'off', detail: '' },
      SDR: { state: 'off', detail: '' },
      Cell: { state: 'off', detail: '' },
      GPS: { state: 'off', detail: '' },
    }
    this._position = null
  }

  render() {
    this._screen = blessed.screen({
      smartCSR: true,
      title: 'Track Detect — Tracking Detection',
      fullUnicode: true,
      dockBorders: true,
    })

    this._buildLayout()
    this._bindKeys()
    this._initialized = true
    this._screen.render()

    this._clockTimer = setInterval(() => {
      this._updateHeader()
      this._renderDeviceList()
      this._screen.render()
    }, 1000)
  }

  _buildLayout() {
    const s = this._screen

    this._header = blessed.box({
      parent: s,
      top: 0,
      left: 0,
      width: '100%',
      height: 4,
      style: { fg: 'white', bg: 'blue' },
      tags: true,
      content: '',
    })

    this._listBox = blessed.list({
      parent: s,
      label: ' {bold}DETECTIONS{/}  {cyan-fg}B{/}le {magenta-fg}W{/}ifi {yellow-fg}R{/}f {green-fg}C{/}ell ',
      tags: true,
      top: 4,
      left: 0,
      width: '45%',
      bottom: '38%',
      border: { type: 'line' },
      style: {
        fg: 'white',
        border: { fg: 'cyan' },
        label: { fg: 'cyan' },
        selected: { fg: 'black', bg: 'cyan', bold: true },
      },
      keys: true,
      vi: true,
      mouse: true,
      scrollable: true,
      alwaysScroll: true,
    })

    this._detailBox = blessed.box({
      parent: s,
      label: ' {bold}DETAILS{/} ',
      tags: true,
      top: 4,
      left: '45%',
      width: '55%',
      bottom: '38%',
      border: { type: 'line' },
      style: { fg: 'white', border: { fg: 'cyan' }, label: { fg: 'cyan' } },
      scrollable: true,
      alwaysScroll: true,
      keys: true,
      mouse: true,
    })

    this._alertBox = blessed.list({
      parent: s,
      label: ' {bold}{red-fg}ALERTS{/}{/} ',
      tags: true,
      top: '62%',
      left: 0,
      width: '45%',
      bottom: 0,
      border: { type: 'line' },
      style: { fg: 'white', border: { fg: 'red' }, label: { fg: 'red' } },
      scrollable: true,
      alwaysScroll: true,
      mouse: true,
    })

    this._logBox = blessed.log({
      parent: s,
      label: ' {bold}{gray-fg}LOG{/}{/} ',
      tags: true,
      top: '62%',
      left: '45%',
      width: '55%',
      bottom: 0,
      border: { type: 'line' },
      style: { fg: '#888888', border: { fg: '#444444' }, label: { fg: '#666666' } },
      scrollable: true,
      alwaysScroll: true,
      mouse: true,
    })

    this._guideBox = blessed.box({
      parent: s,
      label: ' {bold}DETECTION GUIDE{/} ',
      tags: true,
      top: 'center',
      left: 'center',
      width: '86%',
      height: '90%',
      border: { type: 'line' },
      style: {
        fg: 'white',
        bg: '#0a0a1a',
        border: { fg: 'cyan' },
        label: { fg: 'cyan' },
      },
      scrollable: true,
      alwaysScroll: true,
      keys: true,
      vi: true,
      mouse: true,
      hidden: true,
      content: DETECTION_GUIDE,
      padding: { left: 2, right: 2, top: 1, bottom: 1 },
    })

    this._listBox.on('select item', (item, idx) => {
      this._selectedIndex = idx
      this._renderDetails()
      this._screen.render()
    })

    this._updateHeader()
    this._renderDeviceList()
    this._renderDetails()
    this._listBox.focus()
  }

  _bindKeys() {
    const s = this._screen

    s.key(['q', 'Q', 'C-c'], () => {
      this.destroy()
      process.exit(0)
    })

    s.key(['tab'], () => {
      const panels = [this._listBox, this._detailBox, this._alertBox, this._logBox]
      const fi = panels.findIndex(p => p.focused)
      panels[(fi + 1) % panels.length].focus()
      s.render()
    })

    s.key(['i', 'I'], () => this._toggleGuide())
    this._guideBox.key(['i', 'I', 'escape'], () => this._toggleGuide(false))
  }

  _toggleGuide(force) {
    this._showGuide = force != null ? force : !this._showGuide
    if (this._showGuide) {
      this._guideBox.show()
      this._guideBox.focus()
    } else {
      this._guideBox.hide()
      this._listBox.focus()
    }
    this._screen.render()
  }

  setSubsystem(name, state, detail = '') {
    if (!this._subsystems[name]) this._subsystems[name] = {}
    this._subsystems[name].state = state
    this._subsystems[name].detail = detail
    if (this._initialized) {
      this._updateHeader()
      this._screen.render()
    }
  }

  setPosition(fix, moving) {
    this._position = fix ? { ...fix, moving } : null
  }

  _headerContent() {
    const uptime = this._formatUptime(Date.now() - this._startTime)
    const confirmed = this._devices.filter(d => d.following).length
    const persistent = this._devices.filter(d => d.persistent && !d.following).length

    let verdict
    if (confirmed > 0) {
      verdict = `{red-fg}{bold} ${confirmed} CONFIRMED FOLLOWING{/}{/}`
    } else if (persistent > 0) {
      verdict = `{yellow-fg} ${persistent} persistent, unconfirmed{/}`
    } else {
      verdict = '{green-fg} nothing confirmed{/}'
    }

    const colors = { ok: 'green', warn: 'yellow', error: 'red', off: '#666666' }
    const status = Object.entries(this._subsystems)
      .map(([name, s]) => `{${colors[s.state] || 'white'}-fg}●${name}{/}`)
      .join(' ')

    let pos = '{#666666-fg}no position source{/}'
    if (this._position) {
      const mv = this._position.moving ? '{green-fg}moving{/}' : '{yellow-fg}stationary{/}'
      pos = `{white-fg}${this._position.lat.toFixed(4)},${this._position.lon.toFixed(4)}{/} ${mv}`
    }

    return (
      ` {bold}TRACK DETECT{/}  ${uptime}  ${this._scanCount} obs ${verdict}\n` +
      ` ${status}   ${pos}\n` +
      ` {#aaccff-fg}[I] guide   [Tab] panels   [Q] quit{/}`
    )
  }

  _updateHeader() {
    if (this._header) this._header.setContent(this._headerContent())
  }

  setStatus(text, color = 'green') {
    if (this._initialized) this.log(`{${color}-fg}${text}{/}`)
  }

  updateDevice(device) {
    if (!this._initialized) return
    this._scanCount++

    const key = device.key || device.id
    const idx = this._devices.findIndex(d => (d.key || d.id) === key)
    if (idx >= 0) this._devices[idx] = device
    else this._devices.push(device)

    this._devices.sort((a, b) => {
      if (!!b.following !== !!a.following) return b.following ? 1 : -1
      if ((b.threatScore || 0) !== (a.threatScore || 0)) return (b.threatScore || 0) - (a.threatScore || 0)
      return (b.appearances || 0) - (a.appearances || 0)
    })

    this._renderDeviceList()
    this._renderDetails()
    this._updateHeader()
    this._screen.render()
  }

  // RF emitters share the list with radio devices; they are ranked by the same
  // threat score so the most suspicious thing is always at the top whatever
  // medium found it.
  updateEmitter(emitter) {
    this.updateDevice({
      key: emitter.id,
      id: emitter.id,
      scanType: 'rf',
      name: emitter.name,
      address: `${(emitter.centerHz / 1e6).toFixed(3)} MHz`,
      rssi: Math.round(emitter.peakExcessDb),
      threat: emitter.threat,
      threatScore: emitter.threatScore,
      benign: false,
      firstSeen: emitter.firstSeen,
      lastSeen: emitter.lastSeen,
      appearances: emitter.detections,
      following: emitter.classification === 'mobile_tracker' || emitter.classification === 'satellite_tracker',
      persistent: !!(emitter.periodicity && emitter.periodicity.periodic),
      displacementM: emitter.area.span(),
      emitter,
    })
  }

  updateCellStatus(result) {
    this.updateDevice({
      key: 'cellular-network',
      id: 'cellular-network',
      scanType: 'cell',
      name: result.level === 'NONE'
        ? `Serving cell ${result.cell.cellId}`
        : `Cell anomaly: ${result.findings.length} finding${result.findings.length > 1 ? 's' : ''}`,
      address: `${result.cell.mcc || '?'}-${result.cell.mnc || '?'} TAC ${result.cell.tac || '?'}`,
      rssi: result.cell.signalDbm || 0,
      threat: result.level,
      threatScore: result.score,
      // Always listed, even when clean — the serving cell is worth being able
      // to inspect, and a NONE threat sorts it to the bottom anyway.
      benign: false,
      firstSeen: this._startTime,
      lastSeen: Date.now(),
      appearances: result.observations,
      following: false,
      persistent: false,
      cellResult: result,
    })
  }

  addAlert(device) {
    if (!device.alertMessage) return
    const time = new Date().toLocaleTimeString()
    const critical = device.threat === 'CRITICAL' || device.following
    const icon = critical ? '{red-fg}{bold}[!!!]{/}{/}' : '{yellow-fg}[!! ]{/}'
    this._alerts.unshift(`${icon} {bold}${time}{/}  ${device.alertMessage}`)
    if (this._alerts.length > 200) this._alerts.pop()
    this._alertBox.setItems(this._alerts)
    this._alertBox.scrollTo(0)
    this._screen.render()
  }

  addRawAlert(message, critical = false) {
    const time = new Date().toLocaleTimeString()
    const icon = critical ? '{red-fg}{bold}[!!!]{/}{/}' : '{yellow-fg}[!! ]{/}'
    this._alerts.unshift(`${icon} {bold}${time}{/}  ${message}`)
    if (this._alerts.length > 200) this._alerts.pop()
    if (this._initialized) {
      this._alertBox.setItems(this._alerts)
      this._alertBox.scrollTo(0)
      this._screen.render()
    }
  }

  log(message, level = 'info') {
    if (!this._initialized || !this._logBox) return
    const colors = { info: '{#888888-fg}', error: '{red-fg}', warn: '{yellow-fg}' }
    const col = colors[level] || '{gray-fg}'
    const time = new Date().toLocaleTimeString()
    this._logBox.log(`${col}${time}{/}  ${message}`)
  }

  _renderDeviceList() {
    if (!this._listBox) return
    const visible = this._devices.filter(d => !d.benign)

    if (visible.length === 0) {
      this._listBox.setItems([
        '{gray-fg}  Nothing flagged yet.{/}',
        '{gray-fg}{/}',
        '{gray-fg}  Consumer tags show up in seconds.{/}',
        '{gray-fg}  A hidden GPS box only appears when it{/}',
        '{gray-fg}  transmits — give the RF sweep 20+ min,{/}',
        '{gray-fg}  and drive somewhere to prove movement.{/}',
        '{gray-fg}{/}',
        '{gray-fg}  Press I for what is and is not detectable.{/}',
      ])
      return
    }

    this._listBox.setItems(visible.map(d => this._formatRow(d)))
  }

  _formatRow(d) {
    const tc = THREAT_COLORS[d.threat] || 'white'
    const icons = {
      CRITICAL: '{red-fg}{bold}[!!!]{/}{/}',
      HIGH: '{red-fg}[!! ]{/}',
      MEDIUM: '{yellow-fg}[!  ]{/}',
      LOW: '{green-fg}[   ]{/}',
      UNKNOWN: '{yellow-fg}[?  ]{/}',
      NONE: '{gray-fg}[   ]{/}',
    }
    const icon = icons[d.threat] || '{white-fg}[   ]{/}'
    const tag = TYPE_TAGS[d.scanType] || TYPE_TAGS.ble
    const name = (d.name || 'Unknown').substring(0, 22).padEnd(22)
    const signal = d.scanType === 'rf'
      ? `+${String(d.rssi || 0).padStart(2)}dB`
      : `${String(d.rssi || 0).padStart(4)}dBm`

    let flag = ''
    if (d.following) flag = ' {red-fg}{bold}FOLLOWING{/}{/}'
    else if (d.persistent) flag = ' {yellow-fg}persistent{/}'

    return `${icon}${tag} {${tc}-fg}${name}{/} ${signal} ${this._ago(d.lastSeen)}${flag}`
  }

  _renderDetails() {
    if (!this._detailBox) return
    const visible = this._devices.filter(d => !d.benign)
    const device = visible[this._selectedIndex]

    if (!device) {
      this._detailBox.setContent(
        '\n\n  {gray-fg}Nothing selected.\n\n' +
        '  Press {bold}I{/} to read what this tool can and cannot detect —\n' +
        '  worth doing before you trust an empty screen.{/}'
      )
      return
    }

    if (device.scanType === 'rf') return this._renderRFDetails(device)
    if (device.scanType === 'cell') return this._renderCellDetails(device)
    return this._renderRadioDetails(device)
  }

  _renderRFDetails(device) {
    const e = device.emitter
    const tc = THREAT_COLORS[device.threat] || 'white'
    const p = e.periodicity
    const lines = [
      `  {bold}{cyan-fg}Frequency:{/}    {/}{bold}${(e.centerHz / 1e6).toFixed(4)} MHz{/}`,
      `  {bold}{cyan-fg}Bandwidth:{/}    {/}${Math.round(e.bandwidthHz / 1e3)} kHz`,
      `  {bold}{cyan-fg}Band:{/}         {/}${e.band ? e.band.label : 'unknown'}`,
      `  {bold}{cyan-fg}Peak above{/}    {/}`,
      `  {bold}{cyan-fg}noise floor:{/}  {/}+${e.peakExcessDb.toFixed(1)} dB`,
      '',
      `  {bold}{cyan-fg}Assessment:{/}   {/}{${tc}-fg}{bold}${device.threat}{/}{/}  ${this._threatBar(device.threatScore, device.threat)} ${device.threatScore}/100`,
      `  {bold}{cyan-fg}Classified:{/}   {/}${e.classification.replace(/_/g, ' ')}`,
      '',
      `  {bold}{cyan-fg}Transmissions:{/} {/}${e.eventStarts.length} (${e.detections} sweep hits)`,
    ]

    if (p && p.periodic) {
      lines.push(`  {bold}{cyan-fg}Interval:{/}     {/}{red-fg}{bold}every ${describePeriod(p.periodMs)}{/}{/}`)
      lines.push(`  {bold}{cyan-fg}Regularity:{/}   {/}${p.matched}/${p.total} intervals, jitter ${Math.round(p.jitterMs / 1000)}s`)
    } else if (p && p.reason === 'insufficient_events') {
      lines.push(`  {bold}{cyan-fg}Interval:{/}     {/}{gray-fg}need ${4 - (p.total || 0)} more transmissions{/}`)
    } else {
      lines.push(`  {bold}{cyan-fg}Interval:{/}     {/}{gray-fg}irregular — not beaconing{/}`)
    }

    lines.push(`  {bold}{cyan-fg}Heard from:{/}   {/}${e.area.count} location${e.area.count === 1 ? '' : 's'}, up to ${Math.round(e.area.span())} m apart`)
    lines.push('')

    if (e.reasons.length) {
      lines.push('  {bold}{cyan-fg}Why it is scored this way:{/}')
      for (const r of e.reasons) lines.push(`  {gray-fg}• ${this._wrap(r, 4)}{/}`)
      lines.push('')
    }

    if (device.following) {
      lines.push('  {red-fg}{bold}This transmitter travelled with you.{/}{/}')
      lines.push('  {red-fg}Search the vehicle. A magnetic case on flat steel,{/}')
      lines.push('  {red-fg}or a hardwired box spliced into constant 12V.{/}')
      lines.push(`  {red-fg}Tune ${(e.centerHz / 1e6).toFixed(3)} MHz on a handheld SDR and{/}`)
      lines.push('  {red-fg}walk the car to find where it gets loudest.{/}')
    } else if (e.area.count <= 1) {
      lines.push('  {yellow-fg}Only heard from one place so far. Drive at least{/}')
      lines.push('  {yellow-fg}300 m and keep scanning — if it is still there,{/}')
      lines.push('  {yellow-fg}it is travelling with you rather than nearby.{/}')
    }

    this._detailBox.setContent(lines.join('\n'))
  }

  _renderCellDetails(device) {
    const r = device.cellResult
    const c = r.cell
    const tc = THREAT_COLORS[device.threat] || 'white'
    const lines = [
      `  {bold}{cyan-fg}Serving cell:{/} {/}{bold}${c.cellId}{/}`,
      `  {bold}{cyan-fg}Network:{/}      {/}${c.mcc || '?'}-${c.mnc || '?'}   TAC/LAC ${c.tac || '?'}`,
      `  {bold}{cyan-fg}Technology:{/}   {/}${(c.rat || 'unknown').toUpperCase()}`,
      `  {bold}{cyan-fg}Signal:{/}       {/}${c.signalDbm != null ? `${c.signalDbm} dBm` : 'not reported'}`,
      `  {bold}{cyan-fg}Neighbours:{/}   {/}${c.neighbors != null ? c.neighbors : 'not reported by this source'}`,
      `  {bold}{cyan-fg}Source:{/}       {/}${c.source}`,
      '',
      `  {bold}{cyan-fg}Assessment:{/}   {/}{${tc}-fg}{bold}${r.level}{/}{/}  ${this._threatBar(r.score, r.level)} ${r.score}/100`,
      '',
    ]

    if (!r.mature) {
      lines.push(`  {yellow-fg}Baseline ${Math.round(r.maturity * 100)}% built (${r.observations} readings,`)
      lines.push(`  ${r.knownCells} cells known). Familiarity checks stay off{/}`)
      lines.push('  {yellow-fg}until there is enough history to mean something.{/}')
      lines.push('')
    }

    if (r.findings.length === 0) {
      lines.push('  {green-fg}Nothing anomalous. The serving cell matches what{/}')
      lines.push('  {green-fg}has been recorded at this location before.{/}')
    } else {
      lines.push('  {bold}{red-fg}Findings:{/}{/}')
      for (const f of r.findings) {
        const fc = THREAT_COLORS[f.severity] || 'yellow'
        lines.push('')
        lines.push(`  {${fc}-fg}{bold}${f.title}{/}{/}`)
        lines.push(`  {gray-fg}${this._wrap(f.detail, 4)}{/}`)
      }
    }

    this._detailBox.setContent(lines.join('\n'))
  }

  _renderRadioDetails(device) {
    const t = device.trackerInfo
    const tc = THREAT_COLORS[device.threat] || 'white'
    const mins = Math.floor((device.followDuration || 0) / 60000)
    const secs = Math.floor(((device.followDuration || 0) % 60000) / 1000)
    const stable = device.rssiHistory && device.rssiHistory.length >= 5
      ? (this._isStable(device.rssiHistory) ? '{red-fg}very stable — moves as one object with you{/}' : 'varies normally')
      : 'not enough data'

    const scanLabel = device.scanType === 'wifi' ? '{magenta-fg}WiFi{/}' : '{cyan-fg}Bluetooth LE{/}'
    const unknownType = device.scanType === 'wifi' ? 'Unknown WiFi device' : 'Unknown BLE device'

    const lines = [
      `  {bold}{cyan-fg}Name:{/}         {/}{bold}${device.name || 'Unknown'}{/}`,
      `  {bold}{cyan-fg}Medium:{/}       {/}${scanLabel}`,
      `  {bold}{cyan-fg}Address:{/}      {/}{gray-fg}${device.address || 'N/A'}{/}`,
      `  {bold}{cyan-fg}Identified as:{/}{/}{${tc}-fg}${t ? t.name : unknownType}{/}`,
      `  {bold}{cyan-fg}Brand:{/}        {/}${t ? t.brand : 'Unknown'}`,
      '',
      `  {bold}{cyan-fg}Assessment:{/}   {/}{${tc}-fg}{bold}${device.threat}{/}{/}  ${this._threatBar(device.threatScore || 0, device.threat)} ${device.threatScore || 0}/100`,
      `  {bold}{cyan-fg}Signal:{/}       {/}${device.rssi || 0} dBm (approx ${device.distance || 'unknown'})`,
      `  {bold}{cyan-fg}Signal shape:{/} {/}${stable}`,
    ]

    if (device.scanType === 'wifi') {
      lines.push(`  {bold}{cyan-fg}Channel:{/}      {/}${device.channel || 'N/A'}`)
      lines.push(`  {bold}{cyan-fg}Security:{/}     {/}${device.security || 'Unknown'}`)
    }

    lines.push('')
    lines.push(`  {bold}{cyan-fg}First seen:{/}   {/}${this._ago(device.firstSeen)}`)
    lines.push(`  {bold}{cyan-fg}Seen:{/}         {/}{bold}${device.appearances || 1}x{/} over ${mins}m ${secs}s`)

    if (device.addressRotations > 0) {
      lines.push(`  {bold}{cyan-fg}MAC rotations:{/}{/}{red-fg}${device.addressRotations} — changing address while staying with you{/}`)
    }

    lines.push(`  {bold}{cyan-fg}Travelled:{/}    {/}${Math.round(device.displacementM || 0)} m from first sighting`)
    lines.push('')

    if (device.following) {
      lines.push('  {red-fg}{bold}CONFIRMED FOLLOWING{/}{/}')
      lines.push(`  {red-fg}Present at points ${Math.round(device.displacementM)} m apart. This is on{/}`)
      lines.push('  {red-fg}you or your vehicle — it is not a fixed neighbour.{/}')
      lines.push('')
      lines.push('  {red-fg}Search: wheel wells, bumper covers, OBD-II port,{/}')
      lines.push('  {red-fg}under seats, trunk liner, behind the plate.{/}')
      if (device.trackerType === 'airtag') {
        lines.push('  {red-fg}iOS: Find My > Items > Identify Found Item.{/}')
        lines.push('  {red-fg}Android: Google "Unknown tracker alerts" scan.{/}')
      }
    } else if (device.persistent) {
      if (device.followConfidence === 'no_position_source') {
        lines.push('  {yellow-fg}{bold}PERSISTENT — cannot confirm following{/}{/}')
        lines.push('  {yellow-fg}No position source, so there is no way to tell this{/}')
        lines.push('  {yellow-fg}apart from a stationary device nearby.{/}')
        lines.push('  {yellow-fg}Start gpsd with a USB GPS, or use a modem with{/}')
        lines.push('  {yellow-fg}GNSS, then travel 300 m and watch this entry.{/}')
      } else {
        lines.push('  {yellow-fg}{bold}PERSISTENT — not yet proven to follow{/}{/}')
        lines.push(`  {yellow-fg}You have only moved ${Math.round(device.displacementM)} m since first{/}`)
        lines.push('  {yellow-fg}contact. Drive further and check back.{/}')
      }
    } else if (device.trackerType) {
      lines.push('  {gray-fg}Known tracker type, but present too briefly to mean{/}')
      lines.push('  {gray-fg}anything yet. Most belong to people around you.{/}')
    } else {
      lines.push('  {gray-fg}Unidentified. Watching for a following pattern.{/}')
    }

    if (t && t.notes) {
      lines.push('')
      lines.push('  {bold}{cyan-fg}About this tracker:{/}')
      lines.push(`  {gray-fg}${this._wrap(t.notes, 4)}{/}`)
    }

    if (device.manufacturer) {
      lines.push('')
      lines.push(`  {bold}{cyan-fg}Chip maker:{/}   {/}{gray-fg}${device.manufacturer}{/}`)
    }

    if (device.beaconIntervalMs != null) {
      // AirTag beacons every ~500 ms, Tile ~700 ms, random devices vary wildly.
      const intervalStr = device.beaconIntervalMs < 1000
        ? `${device.beaconIntervalMs} ms`
        : `${(device.beaconIntervalMs / 1000).toFixed(1)} s`
      const intervalNote = device.beaconIntervalMs <= 750
        ? ' {red-fg}(tracker-like — fixed rapid cadence){/}'
        : device.beaconIntervalMs <= 2000
        ? ' {yellow-fg}(periodic — consistent with a tracker){/}'
        : ' {gray-fg}(irregular — typical of human-carried device){/}'
      lines.push(`  {bold}{cyan-fg}Beacon rate:{/}  {/}${intervalStr}${intervalNote}`)
    }

    if (device.manufacturerHex) {
      lines.push('')
      lines.push(`  {gray-fg}Mfr data: ${device.manufacturerHex.substring(0, 40)}${device.manufacturerHex.length > 40 ? '…' : ''}{/}`)
    }

    this._detailBox.setContent(lines.join('\n'))
  }

  _wrap(text, indent = 0, width = 56) {
    const pad = ' '.repeat(indent)
    const words = String(text).split(/\s+/)
    const lines = []
    let line = ''
    for (const w of words) {
      if ((line + ' ' + w).trim().length > width) {
        lines.push(line.trim())
        line = w
      } else {
        line += ` ${w}`
      }
    }
    if (line.trim()) lines.push(line.trim())
    return lines.join(`\n${pad}`)
  }

  _threatBar(score, threat) {
    const filled = Math.round(Math.max(0, Math.min(100, score)) / 10)
    const tc = THREAT_COLORS[threat] || 'white'
    return `{${tc}-fg}${'█'.repeat(filled)}${'░'.repeat(10 - filled)}{/}`
  }

  _isStable(history) {
    const avg = history.reduce((a, b) => a + b, 0) / history.length
    const variance = history.reduce((a, b) => a + Math.pow(b - avg, 2), 0) / history.length
    return variance < 25
  }

  _ago(ts) {
    if (!ts) return '—'
    const diff = Date.now() - ts
    if (diff < 60000) return `${Math.floor(diff / 1000)}s`
    if (diff < 3600000) return `${Math.floor(diff / 60000)}m`
    return `${Math.floor(diff / 3600000)}h`
  }

  _formatUptime(ms) {
    const s = Math.floor(ms / 1000)
    const m = Math.floor(s / 60)
    const h = Math.floor(m / 60)
    if (h > 0) return `${h}h${m % 60}m`
    if (m > 0) return `${m}m${s % 60}s`
    return `${s}s`
  }

  destroy() {
    if (this._clockTimer) clearInterval(this._clockTimer)
    if (this._screen) {
      try { this._screen.destroy() } catch (_) {}
    }
  }
}

module.exports = Dashboard
