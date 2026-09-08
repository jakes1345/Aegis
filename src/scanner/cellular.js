'use strict'

const fs = require('fs')
const { exec, execFile } = require('child_process')
const EventEmitter = require('events')

const { CELLULAR } = require('../config')

const RAT_RANK = { nr5g: 5, '5g': 5, lte: 4, umts: 3, hspa: 3, wcdma: 3, edge: 2, gprs: 2, gsm: 2 }

// Reads the serving cell your modem or phone is actually camped on.
//
// This is the only honest way to detect cell-site simulators from a laptop.
// A fake tower does not announce itself in the spectrum in any way you can
// distinguish from a real one without decoding — but it *does* change what
// your baseband reports: which cell serves you, on what technology, how loud,
// and with what neighbours. Watch those four over time and catchers stand out.
class CellularScanner extends EventEmitter {
  constructor(opts = {}) {
    super()
    this._interval = opts.pollIntervalMs || CELLULAR.pollIntervalMs
    this._timer = null
    this._source = null
    this._modemIndex = null
    this._atDevice = opts.atDevice || null
    this._stopped = false
  }

  get source() {
    return this._source
  }

  start() {
    this._stopped = false
    this._detectSource((source) => {
      if (this._stopped) return
      if (!source) {
        this.emit('unavailable',
          'No cellular source. Options: a USB modem with ModemManager (mmcli), ' +
          'an Android phone with USB debugging (adb), or a modem exposing an AT port. ' +
          'Without one, fake-tower detection is not possible from this machine.')
        return
      }
      this._source = source
      this.emit('started', source)
      this._poll()
      this._timer = setInterval(() => this._poll(), this._interval)
      if (this._timer.unref) this._timer.unref()
    })
  }

  _detectSource(cb) {
    execFile('mmcli', ['-L'], { timeout: 8000 }, (err, stdout) => {
      if (!err && stdout && /\/Modem\/(\d+)/.test(stdout)) {
        this._modemIndex = stdout.match(/\/Modem\/(\d+)/)[1]
        // Location reporting and signal polling are both opt-in.
        execFile('mmcli', ['-m', this._modemIndex, '--location-enable-3gpp'], { timeout: 8000 }, () => {
          execFile('mmcli', ['-m', this._modemIndex, '--signal-setup=5'], { timeout: 8000 }, () => {
            cb('mmcli')
          })
        })
        return
      }

      execFile('adb', ['shell', 'echo', 'ok'], { timeout: 8000 }, (aerr, aout) => {
        if (!aerr && /ok/.test(aout || '')) return cb('adb')

        const candidates = this._atDevice
          ? [this._atDevice]
          : ['/dev/ttyUSB2', '/dev/ttyUSB3', '/dev/ttyACM1', '/dev/ttyUSB1']
        const device = candidates.find((d) => {
          try { return fs.statSync(d).isCharacterDevice() } catch (_) { return false }
        })
        if (device) {
          this._atDevice = device
          return cb('at')
        }
        cb(null)
      })
    })
  }

  _poll() {
    if (this._stopped) return
    if (this._source === 'mmcli') return this._pollMmcli()
    if (this._source === 'adb') return this._pollAdb()
    if (this._source === 'at') return this._pollAt()
  }

  // --- ModemManager ---------------------------------------------------------

  _pollMmcli() {
    const idx = this._modemIndex
    execFile('mmcli', ['-m', idx, '--location-get', '--output-keyval'], { timeout: 10000 },
      (err, locOut) => {
        if (err) return
        execFile('mmcli', ['-m', idx, '--output-keyval'], { timeout: 10000 }, (err2, genOut) => {
          if (err2) return
          execFile('mmcli', ['-m', idx, '--signal-get', '--output-keyval'], { timeout: 10000 },
            (err3, sigOut) => {
              const kv = (out, key) => {
                if (!out) return null
                const re = new RegExp(`^${key.replace(/\./g, '\\.')}\\s*:\\s*(.+)$`, 'm')
                const m = out.match(re)
                if (!m) return null
                const v = m[1].trim()
                return v === '--' || v === '' ? null : v
              }

              const mcc = kv(locOut, 'modem.location.3gpp.mcc')
              const mnc = kv(locOut, 'modem.location.3gpp.mnc')
              const tac = kv(locOut, 'modem.location.3gpp.tac')
              const lac = kv(locOut, 'modem.location.3gpp.lac')
              const cid = kv(locOut, 'modem.location.3gpp.cid')
              if (!cid) return

              const techLine = genOut
                ? (genOut.match(/^modem\.generic\.access-technologies\.value\[\d+\]\s*:\s*(.+)$/m) || [])[1]
                : null
              const rat = (techLine || '').trim().toLowerCase() || 'unknown'

              let signalDbm = null
              if (!err3 && sigOut) {
                for (const key of [
                  'modem.signal.5g.rsrp', 'modem.signal.lte.rsrp',
                  'modem.signal.umts.rscp', 'modem.signal.gsm.rssi',
                ]) {
                  const v = kv(sigOut, key)
                  if (v != null && isFinite(Number(v))) { signalDbm = Number(v); break }
                }
              }

              this._emitCell({
                mcc: mcc ? String(mcc) : null,
                mnc: mnc ? String(mnc) : null,
                tac: tac || lac || null,
                cellId: String(parseInt(cid, 16) || cid),
                rat,
                signalDbm,
                neighbors: null, // ModemManager does not expose neighbour cells
                source: 'mmcli',
              })
            })
        })
      })
  }

