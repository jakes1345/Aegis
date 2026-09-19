# track-detect

Find out whether something is actually tracking you.

Detecting an AirTag is the easy case, and it is the least likely thing a
serious person uses. A tracker that matters is a magnetic or hardwired LTE box
in your wheel well that never advertises over Bluetooth at all — it just phones
home on a timer. This tool is built around finding *that*, and around being
honest about the cases where nothing can be found.

## What it detects

| Layer | Finds | Needs |
|---|---|---|
| Bluetooth LE | AirTag, Find My items, Tile, SmartTag, Chipolo, Pebblebee, Orbit, Nut, GPS trackers with BLE config | Bluetooth adapter, root on Linux |
| WiFi | OBD-II dongles and GPS units that run their own hotspot | `nmcli` or `airport` |
| RF sweep | Hidden cellular and satellite transmitters, by their timing and their movement | RTL-SDR or HackRF |
| Cellular | IMSI catchers / cell-site simulators | `mmcli`, or `adb` with an Android phone |
| Position | Turns "present for a while" into "travelled with you" | gpsd, modem GNSS, or an NMEA device |

## What it cannot detect

Worth reading before you trust a quiet screen.

- **Carrier-side location.** Access through the operator, SS7/Diameter, or a
  legal request produces no transmission near you. There is nothing to detect
  and your phone behaves normally.
- **"Satellite tracking"** in the sense people usually mean. Satellites do not
  scan for individuals. What is real — and what this tool looks for — is a
  satellite *uplink transmitter* on your vehicle sending position up to
  Iridium, Globalstar or Inmarsat.
- **Stalkerware on your own phone.** That is a device-integrity problem: check
  installed apps, configuration profiles, and who has your account passwords.
- **A passive logger.** A tracker that records position for later retrieval
  never transmits. Only a physical search finds it.

## The rule about "following"

Persistence proves nothing. A device seen for thirty minutes describes a
tracker in your bumper and equally describes your neighbour's Tile through the
wall.

Only displacement separates them. Nothing is reported as **FOLLOWING** unless
the same emitter was heard from two places at least 300 m apart. That needs a
position source. Without one, devices are reported as **PERSISTENT** and the
interface says why the stronger claim is unavailable.

The same rule applies to RF. A periodic signal on 915 MHz is probably a smart
meter. A periodic signal on 915 MHz that is still there after you drove across
town is on your car.

## Install and run

```bash
npm install

# Linux: BLE needs root. WiFi, SDR and cellular do not.
sudo node src/main.js

# macOS
node src/main.js
```

Keys: `I` guide · `Tab` panels · `Q` quit

Run the checks on the analysis code (no hardware needed):

```bash
npm test
```

## Options

```
--profile <id>     rtlsdr | rtlsdr-e4000 | hackrf   (default rtlsdr)
--preset <id>      vehicle | satellite | covert | all  (default vehicle)
--band <id>        Lock the SDR to one band in watch mode
--gain <n|auto>    SDR gain
--gps-device <p>   NMEA serial device, e.g. /dev/ttyACM0
--at-device <p>    Modem AT port, e.g. /dev/ttyUSB2
--no-ble --no-wifi --no-sdr --no-cell --no-gps
```

## How the RF sweep works

A hidden GPS tracker has to transmit to be useful. When it does, it transmits
in a cellular or satellite **uplink** band. Downlink is useless to sweep —
every phone nearby sees the same tower traffic.

One receiver only hears about 2.4 MHz at a time, so there are two modes:

- **Survey** hops across a whole band. It finds candidates but only catches a
  fraction of individual bursts. The interface shows that fraction rather than
  implying full coverage.
- **Watch** parks on one 2.4 MHz window. Every transmission inside it is seen,
  which is what makes timing analysis possible.

The tool surveys, picks the most suspicious frequency, parks on it long enough
to measure its interval, then goes back to surveying.

Bands swept:

