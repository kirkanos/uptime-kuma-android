# Changelog

All notable changes to this project are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). The version is defined once in
`gradle.properties` (`VERSION_NAME`); release tags are `v<VERSION_NAME>`.

## [Unreleased]

## [0.2.1] - 2026-10-30

### Added

- Update hint: once a day the app asks GitHub for the latest release and shows a hint on the dashboard
  when a newer version is available (*Download* opens the release page, *Later* hides it until the
  next version). The settings show the available version too and can turn the check off.

## [0.2.0] - 2026-10-30

### Added

- Repeating DOWN alerts: an outage rings again every 10 minutes (Settings → *Repeat while down*: off,
  5, 10, 15, 30 or 60 minutes) until the monitor is back up or you acknowledge the alert, with the new
  *Acknowledge* button or by tapping the notification. Before each reminder the app syncs with Kuma
  and closes the alert if the monitor is no longer down (lost UP push, paused, maintenance, deleted).
  Reminders use an exact alarm, ring in Doze and survive reboots and app updates.

## [0.1.0] - 2026-10-29

First public version.

### Added

- Login to Uptime Kuma 2.x with username/password, TOTP two-factor authentication and servers with
  authentication disabled; URL normalisation, sub-path installations, warning for plain `http`.
  Kuma 1.x is rejected with a clear message.
- Live connection over Kuma's Socket.IO API while the app is visible, closed 30 s after it went to the
  background; automatic re-login with the stored session token, re-login screen when the token is no
  longer valid (e.g. after a password change).
- Offline cache (Room) of monitors, heartbeats, uptime, certificates and notifications; the app opens
  with the last known state.
- Dashboard with status, heartbeat bar, uptime, groups, tags, search and filters.
- Monitor details: response-time chart, uptime 24 h / 30 d / 1 y, certificate expiry, important events,
  pause, resume, delete.
- Monitor editor for all 34 Kuma monitor types, generated from a spec of Kuma's own form, with
  defaults, validation, clone and push URL; tag management with Kuma's colors.
- Push alerts via a self-hosted ntfy server and UnifiedPush: guided setup (distributor, token, test
  alert), webhook notification in Kuma per device, optional for all monitors and as Kuma default for
  new monitors, removal on logout.
- Alert preferences: per-monitor mute (also on the detail page), recovery and certificate alerts, quiet
  hours; alert history of the last 200 alerts.
- Maintenance windows: list, details and editor for manual, single, cron and recurring strategies.
- Status pages: list, rendered view, editor (settings, domains, analytics, groups, monitors),
  incidents, and a public viewer for `/status/<slug>` links without login.
- Home screen widget (Glance) and Quick Settings tile with the overall status, redrawn on changes
  without polling.
- Notification providers: manage all 108 Kuma provider types (generic form from a spec of Kuma's
  dialogs, curated layouts for the 15 most used), test with the unsaved configuration.
- Server administration: general settings, expiry notifications, reverse proxy and Cloudflare tunnel,
  password and two-factor authentication, API keys, proxies, Docker hosts, remote browsers, monitor
  history and database size, server version and update information.
- Settings screen with notifications, administration entries and about.
- Push setup can be postponed during onboarding ("Set up later").
- Second push option "Firebase (own project)" (`full` APK): alerts through a self-hosted stateless
  relay and Firebase Cloud Messaging of the user's own Firebase project, without a permanent
  notification. The four Firebase values are entered or imported from `google-services.json` (system
  file picker), nothing Firebase-specific is compiled in, Firebase is initialized at runtime only once
  configured (no auto-init, no Analytics). Clear errors for missing/outdated Google Play services,
  unreachable FCM, rejected project values and project changes that need an app restart. The setup
  screen asks for the push method first; switching tears the other method down; the settings card shows
  the active method. Same alert handling for both methods (shared message handler).
- Step-by-step Firebase guide (`docs/push/firebase-setup.md`): Firebase project, relay, app, first
  alert, troubleshooting; verified end to end on a Pixel 8 Pro.
- Two APK flavors: `full` (ntfy or Firebase) and `foss` (ntfy only, no Google library at all).
- English and German translations, Material 3 with dynamic color and dark mode.

### Performance and robustness

- Immediate reconnect when the device switches networks or gets connectivity back while the app is in
  the foreground (instead of waiting for the Socket.IO backoff or the ping timeout).
- Monitor list reads only the newest 100 heartbeats per monitor, shares one query pipeline between
  dashboard, detail, widget and tile and throttles updates during event bursts (tested with 500
  monitors x 100 beats).
- Minified release build (R8 full mode, resource shrinking), baseline profile.
- Monitor editor works with Kuma 2.4: fields the older server does not know are dropped and the save
  is retried (33 of 34 types verified live; SFTP exists from Kuma 2.5.4).
- Plain `http` servers and user-installed CA certificates are accepted (self-hosted LAN setups).

### Fixed (found in device tests)

- Release build crashed when opening the monitor editor (spec resource lookup broke after R8
  repackaging) and the notification list (a regex valid on the JVM but rejected by Android's ICU
  engine; CI now checks all regex literals).
- New monitors were not attached to the device's push notification; it is now Kuma's default
  notification when "all monitors" is chosen.
- "Update available" was shown when Kuma's update check reported an older version.
- Setup hint to open the ntfy app once: Android only lets it start its connection from the foreground.

### Infrastructure

- CI: lint, detekt, ktlint, unit tests, translation check, minified release build signed with a
  throwaway key, integration tests against Uptime Kuma 2.5.8 and 2.4.0 containers. Both flavors are
  built, linted and tested; CI fails if the `foss` APK contains a Firebase or Play services class.
- Release pipeline (Woodpecker, release key sops-encrypted in `signing/`, never stored at GitHub)
  builds the signed APKs for `vX.Y.Z` tags (tag must match `VERSION_NAME`) and publishes them as a
  GitHub release: `uptime-kuma-android-<version>.apk` (`full`) and `uptime-kuma-android-<version>-foss.apk`,
  each with a SHA-256 checksum and the R8 mapping file.

[Unreleased]: https://github.com/kirkanos/uptime-kuma-android/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/kirkanos/uptime-kuma-android/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/kirkanos/uptime-kuma-android/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/kirkanos/uptime-kuma-android/releases/tag/v0.1.0
