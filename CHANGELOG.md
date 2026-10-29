# Changelog

All notable changes to this project are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). The version is defined once in
`gradle.properties` (`VERSION_NAME`); release tags are `v<VERSION_NAME>`.

## [Unreleased] - 0.1.0

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
- Settings screen with notifications, administration entries and about.
- English and German translations, Material 3 with dynamic color and dark mode.

### Performance and robustness

- Immediate reconnect when the device switches networks or gets connectivity back while the app is in
  the foreground (instead of waiting for the Socket.IO backoff or the ping timeout).
- Monitor list reads only the newest 100 heartbeats per monitor, shares one query pipeline between
  dashboard, detail, widget and tile and throttles updates during event bursts (tested with 500
  monitors x 100 beats).
- Minified release build (R8 full mode, resource shrinking), baseline profile infrastructure.

### Infrastructure

- CI: lint, detekt, ktlint, unit tests, translation check, minified release build signed with a
  throwaway key, integration tests against Uptime Kuma 2.5.8 and 2.4.0 containers.
- Release workflow builds the signed APK for `vX.Y.Z` tags (tag must match `VERSION_NAME`) and
  publishes it with a SHA-256 checksum and the R8 mapping file.

[Unreleased]: https://keepachangelog.com/en/1.1.0/
