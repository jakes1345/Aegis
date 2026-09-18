# Track Detect — Android

A real BLE tracker detector, not a port. The Node tool in this repo is a
terminal application and does not run on Android, so this is a native Kotlin
app that reuses the parts that actually matter: the tracker signature database,
the MAC-rotation fingerprinting, and the geo-correlation rule.

## The rule that makes it worth installing

Most tracker-detector apps flag anything they see repeatedly. That describes a
tracker in your bumper and it equally describes a neighbour's Tile through a
wall, so those apps mostly generate false alarms and get uninstalled.

This one only reports **FOLLOWING** when the same device has been heard from two
places at least **300 m apart**. Until then it says PERSISTENT and tells you
which piece of evidence is missing — either you have not travelled far enough
yet, or there is no position fix to judge by.

## Getting an APK without a build environment

Every push builds one in CI.

1. Open the **Actions** tab on GitHub
2. Pick the most recent **Android APK** run
3. Download the `track-detect-debug-apk` artifact
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
| Precise location | Android requires it for BLE scanning, and this app genuinely uses the fix — it is what proves a device travelled with you |
| Notifications | The one alert that matters, when something crosses into confirmed-following |
| Foreground service | Scanning has to continue while the screen is off, or it never accumulates enough evidence |

Nothing leaves the phone. There is no network permission in the manifest at all,
which you can verify yourself.

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
