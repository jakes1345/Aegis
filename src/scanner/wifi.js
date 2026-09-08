'use strict'

const { exec } = require('child_process')
const EventEmitter = require('events')

const SCAN_INTERVAL_MS = 30 * 1000 // rescan every 30 seconds

class WiFiScanner extends EventEmitter {
  constructor() {
    super()
    this._timer = null
    this._platform = process.platform
    this._tool = null
  }

  start() {
    this._detectTool((tool) => {
      if (!tool) {
        this.emit('unsupported', 'No WiFi scanning tool found (nmcli or airport required)')
        return
      }
      this._tool = tool
      this.emit('started', tool)
      this._scan()
      this._timer = setInterval(() => this._scan(), SCAN_INTERVAL_MS)
    })
  }

  stop() {
    if (this._timer) {
      clearInterval(this._timer)
      this._timer = null
    }
  }

  _detectTool(cb) {
    if (this._platform === 'linux') {
      exec('command -v nmcli', (err) => {
        if (!err) { cb('nmcli'); return }
        exec('command -v iwlist', (err2) => cb(err2 ? null : 'iwlist'))
      })
    } else if (this._platform === 'darwin') {
      const airport = '/System/Library/PrivateFrameworks/Apple80211.framework/Versions/Current/Resources/airport'
      exec(`test -x "${airport}"`, (err) => cb(err ? null : 'airport'))
    } else {
      cb(null)
    }
  }

  _scan() {
    let cmd
    if (this._tool === 'nmcli') {
      // --rescan auto: refreshes if last scan was >30s ago; won't disrupt active connections
      cmd = 'nmcli -t -f SSID,BSSID,SIGNAL,CHAN,SECURITY dev wifi list --rescan auto 2>/dev/null'
    } else if (this._tool === 'iwlist') {
      cmd = 'iwlist scanning 2>/dev/null'
    } else if (this._tool === 'airport') {
      cmd = '/System/Library/PrivateFrameworks/Apple80211.framework/Versions/Current/Resources/airport -s 2>/dev/null'
    } else {
      return
    }

    exec(cmd, { timeout: 20000 }, (err, stdout) => {
      if (err || !stdout.trim()) return
      const networks = this._parse(stdout)
      networks.forEach(n => this.emit('network', n))
    })
  }

  _parse(output) {
    if (this._tool === 'nmcli') return this._parseNmcli(output)
    if (this._tool === 'iwlist') return this._parseIwlist(output)
    if (this._tool === 'airport') return this._parseAirport(output)
    return []
  }

  _parseNmcli(output) {
    // nmcli -t format: SSID:BSSID:SIGNAL:CHAN:SECURITY
    // Colons inside SSID values are escaped as \:
    const networks = []
    for (const line of output.trim().split('\n')) {
      if (!line.trim()) continue

      // Split on unescaped colons
      const fields = []
      let current = ''
      for (let i = 0; i < line.length; i++) {
        if (line[i] === '\\' && line[i + 1] === ':') {
          current += ':'
          i++
        } else if (line[i] === ':') {
          fields.push(current)
          current = ''
        } else {
          current += line[i]
        }
      }
      fields.push(current)

      if (fields.length < 2) continue

      // BSSID is always a MAC address in AA:BB:CC:DD:EE:FF form — find it
      // In nmcli -t output, BSSID is fields[1] but has internal colons already split
      // We need to reconstruct: first field is SSID, next 6 are BSSID octets
      // Actually with our split, fields[1] through fields[6] would be the BSSID parts
      // unless SSID has no escaped colons. Let's detect the BSSID by looking for MAC pattern.
      let bssidStart = -1
      for (let i = 1; i < fields.length - 4; i++) {
        if (/^[0-9A-Fa-f]{2}$/.test(fields[i]) &&
            /^[0-9A-Fa-f]{2}$/.test(fields[i + 1]) &&
            /^[0-9A-Fa-f]{2}$/.test(fields[i + 2]) &&
            /^[0-9A-Fa-f]{2}$/.test(fields[i + 3]) &&
            /^[0-9A-Fa-f]{2}$/.test(fields[i + 4]) &&
            /^[0-9A-Fa-f]{2}$/.test(fields[i + 5])) {
          bssidStart = i
          break
        }
      }

      if (bssidStart === -1) continue

      const ssid = fields.slice(0, bssidStart).join(':') || '<hidden>'
      const bssid = fields.slice(bssidStart, bssidStart + 6).join(':').toUpperCase()
      const signal = parseInt(fields[bssidStart + 6]) || 0
      const channel = parseInt(fields[bssidStart + 7]) || 0
      const security = fields.slice(bssidStart + 8).join(':') || 'Open'

      networks.push({ ssid, bssid, signal, channel, security })
    }
    return networks
  }

  _parseIwlist(output) {
    // Parse iwlist scan output
    const networks = []
    const cells = output.split(/Cell \d+ - /)
    for (const cell of cells.slice(1)) {
      const ssidMatch = cell.match(/ESSID:"([^"]*)"/)
      const bssidMatch = cell.match(/Address: ([0-9A-Fa-f:]{17})/)
      const signalMatch = cell.match(/Signal level=(-?\d+)/)
      const channelMatch = cell.match(/Channel[:\s]+(\d+)/)

      if (!bssidMatch) continue
      networks.push({
        ssid: ssidMatch ? ssidMatch[1] || '<hidden>' : '<hidden>',
        bssid: bssidMatch[1].toUpperCase(),
        signal: signalMatch ? parseInt(signalMatch[1]) : 0,
        channel: channelMatch ? parseInt(channelMatch[1]) : 0,
        security: cell.includes('WPA') ? 'WPA' : cell.includes('WEP') ? 'WEP' : 'Open',
      })
    }
    return networks
  }

  _parseAirport(output) {
    // airport -s output:
    //                  SSID  BSSID             RSSI  CHANNEL  HT  CC  SECURITY
    const networks = []
    const lines = output.trim().split('\n').slice(1) // skip header

    for (const line of lines) {
      const bssidMatch = line.match(/([0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2})/i)
      if (!bssidMatch) continue

      const bssidIdx = line.indexOf(bssidMatch[0])
      const ssid = line.substring(0, bssidIdx).trim() || '<hidden>'
      const rest = line.substring(bssidIdx + 17).trim().split(/\s+/)

      networks.push({
        ssid,
        bssid: bssidMatch[0].toUpperCase(),
        signal: parseInt(rest[0]) || 0,
        channel: parseInt(rest[1]) || 0,
        security: rest[3] || 'Open',
      })
    }
    return networks
  }
}

module.exports = WiFiScanner
