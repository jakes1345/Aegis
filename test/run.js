'use strict'

// Covers the parts that decide whether something gets called a tracker: the
// timing analysis, the distance maths, the burst detector, and the baseband
// parsers. All run without an SDR, a modem or a Bluetooth adapter.

const { haversine, ObservationArea, LocationTrack } = require('../src/analysis/geo')
const { analyze, matchesCommonInterval } = require('../src/analysis/periodicity')
const { SpectrumAnalyzer } = require('../src/analysis/spectrum')
const { fingerprint, IdentityResolver } = require('../src/analysis/identity')
const { parseAndroidRegistry, parseAtOutput, parsePrefixedNumber } = require('../src/scanner/cellular')
const { parseNMEA, nmeaCoord } = require('../src/scanner/gps')

let passed = 0
let failed = 0

function ok(name, condition, detail) {
  if (condition) {
    passed++
    process.stdout.write(`  ok   ${name}\n`)
  } else {
    failed++
    process.stdout.write(`  FAIL ${name}${detail ? ` — ${detail}` : ''}\n`)
  }
}

function near(actual, expected, tolerance) {
  return Math.abs(actual - expected) <= tolerance
}

function section(title) {
  process.stdout.write(`\n${title}\n`)
}

// ── Geo ──────────────────────────────────────────────────────────────────────

section('geo')
{
  const oneDegree = haversine({ lat: 0, lon: 0 }, { lat: 1, lon: 0 })
  ok('one degree of latitude is ~111.19 km',
    near(oneDegree, 111195, 400), `got ${Math.round(oneDegree)} m`)

  ok('null island to nowhere is null', haversine(null, { lat: 1, lon: 1 }) === null)

  const area = new ObservationArea()
  area.add({ lat: 0, lon: 0 })
  area.add({ lat: 0, lon: 0.0005 })      // ~56 m — same place
  ok('fixes within the cluster radius collapse', area.count === 1, `count ${area.count}`)

  area.add({ lat: 0, lon: 0.01 })        // ~1113 m away
  ok('a distant fix opens a new cluster', area.count === 2, `count ${area.count}`)
  ok('span measures the widest separation',
    near(area.span(), 1113, 60), `got ${Math.round(area.span())} m`)
  ok('span past the threshold means it moved with us', area.movedWithUs() === true)

  const near2 = new ObservationArea()
  near2.add({ lat: 0, lon: 0 })
  near2.add({ lat: 0, lon: 0.0015 })     // ~167 m — moved, but not enough
  ok('short separation is not enough to claim following', near2.movedWithUs() === false)

  const track = new LocationTrack()
  ok('no fix means no position', track.hasFix() === false)
  track.update({ lat: 0, lon: 0, speed: 0.2, ts: Date.now() })
  ok('a slow fix is not moving', track.isMoving() === false)
  track.update({ lat: 0, lon: 0.001, speed: 14, ts: Date.now() + 1000 })
  ok('a fast fix is moving', track.isMoving() === true)
}

// ── Periodicity ──────────────────────────────────────────────────────────────

section('periodicity')
{
  const exact = analyze([0, 60000, 120000, 180000, 240000])
  ok('a clean 60 s beacon is periodic', exact.periodic === true)
  ok('and its period is 60 s, not a harmonic of it',
    exact.periodMs === 60000, `got ${exact.periodMs}`)

  // A receiver that hops misses bursts. A gap of 2T is evidence for T.
  const gappy = analyze([0, 60000, 180000, 240000])
  ok('a missed burst still resolves to the true period',
    gappy.periodic === true && gappy.periodMs === 60000, `got ${gappy.periodMs}`)
  ok('occupancy reflects the missed slot',
    near(gappy.occupancy, 0.75, 0.01), `got ${gappy.occupancy}`)

  // Real hardware drifts.
  const jittery = analyze([0, 61000, 119000, 181500, 239000])
  ok('modest jitter does not break detection', jittery.periodic === true)

  const random = analyze([0, 7000, 51000, 58000, 210000, 213000])
  ok('irregular traffic is not called periodic',
    random.periodic === false, `claimed ${random.periodMs} ms`)

  const sparse = analyze([0, 1000])
  ok('two events are not enough to claim anything', sparse.periodic === false)
  ok('and it says why', sparse.reason === 'insufficient_events')

  ok('60 s matches a stock reporting interval', matchesCommonInterval(60000) === 60)
  ok('37 s matches nothing standard', matchesCommonInterval(37000) === null)
}

