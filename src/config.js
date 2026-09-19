'use strict'

// Tuner capabilities. A sweep is only attempted on bands the hardware can reach.
const SDR_PROFILES = {
  rtlsdr: {
    name: 'RTL-SDR (R820T/R820T2)',
    minHz: 24e6,
    maxHz: 1766e6,
    sweepBw: 2.4e6,
    tool: 'rtl_power',
  },
  'rtlsdr-e4000': {
    name: 'RTL-SDR (E4000)',
    minHz: 52e6,
    maxHz: 2200e6,
    sweepBw: 2.8e6,
    tool: 'rtl_power',
    gaps: [[1100e6, 1250e6]],
  },
  hackrf: {
    name: 'HackRF One',
    minHz: 1e6,
    maxHz: 6000e6,
    sweepBw: 20e6,
    tool: 'hackrf_sweep',
  },
}

// What a tracker actually emits. These are UPLINK bands — the frequencies a
// device transmits on. Downlink (tower -> phone) is useless here: every phone
// in range sees the same downlink. A box bolted to your car is only visible
// when it talks, and it talks on uplink.
const BANDS = [
  {
    id: 'orbcomm_up',
    label: 'ORBCOMM satellite uplink',
    minHz: 148.0e6,
    maxHz: 150.05e6,
    category: 'satellite',
    notes: 'Uplink for ORBCOMM satellite asset trackers — common on trailers, containers and fleet vehicles. Narrowband SDPSK bursts.',
  },
  {
    id: 'ism433',
    label: '433 MHz ISM',
    minHz: 433.05e6,
    maxHz: 434.79e6,
    category: 'ism',
    notes: 'Cheap covert transmitters and short-range RF beacons. Also garage remotes and TPMS, so expect benign traffic.',
  },
  {
    id: 'lte_b12_up',
    label: 'LTE B12/B17 uplink (US 700)',
    minHz: 698e6,
    maxHz: 716e6,
    category: 'cellular',
    notes: 'Low-band LTE / LTE-M uplink. Heavily used by IoT trackers for building penetration.',
  },
  {
    id: 'lte_b13_up',
    label: 'LTE B13 uplink (Verizon 700)',
    minHz: 777e6,
    maxHz: 787e6,
    category: 'cellular',
    notes: 'Verizon upper 700 uplink. Common for US fleet and consumer GPS trackers.',
  },
  {
    id: 'cell_low_up',
    label: 'Cellular low uplink (B5/B20/B8)',
    minHz: 824e6,
    maxHz: 915e6,
    category: 'cellular',
    notes: 'Covers GSM850/LTE B5 (824-849), LTE B20 (832-862) and GSM900/LTE B8 (880-915). The single highest-yield range for hidden vehicle trackers worldwide.',
  },
  {
    id: 'ism868',
    label: '868 MHz ISM (EU)',
    minHz: 863e6,
    maxHz: 870e6,
    category: 'ism',
    notes: 'EU short-range devices and LoRa asset tags.',
  },
  {
    id: 'ism915',
    label: '902-928 MHz ISM / LoRa (US)',
    minHz: 902e6,
    maxHz: 928e6,
    category: 'ism',
    notes: 'US ISM. LoRa/Meshtastic asset tags and long-range covert beacons.',
  },
  {
    id: 'globalstar_up',
    label: 'Globalstar uplink',
    minHz: 1610e6,
    maxHz: 1618.725e6,
    category: 'satellite',
    notes: 'Uplink for SPOT trackers and Globalstar simplex asset units. Reachable by a plain RTL-SDR.',
  },
  {
    id: 'iridium_up',
    label: 'Iridium uplink',
    minHz: 1616e6,
    maxHz: 1626.5e6,
    category: 'satellite',
    notes: 'Uplink for Iridium SBD trackers (Garmin inReach, Bivy, RockSTAR). TDMA bursts ~8.3 ms in 90 ms frames.',
  },
  {
    id: 'inmarsat_up',
    label: 'Inmarsat / BGAN uplink',
    minHz: 1626.5e6,
    maxHz: 1660.5e6,
    category: 'satellite',
    notes: 'Inmarsat and Thuraya terminal uplink. High-end covert and maritime asset tracking.',
  },
  {
    id: 'dcs1800_up',
    label: 'GSM/LTE B3 uplink (DCS 1800)',
    minHz: 1710e6,
    maxHz: 1785e6,
    category: 'cellular',
    notes: 'Primary EU/Asia LTE uplink. Needs a tuner reaching 1785 MHz.',
  },
  {
    id: 'pcs1900_up',
    label: 'GSM/LTE B2/B25 uplink (PCS)',
    minHz: 1850e6,
    maxHz: 1915e6,
    category: 'cellular',
    notes: 'US PCS uplink. Beyond a stock R820T2 — needs E4000, HackRF or Airspy.',
  },
  {
    id: 'lte_b1_up',
    label: 'LTE B1 uplink (2100)',
    minHz: 1920e6,
    maxHz: 1980e6,
    category: 'cellular',
    notes: 'EU/Asia 2100 uplink. Beyond a stock R820T2.',
  },
]

