# iTVS Connect

A better, local-first companion for TVS SmartXonnect scooters (Jupiter and similar clusters).

Built from the reverse-engineering work documented in
[overclock98/JupiterRideCompanion](https://github.com/overclock98/JupiterRideCompanion) — without the official app’s login, cloud dependency, or flaky ride tracking.

## v1 goals

- **No login / no backend** — pair over BLE and keep everything on-device
- **Reliable scooter connectivity** — auth handshake, heartbeat ping, auto-reconnect
- **Find My Scooter** — inject the Find-Me flag into the `0x4A` ping packet
- **Live telemetry** — fuel, odometer, economy (km/L), distance-to-empty
- **Auto ride tracking** — ignition ON starts a ride; ignition OFF (+ grace) ends it
- **Per-ride stats** — distance, duration, avg/max speed, approx km/L
- **Button mapping** — tap / multi-tap / long-press → media, volume, assistant, speed dial
- **Call handling** — answer / decline from scooter gestures
- **Cluster notifications** — mirror media + selected app alerts (17-char rows)
- **Parked location history** + saved place bookmarks

### Explicitly deferred to v2

- Turn-by-turn **navigation HUD** on the cluster (Google Maps bridging)

## How auto rides & km/L work

1. When the cluster streams ignition telemetry (`0x11` / `0x18` / `0x19`), the app enters **Ride Mode** and starts GPS sampling.
2. Distance prefers **odometer delta**; GPS is the fallback.
3. Approx **km/L** uses fuel-bar delta × configured tank capacity when fuel drops; otherwise it falls back to the cluster’s reported average fuel economy.
4. Rides shorter than ~50 m and under 60 s are discarded as ignition blips.
5. Everything is stored in a local Room database.

<<<<<<< HEAD
## Install (phone)

Prefer the **release** APK from [GitHub Releases](https://github.com/udayabharathi-t/itvs-connect/releases).  
If Play Protect blocks it, follow [`docs/INSTALL.md`](docs/INSTALL.md) (disable scan temporarily or tap **Install anyway**).

## Build

```bash
./gradlew :app:assembleRelease :app:testDebugUnitTest
=======
## Build

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
>>>>>>> origin/main
```

Requirements: Android SDK 34, JDK 17+, a TVS SmartXonnect scooter for on-device testing.

<<<<<<< HEAD
- Release APK: `app/build/outputs/apk/release/app-release.apk`
- Debug APK: `app/build/outputs/apk/debug/` (may be more aggressively flagged by Play Protect)
=======
Install the debug APK from `app/build/outputs/apk/debug/`.
>>>>>>> origin/main

## Permissions

Bluetooth, location (for auto-connect + ride GPS + parked pins), phone (call gestures), notifications (cluster mirror), foreground service, boot completed.

## Protocol reference

See [`docs/PROTOCOL.md`](docs/PROTOCOL.md) for BLE UUIDs, auth, ping, telemetry offsets, and button silence-gap notes (sourced from JupiterRideCompanion’s RE report).

## License

MIT — see [LICENSE](LICENSE). Protocol research credit: overclock98 / JupiterRideCompanion contributors.
