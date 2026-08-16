# WiFi Survey — Android Wi-Fi analyser with room heatmapping

An Android app that measures Wi-Fi signal strength around a room and draws it as a
top-down heatmap of the floor, so you can see exactly where coverage falls apart.

Built with Kotlin and Jetpack Compose. No third-party SDKs, no analytics, no network
access of its own — everything stays on the device.

## What it does

**Survey tab — map the room**
- Set the room's real dimensions, then tap the plan wherever you are standing. Every
  network audible at that moment is recorded at that spot.
- The heatmap redraws as you sample, so you can see gaps forming and go fill them.
- **Walk mode** advances a cursor by one stride per detected step, in the direction
  you are facing, dropping samples automatically. Dead reckoning drifts, so tapping
  the plan re-anchors the cursor.
- Draw walls, or box the room in one tap, to give the map context.
- Long-press a sample dot to delete it.

**Coverage tab — read the result**
- Percentage of floor area at video-call grade (≥ −67 dBm), usable (≥ −75 dBm) and
  dead (< −80 dBm).
- Switch the map between one specific radio (BSSID), a whole network/mesh by SSID, or
  best-available signal.
- Tune the interpolation radius — how far one reading is allowed to speak for.
- Export the heatmap as PNG, the raw readings as CSV, or the whole survey as JSON.
- Save and reload surveys.

**Networks tab — live analysis**
- Every visible AP with RSSI, band, channel, channel width, security, Wi-Fi standard,
  vendor and a rough line-of-sight distance estimate.
- Tap one to watch a live RSSI graph with the −50/−67/−80 dBm thresholds marked.
- Flags open networks and overlapping 2.4 GHz channels.

**Channels tab — find a clear channel**
- Signal-vs-frequency chart per band, with each network drawn across the spectrum it
  actually occupies so overlaps are visible.
- Weighted recommendation for the least congested of 2.4 GHz channels 1, 6 and 11.

## How the heatmap works

Samples are sparse and irregular, so the map is built by inverse-distance weighting
with a bounded search radius (power 2.4, default radius 3.5 m), followed by a 3×3 mean
filter. Cells further than the radius from every sample are left unmapped rather than
invented — the app shows you where it does not know. Iso-contours are traced with
marching squares at the −80/−70/−67/−55 dBm thresholds.

## Building

Requires the Android SDK and JDK 17.

```bash
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. CI (`.github/workflows/build-apk.yml`)
builds both variants on every push and attaches them to a GitHub Release.

## Permissions

| Permission | Why |
| --- | --- |
| `NEARBY_WIFI_DEVICES` (Android 13+) | Android returns an empty scan list without it. Declared `neverForLocation`. |
| `ACCESS_FINE_LOCATION` (Android 12 and below) | Older Android ties scan results to location permission; location services must also be switched on. |
| `ACTIVITY_RECOGNITION` | Step detection for walk mode. Optional — decline it and tap-to-place still works. |

## A note on scan throttling

Android 9 and later cap foreground apps at four Wi-Fi scans per two minutes. The app
works within that: it polls the system's cached scan results continuously (the platform
refreshes them with its own scans) and reads the connected AP's RSSI separately, which
is not throttled. For faster surveying, turn off **Wi-Fi scan throttling** in Developer
options.

## Accuracy

RSSI is a coarse, device-specific measurement — two phones in the same spot can differ
by 5–10 dB, and the reported distance estimate assumes free space, so indoors it always
reads long. Treat the map as a comparison between places in your room, not as absolute
calibrated values. The relative picture — where the signal falls off, and by how much —
is what it is good at.
