'use strict'

const net = require('net')
const fs = require('fs')
const { exec, execFile } = require('child_process')
const EventEmitter = require('events')

// Position is what makes "following" mean anything. Without it, a device seen
// for twenty minutes is indistinguishable from a neighbour's tracker sitting
// on the other side of a wall. Three sources, best first.
class GPSSource extends EventEmitter {
  constructor(opts = {}) {
    super()
    this._gpsdHost = opts.host || '127.0.0.1'
    this._gpsdPort = opts.port || 2947
    this._nmeaDevice = opts.nmeaDevice || null
    this._socket = null
    this._stream = null
    this._timer = null
    this._source = null
    this._stopped = false
    this._last = null
  }

  get source() {
    return this._source
  }

  get lastFix() {
    return this._last
  }

  start() {
    this._stopped = false
    this._tryGpsd()
  }

  _emitFix(fix) {
    if (!fix || typeof fix.lat !== 'number' || typeof fix.lon !== 'number') return
    if (!isFinite(fix.lat) || !isFinite(fix.lon)) return
    if (Math.abs(fix.lat) > 90 || Math.abs(fix.lon) > 180) return
    if (fix.lat === 0 && fix.lon === 0) return
    this._last = { ...fix, ts: fix.ts || Date.now() }
    this.emit('fix', this._last)
  }

  // --- gpsd -----------------------------------------------------------------

  _tryGpsd() {
    const sock = net.connect({ host: this._gpsdHost, port: this._gpsdPort })
    let buffer = ''
    let gotBanner = false

    const fail = () => {
      try { sock.destroy() } catch (_) {}
      if (this._socket === sock) this._socket = null
      if (!gotBanner && !this._stopped) this._tryModem()
    }

    sock.setEncoding('utf8')
    sock.on('connect', () => {
      this._socket = sock
      sock.write('?WATCH={"enable":true,"json":true}\n')
    })

    sock.on('data', (chunk) => {
      buffer += chunk
      const lines = buffer.split('\n')
      buffer = lines.pop()
      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed) continue
        let msg
        try { msg = JSON.parse(trimmed) } catch (_) { continue }

        if (msg.class === 'VERSION' && !gotBanner) {
          gotBanner = true
          this._source = 'gpsd'
          this.emit('started', 'gpsd')
        }
        // mode 2 = 2D fix, 3 = 3D. Anything less has no usable position.
        if (msg.class === 'TPV' && msg.mode >= 2) {
          this._emitFix({
            lat: msg.lat,
            lon: msg.lon,
            alt: msg.alt,
            speed: typeof msg.speed === 'number' ? msg.speed : null,
            accuracy: msg.eph != null ? msg.eph : null,
            ts: msg.time ? Date.parse(msg.time) : Date.now(),
            source: 'gpsd',
          })
        }
      }
    })

    sock.on('error', fail)
    sock.on('close', () => {
      if (this._socket === sock) this._socket = null
      if (gotBanner && !this._stopped) {
        this.emit('warning', 'gpsd connection closed')
        this._source = null
        this._tryModem()
      } else {
        fail()
      }
    })
  }

  // --- ModemManager GNSS ----------------------------------------------------

  _tryModem() {
    execFile('mmcli', ['-L'], { timeout: 8000 }, (err, stdout) => {
      if (err || !stdout) return this._tryNmeaDevice()
      const m = stdout.match(/\/Modem\/(\d+)/)
      if (!m) return this._tryNmeaDevice()
      const index = m[1]

      // GNSS is off by default; enabling it is harmless and reversible.
      execFile('mmcli', ['-m', index, '--location-enable-gps-raw'], { timeout: 8000 }, () => {
        this._pollModem(index, true)
      })
    })
  }

  _pollModem(index, first) {
    if (this._stopped) return
    execFile('mmcli', ['-m', index, '--location-get', '--output-keyval'],
      { timeout: 8000 }, (err, stdout) => {
        if (!err && stdout) {
          const lat = this._keyval(stdout, 'modem.location.gps.latitude')
          const lon = this._keyval(stdout, 'modem.location.gps.longitude')
          if (lat != null && lon != null) {
            if (first || this._source !== 'modem-gnss') {
              this._source = 'modem-gnss'
              this.emit('started', 'modem-gnss')
            }
            this._emitFix({
              lat: Number(lat),
              lon: Number(lon),
              alt: Number(this._keyval(stdout, 'modem.location.gps.altitude')) || null,
              speed: null,
              ts: Date.now(),
              source: 'modem-gnss',
            })
          } else if (first) {
            return this._tryNmeaDevice()
          }
        } else if (first) {
          return this._tryNmeaDevice()
        }

        this._timer = setTimeout(() => this._pollModem(index, false), 5000)
        if (this._timer.unref) this._timer.unref()
      })
  }

  _keyval(output, key) {
    const re = new RegExp(`^${key.replace(/\./g, '\\.')}\\s*:\\s*(.+)$`, 'm')
    const m = output.match(re)
    if (!m) return null
    const v = m[1].trim()
    return v === '--' || v === '' ? null : v
  }

  // --- Raw NMEA serial device ----------------------------------------------

  _tryNmeaDevice() {
    const candidates = this._nmeaDevice
      ? [this._nmeaDevice]
      : ['/dev/ttyACM0', '/dev/ttyUSB0', '/dev/gps0']

    const device = candidates.find((d) => {
      try { return fs.statSync(d).isCharacterDevice() } catch (_) { return false }
    })

    if (!device) {
      this.emit('unavailable',
        'No position source. Install gpsd (gpsd + a USB GPS), or attach a cellular modem with GNSS. ' +
        'Without a fix, devices can only be reported as persistent, never as following.')
      return
    }

    // A USB GPS puck usually enumerates at 9600 or 4800 baud; set it before
    // reading or the stream is garbage.
    exec(`stty -F ${device} 9600 raw -echo 2>/dev/null`, () => {
      let stream
      try {
        stream = fs.createReadStream(device, { encoding: 'utf8' })
      } catch (err) {
        this.emit('unavailable', `Could not open ${device}: ${err.message}`)
        return
      }

      this._stream = stream
      this._source = 'nmea'
      this.emit('started', `nmea (${device})`)

      let buffer = ''
      stream.on('data', (chunk) => {
        buffer += chunk
        const lines = buffer.split(/\r?\n/)
        buffer = lines.pop()
        for (const line of lines) {
          const fix = parseNMEA(line)
          if (fix) this._emitFix({ ...fix, source: 'nmea' })
        }
      })
      stream.on('error', (err) => {
        this.emit('warning', `NMEA read error: ${err.message}`)
      })
    })
  }

  stop() {
    this._stopped = true
    if (this._timer) { clearTimeout(this._timer); this._timer = null }
    if (this._socket) { try { this._socket.destroy() } catch (_) {} this._socket = null }
    if (this._stream) { try { this._stream.destroy() } catch (_) {} this._stream = null }
  }
}

