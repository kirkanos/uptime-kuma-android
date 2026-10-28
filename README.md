# Uptime Kuma for Android

Native Android client for a self-hosted [Uptime Kuma](https://github.com/louislam/uptime-kuma)
instance. Live status, history and management of your monitors, with high-priority alerts
delivered through your own [ntfy](https://ntfy.sh) server via [UnifiedPush](https://unifiedpush.org).
No Google services, no relay, no tracking.

Status: early development. See `docs/` for architecture and the Uptime Kuma API reference.

License: GPL-3.0-or-later.

## Build

Requires a JDK 17+ to launch Gradle (the build itself runs on an auto-provisioned JDK 21) and the
Android SDK (platform 37, build-tools 36.0.0). See [BUILD.md](BUILD.md) for details, signing and CI.

```bash
./gradlew assembleDebug            # debug APK -> app/build/outputs/apk/debug/
./gradlew ktlintCheck detekt testDebugUnitTest
```