  // --- Android via adb ------------------------------------------------------

  _pollAdb() {
    execFile('adb', ['shell', 'dumpsys', 'telephony.registry'], {
      timeout: 15000,
      maxBuffer: 8 * 1024 * 1024,
    }, (err, stdout) => {
      if (err || !stdout) return
      const cell = parseAndroidRegistry(stdout)
      if (cell) this._emitCell({ ...cell, source: 'adb' })
    })
  }

  // --- Raw AT ---------------------------------------------------------------

  _pollAt() {
    atCommand(this._atDevice, ['AT+CPSI?', 'AT+CEREG?', 'AT+CSQ'], (err, output) => {
      if (err || !output) return
      const cell = parseAtOutput(output)
      if (cell) this._emitCell({ ...cell, source: 'at' })
    })
  }

  _emitCell(cell) {
    this.emit('cell', {
      ...cell,
      ratRank: RAT_RANK[(cell.rat || '').toLowerCase()] || 0,
      ts: Date.now(),
      key: cellKey(cell),
    })
  }

  stop() {
    this._stopped = true
    if (this._timer) { clearInterval(this._timer); this._timer = null }
  }
}

function cellKey(cell) {
  return [cell.mcc || '?', cell.mnc || '?', cell.tac || '?', cell.cellId || '?'].join('-')
}

// dumpsys telephony.registry is verbose and version-dependent, but the cell
// identity and signal blocks have been stable in shape for years.
function parseAndroidRegistry(text) {
  const idBlock =
    text.match(/CellIdentity(Lte|Nr|Wcdma|Gsm)[:\s]*\{([^}]*)\}/) ||
    text.match(/mCellIdentity=CellIdentity(Lte|Nr|Wcdma|Gsm)[:\s]*\{([^}]*)\}/)
  if (!idBlock) return null

  const ratName = { Lte: 'lte', Nr: 'nr5g', Wcdma: 'umts', Gsm: 'gsm' }[idBlock[1]] || 'unknown'
  const body = idBlock[2]

  const field = (name) => {
    const m = body.match(new RegExp(`${name}=(-?[0-9a-fA-F]+)`))
    return m ? m[1] : null
  }

  const mccMnc = (body.match(/mMccMnc=(\d+)/) || [])[1]
  const ci = field('mCi') || field('mNci') || field('mCid')
  // dumpsys reports identifiers in decimal and uses Integer.MAX_VALUE for
  // "unavailable" — hex-parsing these (as the AT path must) corrupts them.
  if (!ci || ci === '2147483647') return null

  const rawTac = field('mTac') || field('mLac')
  const tac = rawTac && rawTac !== '2147483647' ? rawTac : null

  let signalDbm = null
  const sig = text.match(/CellSignalStrength(?:Lte|Nr|Wcdma|Gsm)[:\s]*\{([^}]*)\}/)
  if (sig) {
    const r = sig[1].match(/rsrp=(-?\d+)/) || sig[1].match(/ss[Rr]srp=(-?\d+)/) ||
              sig[1].match(/rscp=(-?\d+)/) || sig[1].match(/rssi=(-?\d+)/)
    if (r) {
      const v = parseInt(r[1], 10)
      if (v > -160 && v < 0) signalDbm = v
    }
  }

  // Neighbours are the entries the modem sees but is not registered to.
  // A serving cell with none at all is a classic catcher tell.
  let neighbors = null
  const cellInfoMatches = text.match(/CellInfo(?:Lte|Nr|Wcdma|Gsm)[:\s]*\{[\s\S]*?\}/g)
  if (cellInfoMatches) {
    neighbors = cellInfoMatches.filter(c => /registered=(NO|false)/i.test(c)).length
  }

  return {
    mcc: mccMnc ? mccMnc.slice(0, 3) : null,
    mnc: mccMnc ? mccMnc.slice(3) : null,
    tac: tac ? String(parseInt(tac, 10)) : null,
    cellId: String(parseInt(ci, 10)),
    rat: ratName,
    signalDbm,
    neighbors,
  }
}

