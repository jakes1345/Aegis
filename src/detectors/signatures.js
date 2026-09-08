'use strict'

// BLE tracker signatures based on public reverse engineering:
// OpenHaystack, Wireshark captures, vendor documentation.

const SIGNATURES = [
  // ── Apple FindMy ecosystem ────────────────────────────────────────────────

  {
    id: 'airtag',
    name: 'Apple AirTag',
    brand: 'Apple',
    threat: 'CRITICAL',
    notes: 'Apple FindMy tracker. CR2032 battery. Magnetic, can be hidden in wheel wells, bumpers, bags.',
    match(adv) {
      const m = adv.manufacturerData
      if (!m || m.length < 4) return false
      const company = m[0] | (m[1] << 8)
      // Company 0x004C, type 0x12 (FindMy), subtype 0x19 (AirTag-specific length)
      return company === 0x004C && m[2] === 0x12 && m[3] === 0x19
    },
  },
  {
    id: 'findmy_thirdparty',
    name: 'Apple Find My Tracker',
    brand: 'Apple / Third-party',
    threat: 'HIGH',
    notes: 'Find My network item — Chipolo ONE Spot, Pebblebee Clip, Invoxia, Motorola, or similar. Same Apple network as AirTag.',
    match(adv) {
      const m = adv.manufacturerData
      if (!m || m.length < 4) return false
      const company = m[0] | (m[1] << 8)
      return company === 0x004C && m[2] === 0x12 && m[3] !== 0x19
    },
  },

  // ── Tile network ─────────────────────────────────────────────────────────

  {
    id: 'tile',
    name: 'Tile Tracker',
    brand: 'Tile / Life360',
    threat: 'HIGH',
    notes: 'Tile crowdsourced network. All Tile users passively detect this in range and report to Tile. ~30m BLE range.',
    match(adv) {
      const uuids = (adv.serviceUuids || []).map(u => u.toLowerCase().replace(/-/g, ''))
      if (uuids.some(u => u.includes('feed'))) return true
      const sd = adv.serviceData || []
      return sd.some(s => s && s.uuid && s.uuid.toLowerCase().replace(/-/g, '').includes('feed'))
    },
  },

  // ── Samsung SmartThings ───────────────────────────────────────────────────

  {
    id: 'samsung_smarttag',
    name: 'Samsung SmartTag / SmartTag+',
    brand: 'Samsung',
    threat: 'HIGH',
    notes: 'Samsung SmartThings FindMy. Detected by any Galaxy phone running SmartThings.',
    match(adv) {
      const uuids = (adv.serviceUuids || []).map(u => u.toLowerCase().replace(/-/g, ''))
      return uuids.some(u => u.includes('fd5a'))
    },
  },
  {
    id: 'samsung_smarttag2',
    name: 'Samsung SmartTag2',
    brand: 'Samsung',
    threat: 'HIGH',
    notes: 'Samsung SmartThings FindMy (gen 2). UWB + BLE. Longer battery life.',
    match(adv) {
      const uuids = (adv.serviceUuids || []).map(u => u.toLowerCase().replace(/-/g, ''))
      return uuids.some(u => u.includes('fd70'))
    },
  },

  // ── Chipolo ───────────────────────────────────────────────────────────────

  {
    id: 'chipolo',
    name: 'Chipolo Tracker',
    brand: 'Chipolo',
    threat: 'HIGH',
    notes: 'Chipolo has its own crowdsourced network and also supports Apple Find My (Chipolo ONE Spot).',
    match(adv) {
      const uuids = (adv.serviceUuids || []).map(u => u.toLowerCase().replace(/-/g, ''))
      return uuids.some(u => u.includes('fe9f') || u.includes('febe') || u.includes('fe2b'))
    },
  },

  // ── Pebblebee ─────────────────────────────────────────────────────────────

  {
    id: 'pebblebee',
    name: 'Pebblebee Tracker',
    brand: 'Pebblebee',
    threat: 'HIGH',
    notes: 'Pebblebee Clip / Card / Tag. Supports Apple Find My network.',
    match(adv) {
      const uuids = (adv.serviceUuids || []).map(u => u.toLowerCase().replace(/-/g, ''))
      return uuids.some(u => u.includes('fe2c') || u.includes('fee7'))
    },
  },

  // ── Orbit / KeySmart ──────────────────────────────────────────────────────

  {
    id: 'orbit',
    name: 'Orbit / KeySmart Tracker',
    brand: 'Orbit / KeySmart',
    threat: 'HIGH',
    notes: 'Orbit Keys tracker. Uses own crowdsourced network and Tile network.',
    match(adv) {
      const uuids = (adv.serviceUuids || []).map(u => u.toLowerCase().replace(/-/g, ''))
      return uuids.some(u => u.includes('fff3') || u.includes('ffe0'))
    },
  },

  // ── Nut / Nutale ─────────────────────────────────────────────────────────

  {
    id: 'nut_tracker',
    name: 'Nut Tracker',
    brand: 'Nut / Nutale',
    threat: 'HIGH',
    notes: 'Nut Find3 / Nut Mini tracker. Crowdsourced Nut network.',
    match(adv) {
      const uuids = (adv.serviceUuids || []).map(u => u.toLowerCase().replace(/-/g, ''))
      return uuids.some(u => u.includes('aa01') || u.includes('aa02'))
    },
  },

  // ── Generic GPS/BLE combo trackers ───────────────────────────────────────

  {
    id: 'generic_gps_ble',
    name: 'GPS Tracker (BLE config)',
    brand: 'Unknown GPS Tracker',
    threat: 'HIGH',
    notes: 'Standalone GPS tracker advertising over BLE for configuration. Common in OBD-II and magnetic mount trackers (TK103, GT06, Coban, Concox, SinoTrack).',
    match(adv) {
      const name = (adv.localName || '').toLowerCase()
      const GPS_NAMES = [
        'gps', 'tracker', 'tk102', 'tk103', 'tk303', 'gt02', 'gt06',
        'gl300', 'gl500', 'st-9', 'st901', 'coban', 'concox', 'sinotrack',
        'meitrack', 'queclink', 'teltonika', 'calamp', 'digital ally',
        'bouncie', 'optimus', 'landairsea', 'brickhouse', 'spytec',
        'americaloc', 'primetracking', 'vyncs', 'linxup', 'samsara',
        'geotab', 'fleetsharp', 'automile',
      ]
      return GPS_NAMES.some(n => name.includes(n))
    },
  },

  // ── iBeacon / Eddystone proximity trackers ────────────────────────────────

  {
    id: 'eddystone',
    name: 'Eddystone Beacon (proximity)',
    brand: 'Google / Custom',
    threat: 'MEDIUM',
    notes: 'Eddystone proximity beacon. Can be used for passive location tracking in stores or covertly.',
    match(adv) {
      const uuids = (adv.serviceUuids || []).map(u => u.toLowerCase().replace(/-/g, ''))
      return uuids.some(u => u.includes('feaa'))
    },
  },
]