| Range | What lives there |
|---|---|
| 148–150.05 MHz | ORBCOMM uplink — fleet and trailer asset trackers |
| 433 / 868 / 915 MHz | ISM — cheap beacons, LoRa tags |
| 698–716, 777–787 MHz | LTE B12/B13/B17 uplink (US low band) |
| 824–915 MHz | GSM850, LTE B5/B20/B8 uplink — highest yield worldwide |
| 1610–1626.5 MHz | Globalstar and Iridium uplink (SPOT, inReach) |
| 1626.5–1660.5 MHz | Inmarsat / Thuraya uplink |
| 1710–1785 MHz | LTE B3 uplink (EU/Asia) |
| 1850–1980 MHz | PCS and B1 uplink — needs HackRF, beyond a stock RTL-SDR |

Bands your tuner cannot reach are skipped with an explanation instead of
silently producing nothing.

**What marks a signal as a tracker:** a narrowband burst, in an uplink band,
repeating on a fixed interval, still present after you have moved. Stock
firmware defaults cluster at 30 s, 60 s and 5 min, and hitting one of those is
treated as corroboration rather than proof.

## How IMSI catcher detection works

No API key and no tower database. The tool learns what is normal at the places
you actually go and flags departures from it, so early sessions in a new area
are quieter by design while the baseline fills in.

What it flags:

- **Downgrade to 2G/3G** where LTE is normal. 2G has no mutual authentication,
  which is exactly why a catcher wants you there.
- **A cell ID whose tracking area code changed.** A real cell's identity does
  not move.
- **Tracking-area change while stationary.** Forced re-registration is how your
  IMSI gets pulled.
- **A serving cell far stronger** than anything recorded at that location. A
  catcher has to out-shout the real network and stands much closer than a tower.
- **Zero neighbouring cells**, which stops your handset returning to the real
  network.
- **A cell that served you briefly and vanished.** Fixed infrastructure does not
  do that; equipment in a vehicle does.

The baseline lives in `~/.track-detect/cell-baseline.json`.

## Where trackers hide in vehicles

RF finds a tracker only while it is transmitting. Search anyway:

wheel wells and inner arches · under bumper covers · OBD-II port and behind the
dash · behind the licence plate · under seats · trunk liner · engine bay near
the firewall · tow hitch · spare tyre well · roof lining on vans

Magnetic cases are the giveaway — a hard rectangular box on flat steel where
nothing should be attached. Follow any wire that does not belong; hardwired
units splice into constant 12 V.

## Layout

```
src/
  main.js                  orchestration, CLI, survey/watch scheduling
  config.js                band plan, tuner limits, thresholds
  store.js                 persistent baseline (atomic writes)
  scanner/
    ble.js                 noble BLE scan, duplicates allowed for RSSI
    wifi.js                nmcli / airport / iwlist
    sdr.js                 rtl_power / hackrf_sweep, sweep assembly
    gps.js                 gpsd, modem GNSS, NMEA serial
    cellular.js            mmcli, adb dumpsys, AT — serving cell
  detectors/
    signatures.js          BLE tracker signatures
    analyzer.js            BLE match and scoring
    wifi-analyzer.js       tracker SSID and OUI patterns
    imsi-catcher.js        cell anomaly heuristics against the baseline
  analysis/
    spectrum.js            noise floor, burst extraction
    periodicity.js         interval clustering, harmonic rejection
    rf-tracker.js          emitter identity and classification
    geo.js                 haversine, observation area, movement
    identity.js            BLE fingerprint across MAC rotation
    persistence.js         persistent vs following
  ui/dashboard.js          terminal interface
test/run.js                checks for the analysis code
```

## Legal

Intended for checking whether *you* are being tracked. Receiving and decoding
communications you are not party to is illegal in many jurisdictions — this
tool measures signal energy and timing and does not demodulate or decode
traffic. Cellular monitoring reads your own modem's status. Check local law
before operating an SDR.

MIT.