// ── Spectrum ─────────────────────────────────────────────────────────────────

section('spectrum')
{
  const START_HZ = 824e6
  const STEP_HZ = 10000
  const BINS = 200
  const HOT_BIN = 100

  const flat = () => {
    const bins = new Float32Array(BINS)
    bins.fill(-60)
    return bins
  }

  const analyzer = new SpectrumAnalyzer()

  let lastResult = null
  for (let i = 0; i < 10; i++) {
    lastResult = analyzer.push({
      startHz: START_HZ, stepHz: STEP_HZ, bins: flat(), tsMs: 1000 * i,
    })
  }

  ok('the floor warms up before anything is reported', analyzer.warmedUp === true)
  ok('flat noise produces no signals', lastResult.signals.length === 0,
    `got ${lastResult.signals.length}`)

  const hot = flat()
  hot[HOT_BIN] = -45   // 15 dB above the floor
  const detected = analyzer.push({
    startHz: START_HZ, stepHz: STEP_HZ, bins: hot, tsMs: 11000,
  })

  ok('a burst above the floor is detected', detected.signals.length === 1,
    `got ${detected.signals.length}`)

  if (detected.signals.length === 1) {
    const sig = detected.signals[0]
    const expectedHz = START_HZ + (HOT_BIN + 0.5) * STEP_HZ
    ok('the burst is located at the right frequency',
      near(sig.peakHz, expectedHz, STEP_HZ), `got ${sig.peakHz}, expected ${expectedHz}`)
    ok('its excess over the floor is measured',
      near(sig.excessDb, 15, 1), `got ${sig.excessDb}`)
    ok('a single-bin burst is reported as narrowband',
      sig.bandwidthHz <= STEP_HZ * 2, `got ${sig.bandwidthHz} Hz`)
  }

  // A gain change lifts every bin together and must not look like a signal.
  const lifted = new Float32Array(BINS)
  lifted.fill(-40)
  const drift = analyzer.push({
    startHz: START_HZ, stepHz: STEP_HZ, bins: lifted, tsMs: 12000,
  })
  ok('a broadband gain shift is not mistaken for a transmission',
    drift.signals.length === 0, `got ${drift.signals.length}`)
}

// ── BLE identity across MAC rotation ─────────────────────────────────────────

section('identity')
{
  const base = {
    address: 'aa:bb:cc:dd:ee:01',
    rssi: -60,
    name: 'Unknown Device',
    // Apple Find My: company 0x004C, type 0x12, length 0x19, then a rotating key.
    manufacturerHex: `4c001219${'00'.repeat(20)}`,
    serviceUuids: [],
    txPowerLevel: 12,
  }
  const rotated = {
    ...base,
    address: 'aa:bb:cc:dd:ee:02',
    manufacturerHex: `4c001219${'ff'.repeat(20)}`,  // same device, new key
  }

  ok('the rotating key does not change the fingerprint',
    fingerprint(base) === fingerprint(rotated))

  const different = { ...base, address: 'aa:bb:cc:dd:ee:03', manufacturerHex: '4c000719abcd' }
  ok('a different device type fingerprints differently',
    fingerprint(base) !== fingerprint(different))

  const resolver = new IdentityResolver()
  const first = resolver.resolve(base)
  ok('a new address becomes a new identity', first.rotated === false)

  const again = resolver.resolve(base)
  ok('the same address stays the same identity',
    again.identityId === first.identityId && again.rotated === false)

  // The old address has to go quiet before a new one can be linked to it,
  // otherwise two identical trackers get merged into one.
  resolver.get(first.identityId).lastSeen = Date.now() - 30000
  const linked = resolver.resolve(rotated)
  ok('an address that appears as another goes quiet is linked',
    linked.rotated === true && linked.identityId === first.identityId)
  ok('and both addresses are remembered', linked.addressCount === 2)

  const stillTalking = new IdentityResolver()
  stillTalking.resolve(base)
  const notLinked = stillTalking.resolve(rotated)
  ok('an address still advertising is treated as a separate device',
    notLinked.rotated === false)
}

// ── Cellular parsers ─────────────────────────────────────────────────────────

