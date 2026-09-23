# Aegis — Android

A real BLE tracker detector, not a port. The Node tool in this repo is a
terminal application and does not run on Android, so this is a native Kotlin
app that reuses the parts that actually matter: the tracker signature database,
the MAC-rotation fingerprinting, and the geo-correlation rule.

## The rule that makes it worth installing

Most tracker-detector apps flag anything they see repeatedly. That describes a
tracker in your bumper and it equally describes a neighbour's Tile through a
wall, so those apps mostly generate false alarms and get uninstalled.

This one only reports **FOLLOWING** when the same device has been heard from two
places at least **300 m apart** (adjustable in Settings). Until then it says
PERSISTENT and tells you which piece of evidence is missing — either you have
not travelled far enough yet, or there is no position fix to judge by.

## Getting an APK without a build environment

Every push builds one in CI.

1. Open the **Actions** tab on GitHub
2. Pick the most recent **Android APK** run
3. Download the `aegis-debug-apk` artifact
4. Unzip it and install the `.apk` on your phone

You will need to allow installation from unknown sources. It is a debug build,
signed with the standard debug key.

## Building locally instead

Requires JDK 17 and the Android SDK.

```bash
cd android
gradle assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

No Gradle wrapper is committed — CI installs Gradle itself, which keeps a
binary out of the repository.

## Permissions, and why each one is needed

| Permission | Why |
|---|---|
| Nearby devices (`BLUETOOTH_SCAN`) | The scan itself |
| Precise location | Android requires it for BLE scanning and for reading your own modem's serving cell (the IMSI-catcher checks), and this app genuinely uses the fix — it is what proves a device travelled with you |
| Notifications | The one alert that matters, when something crosses into confirmed-following |
| Foreground service | Scanning has to continue while the screen is off, or it never accumulates enough evidence |
| Wi-Fi state | Reads the system's existing scan results; the app never triggers a Wi-Fi scan of its own |
| NFC | The passive HF tag sweep on the NFC tab |
| Background location | Optional, requested only from Settings → "Resume scanning after reboot". Android only lets a location service start at boot with "Allow all the time"; without it, scanning simply waits until you open the app |

Declining notifications costs you the alert, not the detector — scanning still runs.

Detection data stays on the phone. The one network use is the Map tab, which
downloads map tiles from OpenStreetMap — the tile server sees your IP address and
the area you are viewing, and nothing else.

## Screen on versus screen off

Android suspends an *unfiltered* BLE scan once the screen goes off, and any filter
list is also a whitelist — so a scan built to survive a pocket can only ever find
devices someone already catalogued. That is the wrong trade for the case this app
exists for, which is the unbranded box wired into a wheel well.

So it runs both. Screen on, the scan is unfiltered and anything advertising at all
can be discovered, including hardware with no known signature. Screen off, it falls
back to filters covering the known tracker manufacturers and service UUIDs, which is
what keeps it running at all. A device first seen while the screen was on stays
tracked either way.

The practical consequence: leave the screen on for a minute or two when you first
start a sweep, so unknown hardware gets a chance to be discovered.

## What it detects

AirTag, Apple Find My items, Tile, Samsung SmartTag and SmartTag2, Chipolo,
Pebblebee, Orbit/KeySmart, Nut, Eddystone beacons, and standalone GPS trackers
that advertise a Bluetooth configuration interface.

AirPods, iBeacons, Apple Handoff and Microsoft devices are filtered out, because
otherwise every coffee shop sets off an alert.

## What it cannot detect

- A tracker with no Bluetooth radio — a hardwired GPS/LTE box only transmits on
  cellular uplink. Finding those needs an SDR; see the Node tool in this repo.
- Anything currently asleep. Physical search still finds things RF never will.
- Carrier-side location. Nothing near you transmits, so there is nothing to hear.
