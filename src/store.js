'use strict'

const fs = require('fs')
const os = require('os')
const path = require('path')

// IMSI-catcher detection needs to know what normal looks like *here*. Public
// tower databases need a key and a network connection; a baseline you build
// yourself needs neither and is more accurate for the places you actually go.
// So state persists across runs.
const DIR = process.env.TRACK_DETECT_HOME || path.join(os.homedir(), '.track-detect')

class Store {
  constructor(filename) {
    this._file = path.join(DIR, filename)
    this._data = null
    this._writeTimer = null
    this._dirty = false
  }

  load(fallback = {}) {
    try {
      const raw = fs.readFileSync(this._file, 'utf8')
      this._data = JSON.parse(raw)
    } catch (_) {
      this._data = fallback
    }
    return this._data
  }

  get data() {
    if (this._data === null) this.load()
    return this._data
  }

  set data(value) {
    this._data = value
    this.markDirty()
  }

  // Writes are batched: cell baselines update every poll and the disk does not
  // need to hear about each one.
  markDirty(delayMs = 5000) {
    this._dirty = true
    if (this._writeTimer) return
    this._writeTimer = setTimeout(() => {
      this._writeTimer = null
      this.flush()
    }, delayMs)
    if (this._writeTimer.unref) this._writeTimer.unref()
  }

  flush() {
    if (!this._dirty || this._data === null) return
    try {
      fs.mkdirSync(DIR, { recursive: true, mode: 0o700 })
      const tmp = `${this._file}.${process.pid}.tmp`
      fs.writeFileSync(tmp, JSON.stringify(this._data), { mode: 0o600 })
      fs.renameSync(tmp, this._file)
      this._dirty = false
      return true
    } catch (_) {
      return false
    }
  }

  close() {
    if (this._writeTimer) {
      clearTimeout(this._writeTimer)
      this._writeTimer = null
    }
    this.flush()
  }

  get path() {
    return this._file
  }
}

module.exports = { Store, DIR }