// Devices that superficially look like trackers but are not
const BENIGN = [
  {
    id: 'airpods',
    name: 'Apple AirPods / Beats',
    match(adv) {
      const m = adv.manufacturerData
      if (!m || m.length < 3) return false
      const company = m[0] | (m[1] << 8)
      return company === 0x004C && (m[2] === 0x01 || m[2] === 0x07)
    },
  },
  {
    id: 'ibeacon',
    name: 'iBeacon (fixed)',
    match(adv) {
      const m = adv.manufacturerData
      if (!m || m.length < 4) return false
      const company = m[0] | (m[1] << 8)
      return company === 0x004C && m[2] === 0x02 && m[3] === 0x15
    },
  },
  {
    id: 'apple_nearby',
    name: 'Apple device (phone/Mac)',
    match(adv) {
      const m = adv.manufacturerData
      if (!m || m.length < 3) return false
      const company = m[0] | (m[1] << 8)
      // AirDrop, Handoff, Nearby, Airprint, Watch, HomeKit, etc.
      return company === 0x004C && [0x05, 0x09, 0x0B, 0x0D, 0x0E, 0x10, 0x15].includes(m[2])
    },
  },
  {
    id: 'apple_watch',
    name: 'Apple Watch',
    match(adv) {
      const m = adv.manufacturerData
      if (!m || m.length < 3) return false
      const company = m[0] | (m[1] << 8)
      return company === 0x004C && m[2] === 0x0B
    },
  },
  {
    id: 'microsoft_device',
    name: 'Microsoft device',
    match(adv) {
      const m = adv.manufacturerData
      if (!m || m.length < 2) return false
      const company = m[0] | (m[1] << 8)
      return company === 0x0006 // Microsoft
    },
  },
]

module.exports = { SIGNATURES, BENIGN }