// A single SDR can only listen to sweepBw at once. Watching a wide range means
// hopping, which means missing short bursts. So there are two modes and the
// presets say which band each one parks on.
const PRESETS = {
  vehicle: {
    label: 'Hidden vehicle tracker (default)',
    survey: ['cell_low_up', 'lte_b12_up', 'lte_b13_up'],
    watch: 'cell_low_up',
  },
  satellite: {
    label: 'Satellite tracker uplink',
    survey: ['globalstar_up', 'iridium_up', 'inmarsat_up', 'orbcomm_up'],
    watch: 'iridium_up',
  },
  covert: {
    label: 'Covert RF beacons and bugs',
    survey: ['ism433', 'ism868', 'ism915'],
    watch: 'ism433',
  },
  all: {
    label: 'Everything the tuner can reach',
    survey: BANDS.map(b => b.id),
    watch: 'cell_low_up',
  },
}

const SDR = {
  // Wide sweeps trade frequency resolution for coverage.
  surveyBinHz: 100e3,
  surveyIntegrationSec: 2,
  // Watch mode parks on one sweepBw-wide window for burst timing.
  watchBinHz: 10e3,
  watchIntegrationSec: 1,

  gain: 'auto',

  // Rolling per-bin noise floor.
  floorWindow: 40,
  // A bin must exceed its own floor by this much to count as a detection.
  detectThresholdDb: 8,
  // Detections closer together than this belong to the same transmission.
  eventMergeMs: 4000,
  // Adjacent detected bins are merged into one signal if within this span.
  signalMergeHz: 200e3,
  // Emitters wider than this are broadband noise, not a tracker modem.
  maxTrackerBwHz: 2.5e6,
}

const PERIODICITY = {
  minEvents: 4,
  tolerance: 0.12,
  minConfidence: 0.6,
  // A tracker beacons on a schedule: seconds to hours. Faster is voice/data,
  // slower than this and we cannot separate it from noise in one session.
  minPeriodMs: 5 * 1000,
  maxPeriodMs: 6 * 60 * 60 * 1000,
}

const GEO = {
  // Two observations this far apart prove the emitter physically moved with you.
  followDisplacementM: 300,
  // Fixes closer than this are treated as the same place.
  locationClusterM: 100,
  // Below this speed we are not travelling, so co-presence proves nothing.
  movingSpeedMs: 1.5,
  maxLocationsPerDevice: 60,
}

const CELLULAR = {
  pollIntervalMs: 15 * 1000,
  // A serving cell this much hotter than the historical norm here is suspect.
  signalOutlierDb: 15,
  // Re-registrations to the same cell within this window count as flapping.
  flapWindowMs: 5 * 60 * 1000,
  flapThreshold: 4,
  // A cell that serves us then vanishes inside this window looks mobile.
  ephemeralCellMs: 10 * 60 * 1000,
}

const PERSISTENCE = {
  followThresholdMs: 10 * 60 * 1000,
  followMinAppearances: 5,
  rssiHistoryMax: 60,
  // A MAC change is only linked to a prior identity if the old address went
  // quiet within this window of the new one appearing.
  macRotationGapMs: 3 * 60 * 1000,
  // ...and the signal level has to be comparable.
  macRotationRssiDelta: 12,
}

function bandById(id) {
  return BANDS.find(b => b.id === id) || null
}

function reachable(band, profile) {
  if (!profile) return false
  if (band.minHz < profile.minHz || band.maxHz > profile.maxHz) return false
  for (const [lo, hi] of profile.gaps || []) {
    if (band.maxHz > lo && band.minHz < hi) return false
  }
  return true
}

module.exports = {
  SDR_PROFILES,
  BANDS,
  PRESETS,
  SDR,
  PERIODICITY,
  GEO,
  CELLULAR,
  PERSISTENCE,
  bandById,
  reachable,
}