// AT responses mix conventions: CPSI may print "0x1A2B" or a plain decimal,
// while CEREG is always unprefixed hex per 27.007. Guessing wrong shifts every
// cell ID, so the prefix decides rather than a fallback chain.
function parsePrefixedNumber(value) {
  if (value == null) return null
  const s = String(value).trim()
  if (/^0x/i.test(s)) return parseInt(s, 16)
  const n = parseInt(s, 10)
  return Number.isNaN(n) ? null : n
}

function parseAtOutput(output) {
  // SIMCom: +CPSI: LTE,Online,310-260,0x1A2B,12345678,257,EUTRAN-BAND4,2175,5,5,-94,-850,-609,14
  const cpsi = output.match(/\+CPSI:\s*([^\r\n]+)/)
  if (cpsi) {
    const f = cpsi[1].split(',').map(s => s.trim())
    const rat = (f[0] || '').toLowerCase().replace(/[^a-z0-9]/g, '')
    const mccmnc = (f[2] || '').split('-')
    if (f[4]) {
      const rsrp = f.length > 11 ? parseInt(f[11], 10) : NaN
      const tacNum = parsePrefixedNumber(f[3])
      const cellNum = parsePrefixedNumber(f[4])
      return {
        mcc: mccmnc[0] || null,
        mnc: mccmnc[1] || null,
        tac: tacNum != null ? String(tacNum) : null,
        cellId: String(cellNum != null ? cellNum : f[4]),
        rat: rat.includes('lte') ? 'lte' : rat.includes('nr') ? 'nr5g' : rat.includes('wcdma') ? 'umts' : rat,
        // CPSI reports RSRP in tenths of a dBm in this position.
        signalDbm: isFinite(rsrp) ? Math.round(rsrp / 10) : null,
        neighbors: null,
      }
    }
  }

  // Generic: +CEREG: 2,1,"1A2B","01234567",7
  const cereg = output.match(/\+C(?:E|G)?REG:\s*\d+,\s*\d+,\s*"([0-9A-Fa-f]+)"\s*,\s*"([0-9A-Fa-f]+)"(?:\s*,\s*(\d+))?/)
  if (cereg) {
    const actMap = { 0: 'gsm', 2: 'umts', 3: 'edge', 7: 'lte', 12: 'lte', 13: 'nr5g' }
    let signalDbm = null
    const csq = output.match(/\+CSQ:\s*(\d+)/)
    if (csq) {
      const rssi = parseInt(csq[1], 10)
      if (rssi >= 0 && rssi <= 31) signalDbm = -113 + 2 * rssi
    }
    return {
      mcc: null,
      mnc: null,
      tac: String(parseInt(cereg[1], 16)),
      cellId: String(parseInt(cereg[2], 16)),
      rat: actMap[parseInt(cereg[3], 10)] || 'unknown',
      signalDbm,
      neighbors: null,
    }
  }

  return null
}

// Minimal AT session over a character device. Avoids a native serial
// dependency by letting stty configure the line first.
function atCommand(device, commands, cb) {
  exec(`stty -F ${device} 115200 raw -echo 2>/dev/null`, () => {
    let read
    let write
    let output = ''
    let done = false

    const finish = (err) => {
      if (done) return
      done = true
      clearTimeout(timer)
      try { if (read) read.destroy() } catch (_) {}
      try { if (write) write.end() } catch (_) {}
      cb(err, output)
    }

    const timer = setTimeout(() => finish(null), 4000)

    try {
      read = fs.createReadStream(device, { encoding: 'utf8' })
      write = fs.createWriteStream(device)
    } catch (err) {
      return finish(err)
    }

    read.on('data', (chunk) => { output += chunk })
    read.on('error', (err) => finish(err))
    write.on('error', (err) => finish(err))

    let i = 0
    const sendNext = () => {
      if (i >= commands.length) return
      try { write.write(`${commands[i++]}\r`) } catch (_) { return finish(null) }
      setTimeout(sendNext, 600)
    }
    sendNext()
  })
}

module.exports = CellularScanner
module.exports.parseAndroidRegistry = parseAndroidRegistry
module.exports.parseAtOutput = parseAtOutput
module.exports.parsePrefixedNumber = parsePrefixedNumber
module.exports.cellKey = cellKey
module.exports.RAT_RANK = RAT_RANK
