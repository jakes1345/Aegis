'use strict'

// Known GPS/LTE tracker SSID patterns — these devices create their own WiFi
// hotspot for configuration and sometimes to report location over WiFi.
const TRACKER_SSID_PATTERNS = [
  // Generic GPS / tracker keywords
  /^GPS[-_\s]/i,
  /GPS[-_]?TRACK/i,
  /^TRACKER[-_\s]/i,
  /GPS_DEVICE/i,
  /LOCATOR/i,
  /FINDME/i,
  /FIND[-_]?ME/i,
  /ANTI[-_]?THEFT/i,

  // OBD-II port trackers (plug into car OBD port, often create WiFi AP)
  /^OBD[-_\s]/i,
  /^OBD2[-_\s]/i,
  /^OBDII/i,
  /OBD[-_]?HOTSPOT/i,
  /AUTO[-_]?TRACK/i,
  /CAR[-_]?TRACK/i,
  /VYNCS/i,
  /BOUNCIE/i,
  /LINXUP/i,
  /ZUBIE/i,
  /HUMMINGBIRD/i,

  // Major GPS tracker model series
  /^GL\d{2,3}/i,            // Concox GL300, GL500
  /^TK\d{2,3}/i,            // TK102, TK103, TK303
  /^ST[-_]?\d{3}/i,         // SinoTrack ST-901, ST-907
  /^GT\d{2}/i,              // Coban GT02, GT06
  /^GPS\d{2,3}[-_\s]/i,     // GPS103, GPS305
  /^GV\d{3}/i,              // Queclink GV300, GV500
  /^AT\d{3}/i,              // Meitrack AT1-MT
  /COBAN/i,
  /CONCOX/i,
  /SINOTRACK/i,
  /MEITRACK/i,
  /QUECLINK/i,
  /TELTONIKA/i,
  /CALAMP/i,
  /OPTIMUS[-_]?GPS/i,
  /SPYTEC/i,
  /AMERICALOC/i,
  /PRIMETRACK/i,
  /GEOTAB/i,
  /SAMSARA[-_]/i,
  /LYTX/i,

  // Satellite tracker config hotspots
  /^SPOT[-_]/i,             // SPOT Gen3/Gen4 satellite tracker
  /GARMIN[-_]?INREACH/i,
  /GLOBALSTAR/i,
  /IRIDIUM[-_]?GO/i,
  /BIVY[-_]?STICK/i,        // Bivy Stick satellite messenger

  // Drone/aviation trackers
  /FLIGHTAWARE/i,
  /FANET/i,
]

// MAC OUI prefixes of manufacturers known to make GPS/LTE trackers.
// These are the first 3 octets (OUI) of the BSSID.
const TRACKER_OUI = new Set([
  // Quectel Wireless — makes cellular modules used in many GPS trackers
  '18:19:23',
  // Fibocom — cellular tracker modules
  'AC:7A:42',
  // u-blox — common in professional GPS trackers
  // (most don't have WiFi but some combo modules do)
])

class WiFiAnalyzer {
  analyze(network) {
    const trackerSsid = TRACKER_SSID_PATTERNS.some(p => p.test(network.ssid))
    const trackerOui = this._checkOui(network.bssid)
    const isHidden = !network.ssid || network.ssid === '<hidden>'

    // Hidden networks with strong signal near you are worth watching
    const hiddenThreat = isHidden && network.signal > -70

    const isTracker = trackerSsid || trackerOui

    let threat = 'NONE'
    let threatScore = 0
    let trackerType = null

    if (isTracker) {
      threat = 'HIGH'
      threatScore = 65
      trackerType = trackerSsid ? 'wifi_tracker_ssid' : 'wifi_tracker_oui'
      if (network.signal > -65) threatScore += 10 // very close
    } else if (hiddenThreat) {
      threat = 'UNKNOWN'
      threatScore = 12
      trackerType = null
    }

    return {
      id: `wifi:${network.bssid}`,
      scanType: 'wifi',
      address: network.bssid,
      name: network.ssid || '<Hidden Network>',
      rssi: network.signal,
      channel: network.channel,
      security: network.security,
      trackerType,
      trackerInfo: isTracker ? {
        name: trackerSsid ? 'WiFi GPS Tracker (SSID match)' : 'WiFi Tracker (manufacturer)',
        brand: 'GPS/LTE Tracker',
        threat,
        notes: 'Device creating its own WiFi hotspot — common in OBD-II and standalone GPS trackers that use WiFi for configuration or location reporting.',
      } : null,
      benign: !isTracker && !hiddenThreat,
      threat,
      threatScore,
      distance: this._estimateDistance(network.signal),
    }
  }

  _checkOui(bssid) {
    if (!bssid) return false
    const oui = bssid.substring(0, 8).toUpperCase()
    return TRACKER_OUI.has(oui)
  }

  _estimateDistance(rssi) {
    // Use -30 dBm as typical WiFi TX power reference at 1m
    if (!rssi || rssi === 0) return 'unknown'
    const txPower = -30
    const ratio = rssi / txPower
    if (ratio < 1.0) {
      const d = Math.pow(ratio, 10)
      return d < 0.5 ? '<0.5m' : `~${d.toFixed(1)}m`
    }
    const dist = 0.89976 * Math.pow(ratio, 7.7095) + 0.111
    if (dist > 100) return '>100m'
    return `~${dist.toFixed(0)}m`
  }
}

module.exports = WiFiAnalyzer
