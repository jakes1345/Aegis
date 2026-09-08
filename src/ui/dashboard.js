'use strict'

const blessed = require('blessed')

const THREAT_COLORS = {
  CRITICAL: 'red',
  HIGH: 'red',
  MEDIUM: 'yellow',
  LOW: 'green',
  UNKNOWN: 'yellow',
  NONE: 'gray',
}

const DETECTION_GUIDE = `{bold}{cyan-fg}HOW TRACKING DETECTION WORKS{/}

{bold}{cyan-fg}BLE SCANNING (this tool){/}
Scans Bluetooth Low Energy for known tracker signatures continuously.
Also flags ANY unknown BLE device seen 5+ times over 10+ minutes.

{bold}BLE TRACKERS DETECTED:{/}
 • {red-fg}Apple AirTag{/} — mfr data 0x004C type 0x12/0x19 (FindMy)
 • {red-fg}Apple Find My devices{/} — Chipolo ONE Spot, Pebblebee, Invoxia, Motorola Tag
 • {red-fg}Tile / Life360{/} — service UUID 0xFEED
 • {red-fg}Samsung SmartTag / SmartTag2{/} — service UUID 0xFD5A / 0xFD70
 • {red-fg}Chipolo{/} — service UUID 0xFE9F / 0xFEBE
 • {red-fg}Pebblebee{/} — service UUID 0xFE2C / 0xFEE7
 • {red-fg}Orbit / KeySmart{/} — service UUID 0xFFF3
 • {red-fg}Nut / Nutale{/} — service UUID 0xAA01
 • {red-fg}GPS tracker BLE config{/} — TK102/TK103/GT06/GL300/Concox/SinoTrack/Coban
 • {yellow-fg}Eddystone beacon{/} — can be used for proximity/location tracking
 • {yellow-fg}Unknown following device{/} — any BLE device persisting 10+ min

{bold}{magenta-fg}WIFI SCANNING (this tool){/}
Scans nearby access points every 30 seconds via nmcli/airport.
Detects trackers that create their own WiFi hotspot for config/reporting.

{bold}WIFI TRACKERS DETECTED:{/}
 • {red-fg}OBD-II port trackers{/} — plug into car OBD port, create WiFi AP
     (Vyncs, Bouncie, Linxup, Zubie, Hummingbird, generic OBD-WiFi)
 • {red-fg}Standalone GPS + WiFi trackers{/} — SSID contains GPS/TRACKER/LOCATE
 • {red-fg}Cellular GPS trackers with WiFi config{/} — TK103, GT06, ST-901, GL300, GV300
 • {red-fg}Satellite tracker hotspots{/} — SPOT, Garmin inReach, Iridium GO, Bivy Stick

{bold}GSM/LTE GPS TRACKERS (hidden in your car){/}
These report location via SIM card. Harder to detect without hardware.
 • Many have BLE config interface — may show up here as unknown device
 • RF detection: use RTL-SDR dongle, scan GSM bands:
     850 MHz, 900 MHz, 1800 MHz, 1900 MHz, 2100 MHz (LTE B4/B7/B12)
 • Listen for short periodic TX bursts (tracker phoning home)
 • Software: GQRX, SDR++ with GSM plugin, or Kal (kalibrate-rtl)

{bold}SATELLITE TRACKERS (Iridium, Globalstar){/}
Used in high-end trackers (Spot, Garmin inReach). Transmit infrequently.
 • Iridium: 1616–1626.5 MHz (L-band)
 • Globalstar uplink: 1610–1618.85 MHz
 • RTL-SDR + iridium-toolkit to decode Iridium SBD messages
 • Transmission bursts: ~20–90 seconds apart when actively tracking

{bold}CELL TOWER / IMSI CATCHER DETECTION{/}
Someone may be using a fake cell tower (Stingray) to intercept your phone.
 • Android: install "SnoopSnitch" or "AIMSICD" (checks tower legitimacy)
 • Desktop + SDR: gr-gsm + Wireshark can decode GSM control channels
 • Suspicious signs: sudden drop to 2G, unknown tower ID, high signal
   from tower not in public cell tower databases (OpenCellID, etc.)

{bold}WHERE PHYSICAL TRACKERS HIDE IN VEHICLES:{/}
 • Wheel wells (magnetic mount)       • Under front/rear bumper
 • Under the car frame (magnetic)     • Inside OBD-II port (self-powered)
 • Behind license plate               • Inside trunk liner
 • Under seats                        • Engine bay near firewall

{bold}PHYSICAL SWEEP TOOLS:{/}
 • RF detector wand (bug detector): sweeps 1MHz–8GHz for transmitters
 • Magnetic wand: finds magnetically-attached trackers
 • AirTag Android detector: Apple's own app (iOS) or Android equivalent

{gray-fg}Press I to close this guide | Q or Ctrl+C to exit{/}`

