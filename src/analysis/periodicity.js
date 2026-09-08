'use strict'

const { PERIODICITY } = require('../config')

// A tracker phoning home is periodic. A phone, a laptop, a car key fob are not.
// This is the single strongest discriminator available from RF timing alone,
// and it is why watch mode matters: you cannot measure a period you did not
// have the receiver parked on.
//
// Detections are lossy — the receiver misses bursts — so a gap of 2T or 3T is
// evidence *for* period T, not against it. Candidate periods therefore include
// each observed gap divided by small integers.
function analyze(times, opts = {}) {
  const cfg = { ...PERIODICITY, ...opts }
  const t = Array.from(new Set(times)).sort((a, b) => a - b)

  if (t.length < cfg.minEvents) {
    return { periodic: false, reason: 'insufficient_events', total: t.length }
  }

  const diffs = []
  for (let i = 1; i < t.length; i++) {
    const d = t[i] - t[i - 1]
    if (d > 0) diffs.push(d)
  }
  if (diffs.length < cfg.minEvents - 1) {
    return { periodic: false, reason: 'insufficient_events', total: t.length }
  }

  const candidates = new Set()
  for (const d of diffs) {
    for (let k = 1; k <= 4; k++) {
      const c = Math.round(d / k)
      if (c >= cfg.minPeriodMs && c <= cfg.maxPeriodMs) candidates.add(c)
    }
  }
  if (candidates.size === 0) {
    return { periodic: false, reason: 'no_candidate_period', total: t.length }
  }

  const totalSpan = t[t.length - 1] - t[0]

  let best = null
  for (const T of candidates) {
    const tol = Math.max(1500, T * cfg.tolerance)
    let matched = 0
    let errSum = 0

    for (const d of diffs) {
      const n = Math.round(d / T)
      if (n < 1 || n > 6) continue
      const err = Math.abs(d - n * T)
      // Independent jitter accumulates as sqrt(n) across n skipped slots.
      if (err <= tol * Math.sqrt(n)) {
        matched++
        errSum += err / n
      }
    }

    const confidence = matched / diffs.length

    // Any period T is also "explained" by T/2 with every other slot empty, and
    // by T/4 with three of four empty. Occupancy — how many of the slots a
    // period predicts actually contain a transmission — is what separates the
    // real period from its own harmonics, and it is what stops sparse random
    // timestamps being declared periodic.
    const predictedSlots = totalSpan / T
    const occupancy = predictedSlots > 0
      ? Math.min(1, (t.length - 1) / predictedSlots)
      : 0

    const jitterMs = matched > 0 ? errSum / matched : Infinity
    const rank = confidence * occupancy
    const candidate = {
      periodMs: T, matched, total: diffs.length, confidence, occupancy, jitterMs, rank,
    }

    if (!best || rank > best.rank || (rank === best.rank && jitterMs < best.jitterMs)) {
      best = candidate
    }
  }

  const periodic =
    best.confidence >= cfg.minConfidence &&
    best.matched >= cfg.minEvents - 1 &&
    best.occupancy >= 0.5

  return {
    periodic,
    periodMs: best.periodMs,
    periodSec: Math.round(best.periodMs / 100) / 10,
    matched: best.matched,
    total: best.total,
    confidence: Math.round(best.confidence * 100) / 100,
    occupancy: Math.round(best.occupancy * 100) / 100,
    jitterMs: Math.round(best.jitterMs),
    events: t.length,
  }
}

function describePeriod(ms) {
  if (!ms || !isFinite(ms)) return 'unknown'
  const s = ms / 1000
  if (s < 90) return `${s.toFixed(1)}s`
  const m = s / 60
  if (m < 90) return `${m.toFixed(1)}min`
  return `${(m / 60).toFixed(1)}h`
}

// Reporting intervals that show up in commercial tracker firmware defaults.
// Landing on one of these is corroborating evidence, never proof on its own.
const COMMON_INTERVALS_SEC = [10, 15, 20, 30, 60, 120, 180, 300, 600, 900, 1800, 3600]

function matchesCommonInterval(periodMs) {
  if (!periodMs) return null
  const sec = periodMs / 1000
  for (const c of COMMON_INTERVALS_SEC) {
    if (Math.abs(sec - c) <= Math.max(2, c * 0.1)) return c
  }
  return null
}

module.exports = { analyze, describePeriod, matchesCommonInterval, COMMON_INTERVALS_SEC }
