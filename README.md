# Aegis

Counter-surveillance for your phone, and a private way to talk.

Aegis answers two questions:

1. **Is something tracking me?** It looks for the trackers that matter: Bluetooth tags,
   Wi-Fi bait networks, fake cell towers (IMSI catchers), and hidden cellular or
   satellite transmitters. It reports "following" only when it can prove the thing
   moved with you, not just that it was nearby.
2. **Can I talk to someone without being watched?** COMMS is end-to-end encrypted
   messaging and voice calls between Aegis apps. It goes through a relay you run
   yourself, uses no phone number, no Google services and no phone carrier, and
   the relay cannot read your messages.

There are two ways to run it:

| | What | Where |
|---|---|---|
| **Android app** | Tracker, Wi-Fi, cell and NFC detection, device health checks, card vault, and COMMS | `android/`, APK on the [Releases](https://github.com/jakes1345/Aegis/releases) page |
| **Desktop tool** (`track-detect`) | Bluetooth, Wi-Fi, SDR radio sweep, cellular and GPS detection from a laptop, including hidden LTE and satellite trackers found by radio sweep | `src/` (Node.js) |

The COMMS relay (`comms-worker/`) and its encryption library (`comms-crypto/`) support the app.

---

## Android app

Android 12 or newer. Download the latest `aegis-vX.Y.Z.apk` from
[Releases](https://github.com/jakes1345/Aegis/releases/latest) and tap it. Updates install
over the previous version.

### Tabs

| Tab | What it does |
|---|---|
| **SCAN** | Bluetooth LE scan for AirTags and Find My items, Tile, SmartTag, Chipolo, Pebblebee and more. Devices are recognised by the parts of their broadcast that don't change, so a tag that keeps changing its address is still recognised as the same device. A device is marked **FOLLOWING** only when it was heard from two places at least 300 m apart; otherwise it stays **PERSISTENT**. Trusted devices can be ignored. |
| **MAP** | Where each suspicious device was seen, on OpenStreetMap. Loading map tiles shows the tile server your IP address and the area you are viewing; nothing else leaves the phone. |
| **LOG** | A lasting timeline of the events that matter: confirmed followers, IMSI-catcher findings, scanned tags, and when scans started and stopped. Can be exported as an evidence report. |
| **CELL** | IMSI-catcher detection. It learns what normal looks like where you actually go, then flags anything unusual: a downgrade to 2G/3G, a cell whose tracking-area code changed, forced re-registration while you're standing still, a signal much stronger than normal, no neighbouring cells, or a cell that appears briefly and then vanishes. It needs no root, no account and no tower database. |
| **NFC** | Identifies NFC and RFID cards and tags (type, UID, payment network). Cards can be stored in an encrypted **card vault**, protected by AES-256-GCM with a key held in the phone's secure hardware and unlocked by fingerprint or PIN, and **replayed** with Host Card Emulation so the phone can act as a stored access card. |
| **WIFI** | Looks for Wi-Fi networks that behave like bait rather than normal infrastructure, while avoiding false alarms on ordinary carrier hotspots and dual-band routers. |
| **DEVICE** | Phone health checks: which apps are using the microphone or camera right now, plus accessibility services, device admins and debug settings that spyware relies on. |
| **COMMS** | Encrypted messages and calls between Aegis apps (below). |

Scanning runs as a foreground service, and can restart after a reboot if you turn that on.
Detection data stays on the phone.

---

## COMMS: encrypted messages and calls

### What you get

- **Messages** end-to-end encrypted with the Olm double ratchet
  ([vodozemac](https://github.com/matrix-org/vodozemac), the library Matrix uses). Each
  message has its own key, with delivered and read receipts.
- **Voice calls** over WebRTC. The keys for the audio are exchanged inside the encrypted
  session, so neither the relay nor a TURN server can listen in or step into the middle.
  An incoming call rings full screen over the lock screen, the caller hears ringback, and
  calls survive network changes (ICE restart). Audio switches to a headset or Bluetooth
  when one is connected, and the screen turns off at your ear.
- **Contacts pinned by key.** Scanning someone's QR code verifies them. Anyone else stays
  "unverified" until you compare safety numbers. If a contact's keys change, you are warned
  and nothing is sent to them until you verify again.
- **An Aegis number**, a random nine-digit ID your relay hands out. **It is not a phone
  number**: it only reaches other Aegis apps on the same relay, and cannot call or text a
  real phone.
- **No Google.** A background connection to your relay rings calls while Aegis is closed.
  Optional [UnifiedPush](https://unifiedpush.org) (for example ntfy) can wake the phone
  instead.

### What the relay can and cannot see

The relay is a Cloudflare Worker you deploy. It stores **sealed envelopes** until the
recipient collects them. It cannot read them, and the sealing also hides who sent each one.
It knows public keys, Aegis numbers, when envelopes are queued, and your push endpoint if
you set one. Every request is signed by the phone's own identity key; there are no
passwords or accounts.

### Joining

- **Running a relay (once):** deploy `comms-worker/` to Cloudflare. It is
  [three commands](comms-worker/README.md#one-time-setup) and fits in the free tier. Then
  open COMMS on your phone and enter the relay URL and the enrollment secret you set.
- **Everyone else, by invite:** COMMS → **INVITE** makes a one-time invite.
  - Someone next to you scans its QR code (COMMS → **SCAN INVITE**).
  - Someone far away gets a link that opens a page with the download and an
    **OPEN IN AEGIS** button.
  - Each invite registers exactly one phone and expires after 7 days.
  - The new person starts with you as a verified contact, and never sees the relay URL or
    its secret.

### Calls between different networks

Two phones on different networks, for example one on mobile data and one on home Wi-Fi,
often cannot reach each other directly. The call then rings, but hangs on "connecting".
To fix that, give the relay a Cloudflare TURN key (free tier), which forwards the call's
encrypted audio when a direct path isn't possible. See
[Calls (optional TURN)](comms-worker/README.md#calls-optional-turn).

### Making calls ring reliably

On each phone:
- In COMMS, allow the **microphone**.
- Allow **full-screen calls** (Android 14+); COMMS settings shows the switch.
- Set Aegis's **battery use to Unrestricted**.
- Keep **Online** on.

COMMS settings → **Diagnostics** keeps a log of connections and calls (never message text),
which you can copy when something goes wrong.

### Not yet

- **No iPhone app.** Aegis is Android only for now.
- **No group chats or video calls.**
- **One phone per Aegis number.** Reinstalling creates a new identity and a new number.

---

## Repository layout

```
android/        the Android app (Kotlin, Jetpack Compose)
  app/src/main/java/com/xat/aegis/
    MainActivity.kt, *Screen.kt     tabs and UI
    ScanService.kt                  foreground scanning service
    analysis/                       cell, IMSI, Wi-Fi, NFC, card vault, device health, geo
    detect/Signatures.kt            BLE tracker signatures
    comms/                          COMMS: relay client, live link, calls, store, wire format
comms-crypto/   Rust library: Olm sessions (vodozemac), sealing, safety numbers; UniFFI → Kotlin
comms-worker/   Cloudflare Worker + Durable Objects: the COMMS relay (see its README)
src/, test/     desktop track-detect tool (Node.js)
```

## Building from source

Android (needs JDK 17, the Android SDK, Rust with `cargo-ndk`, and the NDK; CI does the same
in `.github/workflows/android.yml`):

```bash
cd android
gradle assembleDebug          # builds the Rust crate for every ABI, then the app
gradle :app:testDebugUnitTest # wire-format and invite tests
```

The relay round-trip tests run two simulated phones against a real relay. They are skipped
unless you point them at one:

```bash
AEGIS_RELAY_URL=https://your-relay.workers.dev AEGIS_ENROLL_SECRET=… gradle :app:testDebugUnitTest
```

Crypto library: `cd comms-crypto && cargo test`. Relay: `cd comms-worker && npm run typecheck`.

Releases are built by GitHub Actions when a `release/vX.Y.Z` branch is pushed.

---

## Desktop tool: `track-detect`

The desktop tool goes further than a phone can. A tracker that matters is often a
magnetic or hardwired LTE box in your wheel well that never broadcasts over Bluetooth
at all; it just reports in on a timer. With an RTL-SDR or HackRF, this tool sweeps the
cellular and satellite uplink bands for that kind of transmitter.

```bash
npm install
sudo node src/main.js   # Linux: Bluetooth needs root. Wi-Fi, SDR and cellular do not.
node src/main.js        # macOS
npm test                # analysis checks, no hardware needed
```

Keys: `I` guide · `Tab` panels · `Q` quit

### What it detects

| Layer | Finds | Needs |
|---|---|---|
| Bluetooth LE | AirTag, Find My items, Tile, SmartTag, Chipolo, Pebblebee, Orbit, Nut, GPS trackers with BLE config | Bluetooth adapter, root on Linux |
| WiFi | OBD-II dongles and GPS units that run their own hotspot | `nmcli` or `airport` |
| RF sweep | Hidden cellular and satellite transmitters, by their timing and their movement | RTL-SDR or HackRF |
| Cellular | IMSI catchers / cell-site simulators | `mmcli`, or `adb` with an Android phone |
| Position | Turns "present for a while" into "travelled with you" | gpsd, modem GNSS, or an NMEA device |

### What it cannot detect

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

### The rule about "following"

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

### Options

```
--profile <id>     rtlsdr | rtlsdr-e4000 | hackrf   (default rtlsdr)
--preset <id>      vehicle | satellite | covert | all  (default vehicle)
--band <id>        Lock the SDR to one band in watch mode
--gain <n|auto>    SDR gain
--gps-device <p>   NMEA serial device, e.g. /dev/ttyACM0
--at-device <p>    Modem AT port, e.g. /dev/ttyUSB2
--no-ble --no-wifi --no-sdr --no-cell --no-gps
```

### How the RF sweep works

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

### How IMSI catcher detection works

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

### Where trackers hide in vehicles

RF finds a tracker only while it is transmitting. Search anyway:

wheel wells and inner arches · under bumper covers · OBD-II port and behind the
dash · behind the licence plate · under seats · trunk liner · engine bay near
the firewall · tow hitch · spare tyre well · roof lining on vans

Magnetic cases are the giveaway — a hard rectangular box on flat steel where
nothing should be attached. Follow any wire that does not belong; hardwired
units splice into constant 12 V.

### Desktop layout

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

---

## Legal

Aegis is for checking whether *you* are being tracked, and for talking privately with people
who chose to use it.

- **Radio:** the SDR sweep measures signal energy and timing only; it does not demodulate or
  decode anyone's traffic. Receiving and decoding communications you are not party to is
  illegal in many places. Check local law before operating an SDR.
- **Cellular monitoring** reads your own modem's status.
- **Card replay:** only use it with cards you are entitled to use.

MIT.