class Dashboard {
  constructor() {
    this._screen = null
    this._devices = []       // all non-benign devices tracked
    this._alerts = []
    this._selectedIndex = 0
    this._showGuide = false
    this._guideBox = null
    this._initialized = false
    this._scanCount = 0
    this._startTime = Date.now()
  }

  render() {
    this._screen = blessed.screen({
      smartCSR: true,
      title: 'Track Detect — Tracking Device Scanner',
      fullUnicode: true,
      dockBorders: true,
    })

    this._buildLayout()
    this._bindKeys()
    this._initialized = true
    this._screen.render()

    // Refresh clock every second
    this._clockTimer = setInterval(() => {
      this._updateHeader()
      this._screen.render()
    }, 1000)
  }

  _buildLayout() {
    const s = this._screen

    // Header bar
    this._header = blessed.box({
      parent: s,
      top: 0,
      left: 0,
      width: '100%',
      height: 3,
      style: { fg: 'white', bg: 'blue', bold: true },
      tags: true,
      content: this._headerContent(),
    })

    // Left top: tracker list
    this._listBox = blessed.list({
      parent: s,
      label: ' {bold}DETECTED DEVICES{/}  {cyan-fg}B{/}=BLE {magenta-fg}W{/}=WiFi ',
      tags: true,
      top: 3,
      left: 0,
      width: '45%',
      bottom: '38%',
      border: { type: 'line' },
      style: {
        fg: 'white',
        border: { fg: 'cyan' },
        label: { fg: 'cyan' },
        selected: { fg: 'black', bg: 'cyan', bold: true },
        item: { hover: { bg: '#003333' } },
      },
      keys: true,
      vi: true,
      mouse: true,
      scrollable: true,
      alwaysScroll: true,
    })

    // Right top: device details
    this._detailBox = blessed.box({
      parent: s,
      label: ' {bold}DEVICE DETAILS{/} ',
      tags: true,
      top: 3,
      left: '45%',
      width: '55%',
      bottom: '38%',
      border: { type: 'line' },
      style: {
        fg: 'white',
        border: { fg: 'cyan' },
        label: { fg: 'cyan' },
      },
      scrollable: true,
      alwaysScroll: true,
      keys: true,
      mouse: true,
      tags: true,
    })

    // Bottom left: alerts
    this._alertBox = blessed.list({
      parent: s,
      label: ' {bold}{red-fg}ALERTS{/}{/} ',
      tags: true,
      top: '62%',
      left: 0,
      width: '45%',
      bottom: 0,
      border: { type: 'line' },
      style: {
        fg: 'white',
        border: { fg: 'red' },
        label: { fg: 'red' },
      },
      scrollable: true,
      alwaysScroll: true,
      mouse: true,
      tags: true,
    })

    // Bottom right: log
    this._logBox = blessed.log({
      parent: s,
      label: ' {bold}{gray-fg}SCAN LOG{/}{/} ',
      tags: true,
      top: '62%',
      left: '45%',
      width: '55%',
      bottom: 0,
      border: { type: 'line' },
      style: {
        fg: '#888888',
        border: { fg: '#444444' },
        label: { fg: '#666666' },
      },
      scrollable: true,
      alwaysScroll: true,
      mouse: true,
      tags: true,
    })

    // Guide modal (hidden by default)
    this._guideBox = blessed.box({
      parent: s,
      label: ' {bold}DETECTION GUIDE{/} ',
      tags: true,
      top: 'center',
      left: 'center',
      width: '80%',
      height: '85%',
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

    // Render initial empty states
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

    s.key(['i', 'I'], () => {
      this._showGuide = !this._showGuide
      if (this._showGuide) {
        this._guideBox.show()
        this._guideBox.focus()
      } else {
        this._guideBox.hide()
        this._listBox.focus()
      }
      s.render()
    })

    this._guideBox.key(['i', 'I', 'escape'], () => {
      this._showGuide = false
      this._guideBox.hide()
      this._listBox.focus()
      s.render()
    })
  }

  _headerContent() {
    const uptime = this._formatUptime(Date.now() - this._startTime)
    const count = this._devices.filter(d => !d.benign && d.trackerType).length
    const threats = count > 0 ? `{red-fg} ⚠ ${count} TRACKER${count > 1 ? 'S' : ''} FOUND{/}` : ''
    return ` {bold}TRACK DETECT{/}  |  Uptime: ${uptime}  |  Scans: ${this._scanCount}${threats}  {gray-fg}[I] Guide  [Tab] Switch  [Q] Quit{/}`
  }

  _updateHeader() {
    if (this._header) {
      this._header.setContent(this._headerContent())
    }
  }

  setStatus(text, color = 'green') {
    const statusIcons = { green: '◉', red: '◎', yellow: '◌' }
    const icon = statusIcons[color] || '◉'
    // Embed status in the log
    if (this._initialized) {
      this.log(`{${color}-fg}${icon} ${text}{/}`)
    }
  }

  updateDevice(device) {
    if (!this._initialized) return
    this._scanCount++

    const idx = this._devices.findIndex(d => d.id === device.id)
    if (idx >= 0) {
      this._devices[idx] = device
    } else {
      this._devices.push(device)
    }

    // Sort by threat score descending, then by appearances
    this._devices.sort((a, b) => {
      if (b.threatScore !== a.threatScore) return b.threatScore - a.threatScore
      return (b.appearances || 0) - (a.appearances || 0)
    })

    this._renderDeviceList()
    this._renderDetails()
    this._updateHeader()
    this._screen.render()
  }

  addAlert(device) {
    if (!device.alertMessage) return
    const time = new Date().toLocaleTimeString()
    const isCritical = device.threat === 'CRITICAL' || device.following
    const icon = isCritical ? '{red-fg}{bold}[!!!]{/}{/}' : '{yellow-fg}[!! ]{/}'
    this._alerts.unshift(`${icon} {bold}${time}{/}  ${device.alertMessage}`)
    this._alertBox.setItems(this._alerts)
    this._alertBox.scrollTo(0)
    this._screen.render()
  }

  log(message, level = 'info') {
    if (!this._initialized || !this._logBox) return
    const colors = { info: '{#888888-fg}', error: '{red-fg}', warn: '{yellow-fg}' }
    const col = colors[level] || '{gray-fg}'
    const time = new Date().toLocaleTimeString()
    this._logBox.log(`${col}${time}{/}  ${message}`)
  }

  _renderDeviceList() {
    const visible = this._devices.filter(d => !d.benign)

    if (visible.length === 0) {
      this._listBox.setItems([
        '{gray-fg}  No suspicious devices detected yet...{/}',
        '{gray-fg}  BLE: AirTag|Tile|SmartTag|GPS tracker BLE{/}',
        '{gray-fg}  WiFi: OBD trackers|GPS hotspots{/}',
        '{gray-fg}  Press I for full detection guide{/}',
      ])
      return
    }

    const items = visible.map(d => this._formatRow(d))
    this._listBox.setItems(items)
  }

  _formatRow(d) {
    const tc = THREAT_COLORS[d.threat] || 'white'
    const icons = {
      CRITICAL: `{red-fg}{bold}[!!!]{/}{/}`,
      HIGH: `{red-fg}[!! ]{/}`,
      MEDIUM: `{yellow-fg}[!  ]{/}`,
      LOW: `{green-fg}[   ]{/}`,
      UNKNOWN: `{yellow-fg}[?  ]{/}`,
      NONE: `{gray-fg}[   ]{/}`,
    }
    const icon = icons[d.threat] || `{white-fg}[   ]{/}`
    const typeTag = d.scanType === 'wifi'
      ? '{magenta-fg}W{/}'
      : '{cyan-fg}B{/}'
    const name = (d.name || 'Unknown').substring(0, 16).padEnd(16)
    const rssi = String(d.rssi || 0).padStart(4)
    const seen = this._ago(d.firstSeen || Date.now())
    const followFlag = d.following ? ` {red-fg}FOLLOWING{/}` : ''
    return `${icon}${typeTag} {${tc}-fg}${name}{/} ${rssi}dBm  ${seen}${followFlag}`
  }

  _renderDetails() {
    if (!this._detailBox) return
    const visible = this._devices.filter(d => !d.benign)
    const device = visible[this._selectedIndex]

    if (!device) {
      this._detailBox.setContent(
        '\n\n  {gray-fg}No device selected.\n\n  Select a device from the left panel.\n\n  Press {bold}I{/} for detection guide.{/}'
      )
      return
    }

    const t = device.trackerInfo
    const tc = THREAT_COLORS[device.threat] || 'white'
    const bar = this._threatBar(device.threatScore || 0, device.threat)
    const followDur = device.followDuration
      ? `${Math.floor(device.followDuration / 60000)}m ${Math.floor((device.followDuration % 60000) / 1000)}s`
      : '—'
    const stable = device.rssiHistory && device.rssiHistory.length >= 5
      ? this._isStable(device.rssiHistory) ? '{red-fg}YES (attached?){/}' : 'No'
      : 'Not enough data'

    const scanLabel = device.scanType === 'wifi'
      ? '{magenta-fg}WiFi{/}'
      : '{cyan-fg}Bluetooth LE{/}'
    const unknownType = device.scanType === 'wifi' ? 'Unknown WiFi Device' : 'Unknown BLE Device'

    const lines = [
      `  {bold}{cyan-fg}Name:{/}         {/}{bold}${device.name || 'Unknown'}{/}`,
      `  {bold}{cyan-fg}Scan type:{/}    {/}${scanLabel}`,
      `  {bold}{cyan-fg}Address:{/}      {/}{gray-fg}${device.address || 'N/A'}{/}`,
      `  {bold}{cyan-fg}Detected as:{/}  {/}{${tc}-fg}${t ? t.name : unknownType}{/}`,
      `  {bold}{cyan-fg}Brand:{/}        {/}${t ? t.brand : 'Unknown'}`,
      '',
      `  {bold}{cyan-fg}Threat:{/}       {/}{${tc}-fg}{bold}${device.threat}{/}{/}`,
      `  {bold}{cyan-fg}Score:{/}        {/}${bar} ${device.threatScore || 0}/100`,
      `  {bold}{cyan-fg}Signal:{/}       {/}${device.rssi || 0} dBm (est. ${device.distance || 'unknown'})`,
      `  {bold}{cyan-fg}Stable signal:{/} ${stable}`,
    ]

    // WiFi-specific fields
    if (device.scanType === 'wifi') {
      lines.push(`  {bold}{cyan-fg}Channel:{/}      {/}${device.channel || 'N/A'}`)
      lines.push(`  {bold}{cyan-fg}Security:{/}     {/}${device.security || 'Unknown'}`)
    }

    lines.push('')
    lines.push(`  {bold}{cyan-fg}First seen:{/}   {/}${this._ago(device.firstSeen)}`)
    lines.push(`  {bold}{cyan-fg}Last seen:{/}    {/}${this._ago(device.lastSeen)}`)
    lines.push(`  {bold}{cyan-fg}Appearances:{/}  {/}{bold}${device.appearances || 1}x{/}`)
    lines.push(`  {bold}{cyan-fg}Following you:{/} {/}${device.following ? `{red-fg}{bold}YES — ${followDur}{/}{/}` : 'Not yet'}`)
    lines.push('')

    if (t && t.notes) {
      lines.push(`  {bold}{cyan-fg}About this tracker:{/}`)
      lines.push(`  {gray-fg}${t.notes}{/}`)
      lines.push('')
    }

    if (device.following) {
      lines.push(`  {bold}{red-fg}⚠ ALERT: This device is following you!{/}{/}`)
      lines.push(`  {red-fg}Check your vehicle: wheel wells, bumpers, undercarriage.{/}`)
      lines.push(`  {red-fg}Check bags and personal items for hidden devices.{/}`)
      if (device.trackerType === 'airtag') {
        lines.push(`  {red-fg}iOS: "Items Detected Near You" notification.{/}`)
        lines.push(`  {red-fg}Android: "Tracker Detect" app — play sound on AirTag.{/}`)
      }
      if (device.scanType === 'wifi') {
        lines.push(`  {red-fg}WiFi tracker: physically inspect vehicle for OBD-II{/}`)
        lines.push(`  {red-fg}dongle or magnetic GPS unit with WiFi antenna.{/}`)
      }
    } else if (device.trackerType) {
      lines.push(`  {yellow-fg}Known tracker type detected. Monitor if it persists.{/}`)
      lines.push(`  {yellow-fg}If seen across multiple locations, take action.{/}`)
    } else {
      const medium = device.scanType === 'wifi' ? 'WiFi' : 'BLE'
      lines.push(`  {gray-fg}Unknown ${medium} device. Monitoring for following pattern.{/}`)
      lines.push(`  {gray-fg}Will alert if seen 5+ times over 10+ minutes.{/}`)
    }

    if (device.manufacturerHex) {
      lines.push('')
      lines.push(`  {gray-fg}Manufacturer data: ${device.manufacturerHex.substring(0, 32)}${device.manufacturerHex.length > 32 ? '...' : ''}{/}`)
    }

    this._detailBox.setContent(lines.join('\n'))
  }

  _threatBar(score, threat) {
    const filled = Math.round(Math.max(0, Math.min(100, score)) / 10)
    const tc = THREAT_COLORS[threat] || 'white'
    return `{${tc}-fg}${'█'.repeat(filled)}${'░'.repeat(10 - filled)}{/}`
  }

  _isStable(history) {
    const avg = history.reduce((a, b) => a + b, 0) / history.length
    const variance = history.reduce((a, b) => a + Math.pow(b - avg, 2), 0) / history.length
    return variance < 25 // std dev < 5 dBm
  }

  _ago(ts) {
    if (!ts) return 'N/A'
    const diff = Date.now() - ts
    if (diff < 60000) return `${Math.floor(diff / 1000)}s ago`
    if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`
    return `${Math.floor(diff / 3600000)}h ago`
  }

  _formatUptime(ms) {
    const s = Math.floor(ms / 1000)
    const m = Math.floor(s / 60)
    const h = Math.floor(m / 60)
    if (h > 0) return `${h}h ${m % 60}m`
    if (m > 0) return `${m}m ${s % 60}s`
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
