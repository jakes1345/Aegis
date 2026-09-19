'use strict'

const https = require('https')
const fs = require('fs')
const path = require('path')
const zlib = require('zlib')

// IEEE OUI (Organizationally Unique Identifier) lookup.
//
// The IEEE publishes the full assignment database at no cost. We cache a
// compressed copy so the tool works offline after the first run.
// Source: https://maclookup.app/downloads/json-database (CC0 licensed)
// Alternate: https://standards-oui.ieee.org/oui/oui.csv (official, same data)

const CACHE_PATH = path.join(__dirname, '../../.cache/oui.json')
const OUI_URL = 'https://maclookup.app/downloads/json-database'

let _db = null // { 'AA:BB:CC': 'Vendor Name', ... }

function normOui(mac) {
  if (!mac) return null
  const upper = mac.toUpperCase().replace(/[^0-9A-F]/g, '')
  if (upper.length < 6) return null
  return `${upper.slice(0, 2)}:${upper.slice(2, 4)}:${upper.slice(4, 6)}`
}

// Blocking load from cache — call once at startup.
function loadSync() {
  if (_db) return
  try {
    const raw = fs.readFileSync(CACHE_PATH, 'utf8')
    const arr = JSON.parse(raw)
    _db = {}
    for (const row of arr) {
      if (row.macPrefix && row.vendorName) {
        _db[row.macPrefix.toUpperCase()] = row.vendorName
      }
    }
  } catch (_) {
    _db = {}
  }
}

// Returns vendor name or null. O(1) hash lookup after loadSync().
function lookup(mac) {
  if (!_db) loadSync()
  const oui = normOui(mac)
  return oui ? (_db[oui] || null) : null
}

// Download and cache the OUI database. Callback(err).
function download(cb) {
  const dir = path.dirname(CACHE_PATH)
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })

  const tmp = CACHE_PATH + '.tmp'
  const out = fs.createWriteStream(tmp)

  https.get(OUI_URL, { timeout: 30000 }, (res) => {
    if (res.statusCode !== 200) {
      res.resume()
      return cb(new Error(`OUI fetch failed: HTTP ${res.statusCode}`))
    }

    const pipe = res.headers['content-encoding'] === 'gzip'
      ? res.pipe(zlib.createGunzip())
      : res

    pipe.pipe(out)
    out.on('finish', () => {
      fs.rename(tmp, CACHE_PATH, (err) => {
        if (err) return cb(err)
        _db = null // invalidate, will reload on next lookup
        loadSync()
        cb(null)
      })
    })
    pipe.on('error', cb)
  }).on('error', cb).on('timeout', () => cb(new Error('OUI download timeout')))
}

// Refresh if the cache is older than 30 days. Fire and forget — callers are not
// blocked on an update; they use whatever is cached.
function refreshIfStale() {
  try {
    const stat = fs.statSync(CACHE_PATH)
    const ageMs = Date.now() - stat.mtimeMs
    if (ageMs < 30 * 24 * 3600 * 1000) return
  } catch (_) {
    // no cache at all — download now
  }
  download((err) => {
    if (err) process.stderr.write(`[oui] refresh failed: ${err.message}\n`)
  })
}

module.exports = { lookup, download, refreshIfStale, loadSync }