section('cellular parsers')
{
  ok('0x-prefixed values parse as hex', parsePrefixedNumber('0x1A2B') === 6699)
  ok('plain values parse as decimal', parsePrefixedNumber('12345678') === 12345678)
  ok('garbage parses as null', parsePrefixedNumber('zz') === null)

  const dumpsys = [
    'mCellInfo=[CellInfoLte:{mRegistered=YES mTimeStamp=123 ',
    'CellIdentityLte:{ mCi=12345678 mPci=42 mTac=4660 mMccMnc=310260 } ',
    'CellSignalStrengthLte:{ rssi=-65 rsrp=-95 rsrq=-11 }}, ',
    'CellInfoLte:{mRegistered=NO mTimeStamp=124 ',
    'CellIdentityLte:{ mCi=87654321 mPci=17 mTac=4660 mMccMnc=310260 } ',
    'CellSignalStrengthLte:{ rssi=-99 rsrp=-115 }}]',
  ].join('')

  const android = parseAndroidRegistry(dumpsys)
  ok('android: serving cell id read as decimal',
    android && android.cellId === '12345678', android && android.cellId)
  ok('android: tracking area read as decimal, not hex',
    android && android.tac === '4660', android && android.tac)
  ok('android: network split from mccmnc',
    android && android.mcc === '310' && android.mnc === '260')
  ok('android: technology identified', android && android.rat === 'lte')
  ok('android: RSRP preferred over RSSI',
    android && android.signalDbm === -95, android && String(android.signalDbm))
  ok('android: unregistered entries counted as neighbours',
    android && android.neighbors === 1, android && String(android.neighbors))

  ok('android: unavailable identity is rejected',
    parseAndroidRegistry('CellIdentityLte:{ mCi=2147483647 mTac=2147483647 }') === null)

  const cpsi = parseAtOutput(
    '+CPSI: LTE,Online,310-260,0x1A2B,12345678,257,EUTRAN-BAND4,2175,5,5,-94,-850,-609,14'
  )
  ok('CPSI: hex tracking area decoded', cpsi && cpsi.tac === '6699', cpsi && cpsi.tac)
  ok('CPSI: decimal cell id preserved', cpsi && cpsi.cellId === '12345678', cpsi && cpsi.cellId)
  ok('CPSI: RSRP converted from tenths of a dBm',
    cpsi && cpsi.signalDbm === -85, cpsi && String(cpsi.signalDbm))
  ok('CPSI: network parsed', cpsi && cpsi.mcc === '310' && cpsi.mnc === '260')

  const cereg = parseAtOutput('+CEREG: 2,1,"1A2B","01234567",7\r\n+CSQ: 20,99')
  ok('CEREG: unprefixed hex decoded as hex', cereg && cereg.tac === '6699', cereg && cereg.tac)
  ok('CEREG: cell id decoded as hex',
    cereg && cereg.cellId === '19088743', cereg && cereg.cellId)
  ok('CEREG: access technology mapped', cereg && cereg.rat === 'lte')
  ok('CEREG: CSQ converted to dBm',
    cereg && cereg.signalDbm === -73, cereg && String(cereg.signalDbm))

  ok('unparseable AT output yields nothing', parseAtOutput('OK') === null)
}

// ── NMEA ─────────────────────────────────────────────────────────────────────

section('nmea')
{
  ok('ddmm.mmmm converts to decimal degrees',
    near(nmeaCoord('4807.038', 'N'), 48.1173, 0.0002))
  ok('southern and western hemispheres are negative',
    near(nmeaCoord('4807.038', 'S'), -48.1173, 0.0002))

  const rmc = parseNMEA('$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A')
  ok('RMC position decoded',
    rmc && near(rmc.lat, 48.1173, 0.0002) && near(rmc.lon, 11.5167, 0.0002))
  ok('RMC speed converted from knots to m/s',
    rmc && near(rmc.speed, 11.52, 0.05), rmc && String(rmc.speed))

  ok('a void RMC fix is rejected',
    parseNMEA('$GPRMC,123519,V,4807.038,N,01131.000,E,0,0,230394,,*00') === null)

  const gga = parseNMEA('$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47')
  ok('GGA position decoded', gga && near(gga.lat, 48.1173, 0.0002))
  ok('GGA altitude decoded', gga && near(gga.alt, 545.4, 0.1))

  ok('a GGA with no fix is rejected',
    parseNMEA('$GPGGA,123519,4807.038,N,01131.000,E,0,00,,,M,,M,,*00') === null)

  ok('non-NMEA input is ignored', parseNMEA('hello') === null)
}

// ── Result ───────────────────────────────────────────────────────────────────

process.stdout.write(`\n${passed} passed, ${failed} failed\n`)
process.exit(failed === 0 ? 0 : 1)
