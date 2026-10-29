# Uptime Kuma for Android

Native Android client for a self-hosted [Uptime Kuma](https://github.com/louislam/uptime-kuma) 2.x
instance. Live status, history and management of your monitors, with high-priority alerts delivered
either through your own [ntfy](https://ntfy.sh) server via [UnifiedPush](https://unifiedpush.org)
(no Google services at all) or through Firebase Cloud Messaging of your own Firebase project and a small
relay you host (see [Push options](#push-options)). No tracking.

Status: pre-release (0.1.0 in preparation). License: GPL-3.0-or-later.

## Features

- **Dashboard**: all monitors with live status, the last 100 heartbeats as a bar, 24 h uptime, groups,
  tags, search and filters; updates in real time over Kuma's Socket.IO API while the app is open.
- **Monitor details**: response-time chart (3 h to 7 days), uptime 24 h / 30 d / 1 y, certificate
  expiry, important events, pause / resume / delete.
- **Monitor editor** for all 34 Kuma monitor types (driven by a spec of Kuma's own form), tags, clone.
- **Alerts on your phone**: Kuma sends a webhook to your ntfy server (delivered via UnifiedPush) or to
  your FCM relay (delivered via Firebase Cloud Messaging). Per-monitor mute, recovery and certificate
  alerts, quiet hours, alert history.
- **Maintenance windows**: list, details and editor for every strategy (manual, single, cron,
  recurring).
- **Status pages**: list, rendered view, editor, incidents, and a viewer for any public
  `/status/<slug>` link.
- **Home screen widget** and **Quick Settings tile** with the overall status.
- Login with username/password and 2FA (TOTP); servers with authentication disabled work too.
- Material 3 with dynamic color, dark mode, English and German.

## Screenshots

_Screenshots will be added with the first release (`fastlane/metadata/android/en-US/images/`)._

| Dashboard | Monitor | Alerts | Widget |
|---|---|---|---|
| _tbd_ | _tbd_ | _tbd_ | _tbd_ |

## Requirements

- Android 12 (API 31) or newer.
- **Uptime Kuma 2.x**. Developed and tested against 2.5.8, see [Compatibility](#compatibility).
  1.x servers are rejected at login.
- For push alerts, one of (details in [Push options](#push-options)):
  - a self-hosted **ntfy** server reachable by Kuma and by the phone, and a **UnifiedPush
    distributor** on the phone, normally the ntfy Android app
    ([F-Droid](https://f-droid.org/packages/io.heckel.ntfy/),
    [GitHub](https://github.com/binwiederhier/ntfy-android/releases), Google Play), or
  - **Google Play services** on the phone, your own (free) **Firebase project** and the small **FCM
    relay** from `relay/`, reachable by Kuma (`full` APK only).

  Without push the app still works; you only see changes while it is open.

## Push options

The push setup screen first asks how alerts should reach the device. Both options use the same Kuma
`webhook` notification (one per device, created by the app) with the same JSON body, and the same
alert handling in the app (mutes, quiet hours, history).

| | ntfy (UnifiedPush) | Firebase (own project) |
|---|---|---|
| Path | Kuma -> your ntfy server -> ntfy app -> this app | Kuma -> your relay -> Google FCM -> this app |
| Google services | none | Google Play services required |
| Permanent notification | ntfy shows one for its connection; hide it by turning off that notification channel in ntfy's app settings | none |
| Delivery and battery | very good while ntfy is allowed to run in the background (battery optimization off) | best: FCM high-priority messages wake the app even in Doze, no extra connection |
| You run | an ntfy server | a stateless relay (Docker image, holds your Firebase service account key) |
| You create | ntfy users and a publish token | a Firebase project with an Android app for this package, then enter or import its `google-services.json` values |
| APK | `full` and `foss` | `full` only |

Setting up one option tears down the other one (its Kuma notification is deleted, UnifiedPush is
unregistered or the FCM token deleted). Firebase setup:
[docs/push/firebase-setup.md](docs/push/firebase-setup.md), reference: [docs/push/firebase.md](docs/push/firebase.md). ntfy details: [docs/push/README.md](docs/push/README.md).

## Setup (ntfy)

For the Firebase option follow the step-by-step guide [docs/push/firebase-setup.md](docs/push/firebase-setup.md)
instead (Firebase project, relay, app).

### 1. ntfy server

Run ntfy next to Kuma, behind your reverse proxy. A ready-to-use compose service is in
[docs/push/ntfy-compose-snippet.yml](docs/push/ntfy-compose-snippet.yml); the reasoning behind every
setting is in [docs/push/README.md](docs/push/README.md#4-ntfy-server-setup). Keep
`NTFY_AUTH_DEFAULT_ACCESS=deny-all`.

### 2. ntfy users and token

Two identities: one that Kuma publishes with, one that the phone reads with.

```bash
docker compose exec ntfy sh -c 'NTFY_PASSWORD=... ntfy user add kuma && ntfy access kuma "up*" write-only'
docker compose exec ntfy sh -c 'NTFY_PASSWORD=... ntfy user add app  && ntfy access app  "up*" read-only'
docker compose exec ntfy ntfy token add --label kuma-<device> kuma    # tk_... is entered in this app
```

### 3. ntfy app on the phone

1. Install the ntfy app.
2. Settings -> *Default server*: your ntfy URL (exactly the `NTFY_BASE_URL`).
3. Settings -> *Manage users*: add the `app` user for that server.
4. Allow ntfy to ignore battery optimizations (it keeps the push connection).
5. **Open ntfy once after the push setup in this app.** Android does not let ntfy start its
   background service when it was only registered from the background; until it has been opened
   once, nothing is delivered. The app reminds you of this.

### 4. This app

1. Install the app (below) and log in with your Kuma URL, username, password (and 2FA code).
2. Settings -> *Push notifications*: choose *ntfy (UnifiedPush)* (only asked in the `full` APK), pick
   ntfy as distributor, enter the `tk_...` token, choose
   whether all monitors should alert. The app creates a webhook notification in Kuma named after
   your device and sends a test alert.
3. Optional: per-monitor mute on a monitor's detail page; quiet hours and recovery alerts in Settings.

Logging out removes this device's webhook notification from Kuma and unregisters from UnifiedPush
(or deletes the FCM token).

## Install

Two APKs per release, same package name and signing key (you can switch by installing the other one
over it):

| APK | Push options | Google libraries |
|---|---|---|
| `uptime-kuma-android-<version>.apk` (`full`) | ntfy or Firebase | Firebase Messaging, Play services base (inactive until you set up the Firebase option) |
| `uptime-kuma-android-<version>-foss.apk` (`foss`) | ntfy | none (no `com.google.firebase` / `com.google.android.gms` class in the APK) |

- **GitHub Releases**: download one of the APKs from this repository's Releases page and compare it
  with the `.sha256` file next to it.
- **[Obtainium](https://github.com/ImranR98/Obtainium)**: add this repository's GitHub URL as an app
  source to get updates automatically from the Releases page.
- F-Droid: metadata is prepared in `fastlane/metadata/android/`; not submitted yet (F-Droid would
  build the `foss` flavor).

Release APKs are signed with the project's release key; the signature stays the same across updates.
SHA-256 fingerprint of the signing certificate (check with `apksigner verify --print-certs` or
[AppVerifier](https://github.com/soupslurpr/AppVerifier)):

```
7A:11:2B:64:2B:2D:6F:47:74:EC:1B:3F:41:DC:83:8D:B9:78:3F:E6:73:FB:5F:09:C0:CD:C1:C3:F6:7D:A7:3D
```

## Build from source

Requires a JDK 17+ to launch Gradle (the build itself runs on an auto-provisioned JDK 21) and the
Android SDK (platform 37, build-tools 36.0.0). See [BUILD.md](BUILD.md) for details, signing and CI.

```bash
./gradlew assembleFullDebug assembleFossDebug             # debug APKs -> app/build/outputs/apk/{full,foss}/debug/
./gradlew ktlintCheck detekt lintFullDebug lintFossDebug testDebugUnitTest testFullDebugUnitTest testFossDebugUnitTest
./gradlew assembleRelease -PsignReleaseWithDebugKey=true  # minified release (both flavors), installable, NOT for distribution
```

The version (`VERSION_NAME`, `VERSION_CODE`) lives in `gradle.properties`.

## Compatibility

Every pull request runs the integration tests (`:core:kuma-client:integrationTest` and the
`integration`-tagged `:core:data` tests) against real Kuma containers seeded by `infra/kuma-seed`.

| Kuma | Status | Notes |
|---|---|---|
| 2.5.8 | supported, reference | API docs (`docs/kuma-api/`) and fixtures were recorded from it |
| 2.4.0 | works (CI job allowed to fail) | Everything works, including creating and editing monitors: Kuma 2.4 rejects 2.5-only monitor fields (`ntp*Threshold`, `sshAuthMethod`) with `no column named …`, and the app retries without the reported field. Verified live: 33 of 34 monitor types can be created; the SFTP type only exists from Kuma 2.5.4. |
| 2.0 - 2.3 | untested | logged as "untested 2.x" and allowed |
| 1.x | not supported | rejected at login |

## Privacy

The app has no analytics, no crash reporting and no ads. It talks only to the servers you configure:
your Uptime Kuma instance and, for push, your ntfy server (through the ntfy app) or, with the
Firebase option, Google's FCM servers of your own Firebase project (through Google Play services). With
ntfy nothing leaves the triangle phone - Kuma - ntfy; with Firebase the alert content passes your relay
and Google's FCM. The `foss` APK contains no Google library at all, the `full` APK initializes Firebase
only after you set up the Firebase option (no automatic initialization, FCM auto-init off, no
Analytics). Your Kuma session token, the ntfy token and the relay token are stored encrypted with the
Android Keystore and are excluded from backups.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md). Changes are listed in
[CHANGELOG.md](CHANGELOG.md). Architecture: [docs/architecture.md](docs/architecture.md),
performance and baseline profiles: [docs/performance.md](docs/performance.md).
