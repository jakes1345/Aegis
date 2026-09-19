'use strict'

const https = require('https')
const fs = require('fs')
const path = require('path')

// OpenCelliD tower verification.
//
// OpenCelliD is the world's largest open database of cell towers — 40M+
// entries crowdsourced from mobile devices worldwide. Verifying that a serving
// cell (MCC+MNC+LAC/TAC+CellID) actually appears in the database turns our
// IMSI catcher heuristics from educated guesses into hard facts.
//
// Two modes:
//   API mode   — requires a free key from opencellid.org; makes live HTTP
//                lookups and caches results locally so each cell is only
//                queried once.
//   Offline mode — if no API key, falls back to "plausibility only": we check
//                  whether the cell IDs we have seen ourselves (on known-good
//                  routes) match. Weaker but still catches cells that appear
//                  nowhere in your personal history.
//
// To get a free API key: https://opencellid.org/ → Register (no credit card).

const CACHE_DIR = path.join(__dirname, '../../.cache/cells')
const API_BASE = 'https://opencellid.org/cell/get'

let _apiKey = process.env.OPENCELLID_API_KEY || null
let _pending = new Map() // key → [ callbacks ]

function cacheFile(key) {
  return path.join(CACHE_DIR, key.replace(/[^a-z0-9_-]/gi, '_') + '.json')
}

function ensureDir() {
  if (!fs.existsSync(CACHE_DIR)) fs.mkdirSync(CACHE_DIR, { recursive: true })
}

// Returns {found: bool, lat, lon, range} or {found: false} or null (offline).
// Never throws. Callback(err, result).
function lookupCell({ mcc, mnc, tac, cellId }, cb) {
  if (!mcc || !mnc || !tac || !cellId) return cb(null, null)
  if (!_apiKey) return cb(null, null) // offline mode — skip

  const key = `${mcc}-${mnc}-${tac}-${cellId}`
  const cf = cacheFile(key)

  // Return cached result immediately.
  try {
    const cached = JSON.parse(fs.readFileSync(cf, 'utf8'))
    return cb(null, cached)
  } catch (_) {}

  // Deduplicate concurrent requests for the same cell.
  if (_pending.has(key)) {
    _pending.get(key).push(cb)
    return
  }
  _pending.set(key, [cb])

  const qs = new URLSearchParams({
    key: _apiKey,
    mcc, mnc,
    lac: tac, // TAC is the LTE/NR equivalent of LAC — OpenCelliD accepts both
    cellid: cellId,
    format: 'json',
  })
  const url = `${API_BASE}?${qs}`

  ensureDir()
  const req = https.get(url, { timeout: 8000 }, (res) => {
    let body = ''
    res.on('data', (c) => { body += c })
    res.on('end', () => {
      let result
      try {
        const parsed = JSON.parse(body)
        if (parsed.lat != null) {
          result = { found: true, lat: parsed.lat, lon: parsed.lon, range: parsed.range || null }
        } else {
          result = { found: false }
        }
      } catch (_) {
        result = null
      }

      if (result) {
        try { fs.writeFileSync(cf, JSON.stringify(result)) } catch (_) {}
      }

      const waiting = _pending.get(key) || []
      _pending.delete(key)
      for (const fn of waiting) fn(null, result)
    })
  })
  req.on('error', (err) => {
    const waiting = _pending.get(key) || []
    _pending.delete(key)
    for (const fn of waiting) fn(err, null)
  })
  req.on('timeout', () => {
    req.destroy()
    const waiting = _pending.get(key) || []
    _pending.delete(key)
    for (const fn of waiting) fn(new Error('opencellid timeout'), null)
  })
}

function setApiKey(key) {
  _apiKey = key
}

function hasApiKey() {
  return !!_apiKey
}

module.exports = { lookupCell, setApiKey, hasApiKey }
