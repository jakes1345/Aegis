'use strict'

// Tracker signatures based on public reverse engineering research:
// OpenHaystack, Apple BLE protocol docs, Tile/Samsung published specs.

const SIGNATURES = [
  {
    id: 'airtag',
    name: 'Apple AirTag',
    brand: 'Apple',
    threat: 'CRITICAL',
    notes: 'Apple FindMy tracker. CR2032 battery. Commonly hidden in wheel wells, bumpers, under seats.',
    match(adv) {
      const m = adv.manufacturerData
      if (!m || m.length < 4) return false
      const company = m[0] | (m[1] << 8)
      // Apple company ID 0x004C, type 0x12 (FindMy), subtype 0x19 (AirTag specific)
      return company === 0x004C && m[2] === 0x12 && m[3] === 0x19
    },
  },
  {
    id: 'findmy_compatible',
    name: 'Apple Find My Tracker',
    brand: 'Apple / Third-party',
    threat: 'HIGH',
    notes: 'Apple Find My network compatible device. Could be AirTag, Chipolo ONE Spot, Pebblebee Clip, or similar.',
    match(adv) {
      const m = adv.manufacturerData
      if (!m || m.length < 3) return false
      const company = m[0] | (m[1] << 8)
      // Apple company ID + FindMy type byte (0x12), but not standard AirTag subtype
      return company === 0x004C && m[2] === 0x12 && m[3] !== 0x19
    },
  },
  {
    id: 'tile',
    name: 'Tile Tracker',
    brand: 'Tile',
    threat: 'HIGH',
    notes: 'Tile tracking network. Crowd-sourced location via Tile app users. Range ~30m BLE.',
    match(adv) {
      const uuids = (adv.serviceUuids || []).map(u => u.toLowerCase().replace(/-/g, ''))
      if (uuids.some(u => u.includes('feed'))) return true
      const sd = adv.serviceData || []
      return sd.some(s => s && s.uuid && s.uuid.toLowerCase().replace(/-/g, '').includes('feed'))
    },
  },
  {
    id: 'samsung_smarttag',
    name: 'Samsung SmartTag',
    brand: 'Samsung',
    threat: 'HIGH',
    notes: 'Samsung SmartThings FindMy network. Active on all Samsung devices running SmartThings.',
    match(adv) {
      const uuids = (adv.serviceUuids || []).map(u => u.toLowerCase().replace(/-/g, ''))
      return uuids.some(u => u.includes('fd5a') || u.includes('fd70'))
    },
  },
  {
    id: 'chipolo',
    name: 'Chipolo Tracker',
    brand: 'Chipolo',
    threat: 'HIGH',
    notes: 'Chipolo tracking device. Non-Find My versions have their own crowdsourced network.',
    match(adv) {
      const uuids = (adv.serviceUuids || []).map(u => u.toLowerCase().replace(/-/g, ''))
      // Chipolo proprietary service UUID
      return uuids.some(u => u.includes('fe9f') || u.includes('febe'))
    },
  },
  {
    id: 'pebblebee',
    name: 'Pebblebee Tracker',
    brand: 'Pebblebee',
    threat: 'HIGH',
    notes: 'Pebblebee tracking device (Clip, Card, Tag).',
    match(adv) {
      const uuids = (adv.serviceUuids || []).map(u => u.toLowerCase().replace(/-/g, ''))
      return uuids.some(u => u.includes('fe2c') || u.includes('fee7'))
    },
  },
]

// Devices that look like trackers but are not - exclude from alerts
const BENIGN = [
  {
    id: 'airpods',
    name: 'Apple AirPods',
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
    name: 'Apple Nearby (iPhone/Mac)',
    match(adv) {
      const m = adv.manufacturerData
      if (!m || m.length < 3) return false
      const company = m[0] | (m[1] << 8)
      // Type 0x10 = Nearby (iPhone unlock handoff), 0x05 = AirDrop, 0x15 = handoff
      return company === 0x004C && [0x05, 0x09, 0x0B, 0x0D, 0x0E, 0x10, 0x15].includes(m[2])
    },
  },
]

module.exports = { SIGNATURES, BENIGN }
