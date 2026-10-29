# Contributing

Thanks for helping! Bug reports, translations and pull requests are welcome.

## Before you start

- For anything larger than a small fix, open an issue first so we can agree on the approach.
- The app talks to Uptime Kuma 2.x over its (undocumented) Socket.IO API. Read
  [docs/kuma-api/README.md](docs/kuma-api/README.md) first; it lists the pitfalls of the real server.
- Architecture and module rules: [docs/architecture.md](docs/architecture.md) and [BUILD.md](BUILD.md).
  Features never depend on each other or on `:core:data`; they use the interfaces in `:core:domain`.

## Development setup

1. JDK 17+ to launch Gradle, Android SDK platform 37 (see BUILD.md), `local.properties` with `sdk.dir`.
2. A local Kuma for manual and integration tests: `docker compose -f infra/docker-compose.dev.yml up -d`,
   then `cd infra/kuma-seed && npm ci && node setup.mjs && node seed.mjs` (see infra/README.md).

## Checks

Run what CI runs before you push:

```bash
./gradlew ktlintFormat ktlintCheck detekt lintDebug assembleDebug testDebugUnitTest
python3 scripts/check-translations.py
./gradlew assembleRelease -PsignReleaseWithDebugKey=true          # R8 problems
./gradlew :core:kuma-client:integrationTest :core:data:testDebugUnitTest -Pintegration   # needs the dev Kuma
```

## Guidelines

- Kotlin official style (ktlint), no detekt findings, no new lint warnings.
- Unit tests (JUnit 5, Turbine, fakes from `:core:testing`) for every ViewModel and for JSON handling
  against the recorded fixtures in `fixtures/kuma-2.5.8`.
- Every user-visible string in `values/strings.xml` **and** `values-de/strings.xml`; the translation
  check fails otherwise. Content descriptions for icons that carry meaning, 48 dp touch targets.
- Compose previews for new screens (light and dark).
- No tracking, analytics, crash reporters or Google services. Network access only to the user's
  Kuma and ntfy servers.
- Commit messages: short imperative subject, body explains why.

## Translations

Copy the keys from `values/strings.xml` of a module into `values-<lang>/strings.xml`.
`python3 scripts/check-translations.py --locale <lang> --warn-only` lists what is missing.

## Releases (maintainers)

1. Update `VERSION_NAME` / `VERSION_CODE` in `gradle.properties`, move the `Unreleased` section of
   CHANGELOG.md to the new version, add `fastlane/metadata/android/*/changelogs/<versionCode>.txt`.
2. Tag `vX.Y.Z` (must equal `VERSION_NAME`) and push the tag; the Woodpecker release pipeline (`.woodpecker/release.yml`) does the rest.