// ddmm.mmmm -> decimal degrees.
function nmeaCoord(value, hemi) {
  if (!value) return null
  const v = parseFloat(value)
  if (!isFinite(v)) return null
  const deg = Math.floor(v / 100)
  const min = v - deg * 100
  let dd = deg + min / 60
  if (hemi === 'S' || hemi === 'W') dd = -dd
  return dd
}

function parseNMEA(line) {
  if (!line || line[0] !== '$') return null
  const body = line.slice(1).split('*')[0]
  const f = body.split(',')
  const type = f[0].slice(2)

  if (type === 'RMC') {
    if (f[2] !== 'A') return null
    const lat = nmeaCoord(f[3], f[4])
    const lon = nmeaCoord(f[5], f[6])
    if (lat == null || lon == null) return null
    const knots = parseFloat(f[7])
    return {
      lat,
      lon,
      speed: isFinite(knots) ? knots * 0.514444 : null,
      ts: Date.now(),
    }
  }

  if (type === 'GGA') {
    const quality = parseInt(f[6], 10)
    if (!quality) return null
    const lat = nmeaCoord(f[2], f[3])
    const lon = nmeaCoord(f[4], f[5])
    if (lat == null || lon == null) return null
    const hdop = parseFloat(f[8])
    return {
      lat,
      lon,
      alt: parseFloat(f[9]) || null,
      accuracy: isFinite(hdop) ? hdop * 5 : null,
      speed: null,
      ts: Date.now(),
    }
  }

  return null
}

module.exports = { GPSSource, parseNMEA, nmeaCoord }
