# Building Uptime Kuma for Android

## Requirements

| What | Version | Notes |
|---|---|---|
| JDK to launch Gradle | 17 or newer | Only the `./gradlew` launcher runs on it. Android Studio's bundled JBR works. |
| JDK for the build itself | 21 (Temurin) | Pinned in `gradle/gradle-daemon-jvm.properties`. Gradle provisions it automatically into `~/.gradle/jdks` via the foojay resolver if no local JDK 21 is found. |
| Android SDK | platform `android-37`, build-tools `36.0.0` | `local.properties` must contain `sdk.dir=/path/to/Android/sdk` (gitignored; `ANDROID_HOME` also works). |
| Gradle | 9.8.2 | Use the wrapper (`./gradlew`); the distribution checksum is pinned in `gradle/wrapper/gradle-wrapper.properties`. |

Why a JDK 21 daemon? Gradle 9.8 and AGP 9.5 run fine on JDK 25, but detekt 1.23.x embeds an
older Kotlin compiler whose `JavaVersion.parse` chokes on the JetBrains Runtime 25 version string
(`25.0.3+-…`), and Kotlin/KSP tooling in general is only validated up to JDK 21. Pinning the daemon
JVM makes local builds and CI (`actions/setup-java` with Temurin 21) identical. To change it, run
`./gradlew updateDaemonJvm --jvm-version=<n> --jvm-vendor=adoptium`.

## Everyday commands

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS example

./gradlew assembleFullDebug                     # debug APK  -> app/build/outputs/apk/full/debug/app-full-debug.apk
./gradlew assembleFossDebug                     # debug APK without Firebase -> app/build/outputs/apk/foss/debug/app-foss-debug.apk
./gradlew ktlintCheck detekt                    # static analysis (ktlint_official style, detekt config in config/detekt/detekt.yml)
./gradlew ktlintFormat                          # auto-format
./gradlew lintDebug lintFullDebug lintFossDebug # Android lint (libraries: lintDebug, app: one task per flavor)
./gradlew testDebugUnitTest testFullDebugUnitTest testFossDebugUnitTest   # JUnit 5 unit tests of every module (pure JVM modules alias `test` to testDebugUnitTest)
./gradlew assembleRelease                       # minified release APKs of both flavors; unsigned unless signing is configured (see below)
./gradlew assembleRelease -PsignReleaseWithDebugKey=true   # same, signed with the debug key (installable, never distribute)
python3 scripts/check-translations.py           # every string in values/ also in values-de/
python3 scripts/check-android-regex.py          # no unescaped braces in Regex literals (ICU)
python3 scripts/check-foss-apk.py app/build/outputs/apk/foss/debug/app-foss-debug.apk   # no Firebase / gms class in foss
./gradlew :core:kuma-client:integrationTest :core:data:testDebugUnitTest -Pintegration   # against the dev Kuma
```

The full local gate before a commit:

```bash
./gradlew ktlintFormat ktlintCheck detekt lintDebug lintFullDebug lintFossDebug \
  assembleFullDebug assembleFossDebug testDebugUnitTest testFullDebugUnitTest testFossDebugUnitTest \
  assembleRelease -PsignReleaseWithDebugKey=true
python3 scripts/check-android-regex.py && python3 scripts/check-translations.py
python3 scripts/check-foss-apk.py app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

## Product flavors

`:app` has one flavor dimension, `distribution`:

| Flavor | Push transports | Extra dependency | Output |
|---|---|---|---|
| `full` (default) | ntfy (UnifiedPush) and Firebase (user's own project) | `fullImplementation(projects.pushFcm)`: firebase-messaging (BoM 35.1.0), play-services-base | `uptime-kuma-android-<version>.apk` |
| `foss` | ntfy (UnifiedPush) only | none | `uptime-kuma-android-<version>-foss.apk` |

All Firebase code lives in `:push-fcm`. `:push` only defines the `FirebaseTransport` interface with an
optional Hilt binding (`@BindsOptionalOf`), so it compiles without Firebase; `:push-fcm` binds it. In
`foss` the optional is empty: the setup screen skips the transport picker and the repository reports
`FIREBASE_UNAVAILABLE`. No `google-services.json` and no google-services Gradle plugin are used; the user's
Firebase values are entered at runtime (see [docs/push/firebase.md](docs/push/firebase.md#app-setup)).

Verification that `foss` is free of Google code: `scripts/check-foss-apk.py` searches every dex of the
unminified `foss` debug APK for `Lcom/google/firebase/…` and `Lcom/google/android/gms/…` type
descriptors (CI and the release pipeline run it). As of this change it reports 0 for `foss` and 1422 for
`full`; the merged `foss` manifest has no Firebase/gms component, permission or meta-data. `foss` still
contains other `com.google.*` packages that are plain open-source libraries pulled in by the UnifiedPush
connector (Tink `com.google.crypto`, protobuf, gson, errorprone annotations), not Google services.

Both flavors share `applicationId` and the signing key. The baseline profile is generated against
`fullRelease` (`:baselineprofile` uses `missingDimensionStrategy("distribution", "full")`) and merged into
`app/src/main/generated/baselineProfiles` (`baselineProfile.mergeIntoMain = true`), so both flavors ship it.

CI (`.github/workflows/ci.yml`) has three jobs:

| Job | Runs |
|---|---|
| `build` | `scripts/check-translations.py`, `scripts/check-android-regex.py`, then `./gradlew ktlintCheck detekt lintDebug lintFullDebug lintFossDebug assembleFullDebug assembleFossDebug testDebugUnitTest testFullDebugUnitTest testFossDebugUnitTest`, then `scripts/check-foss-apk.py` on the foss debug APK |
| `release-build` | `./gradlew assembleRelease :baselineprofile:assemble -PsignReleaseWithDebugKey=true`; reports both APK sizes, uploads both APKs + R8 mappings |
| `integration` | Kuma as service container (matrix 2.5.8, 2.4.0 allowed to fail), `infra/kuma-seed` setup + seed, then the integration tests |

All jobs cache Gradle via `gradle/actions/setup-gradle` (read-only on branches other than `main`), and a
new push to the same ref cancels the running pipeline.

## Version

`VERSION_NAME` and `VERSION_CODE` in `gradle.properties` are the single source; `app/build.gradle.kts`
reads them. The release pipeline refuses tags other than `v<VERSION_NAME>`.

## Release signing

`app/build.gradle.kts` resolves the release signing config from, in this order:

1. `keystore.properties` in the repository root (gitignored):
   ```properties
   storeFile=/absolute/or/repo-relative/path/release.keystore
   storePassword=...
   keyAlias=...
   keyPassword=...
   ```
2. Environment variables `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

If neither is complete the release build type is left unsigned (the build still succeeds, the APK
cannot be installed) unless `-PsignReleaseWithDebugKey=true` is passed, which signs it with the local
debug keystore (CI uses this on pull requests; the baseline profile tasks need it too, because the
plugin's `nonMinifiedRelease`/`benchmarkRelease` build types copy the release signing config).

Releases are built by **Woodpecker**, not by GitHub Actions, so GitHub never holds the release key
(same approach as the maintainer's other apps):

- `signing/release.jks.enc`: the release keystore (PKCS12, alias `uptimekuma`, RSA 4096, valid
  30 years), sops-encrypted (binary) for the maintainer's age recipient.
- `signing/signing.env.enc`: `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (sops, dotenv).
- `.woodpecker/release.yml`, on tags `vX.Y.Z`: checks the tag against `VERSION_NAME`, decrypts both
  files with the Woodpecker secret `sops_age_key`, runs `./gradlew assembleRelease assembleFossDebug`
  with `KEYSTORE_FILE` and the three values from `signing.env`, deletes the decrypted key, verifies the
  signatures with `apksigner`, runs `scripts/check-foss-apk.py`, and publishes
  `uptime-kuma-android-<version>.apk` (`full`) and `uptime-kuma-android-<version>-foss.apk`, each with a
  `.sha256` file and its R8 mapping, as a GitHub release (`woodpeckerci/plugin-release`, secret
  `github_release_token`: fine-grained token for this repository, *Contents: read and write*).
  The repository must be *trusted* in Woodpecker (the Gradle home is a host volume).

Certificate SHA-256: `7A:11:2B:64:2B:2D:6F:47:74:EC:1B:3F:41:DC:83:8D:B9:78:3F:E6:73:FB:5F:09:C0:CD:C1:C3:F6:7D:A7:3D`.

Decrypt locally (needs the age key): `sops --decrypt --input-type binary --output-type binary
signing/release.jks.enc > release.jks`. **Do not lose the key**: Android installs an update only with the
same signature. A copy belongs in the password manager.

## Project layout

```
build-logic/                  included build with the kuma.* convention plugins (Kotlin DSL)
gradle/libs.versions.toml     single source of truth for all versions
config/detekt/detekt.yml      detekt rules (ktlint handles formatting; style in .editorconfig)
config/compose/               Compose compiler stability configuration
app/                          application module (net.kirkanos.uptimekuma)
core/model, core/domain       pure Kotlin/JVM (kuma.jvm.library)
core/kuma-client              pure Kotlin/JVM Socket.IO + kotlinx.serialization client
core/data                     Android library: repositories, Room, DataStore, Hilt bindings
core/ui                       Android library: Material 3 theme (KumaTheme) and shared composables
core/testing                  pure Kotlin/JVM test fixtures (fakes, JUnit 5 extensions)
feature/*                     Compose feature modules (kuma.android.feature)
push/                         push setup (both transports), UnifiedPush receiver, alert handling, WorkManager
push-fcm/                     Firebase transport (FirebaseMessagingService, runtime FirebaseApp init); `full` flavor only
widget/                       Glance app widget
baselineprofile/              baseline profile generator + startup macrobenchmarks (see docs/performance.md)
scripts/                      repository tooling (translation check)
fastlane/metadata/android/    store/F-Droid listing texts (en-US, de-DE)
```

Convention plugins: `kuma.android.application`, `kuma.android.library`, `kuma.android.library.compose`,
`kuma.android.feature` (library + Compose + Hilt + Navigation + core deps), `kuma.android.hilt`,
`kuma.jvm.library`, `kuma.quality` (applied by all of the above).

Shared settings: `compileSdk = targetSdk = 37`, `minSdk = 31`, Java 17 bytecode, Kotlin via AGP's
built-in Kotlin support (no `kotlin-android` plugin), JUnit 5 through Gradle's JUnit Platform
support, configuration cache and build cache enabled.

## Versions (as of 2026-10-28)

| Component | Version |
|---|---|
| Gradle | 9.8.2 |
| Android Gradle plugin | 9.5.0 (released 2026-10-28; fall back to 9.4.1 if it misbehaves) |
| Kotlin / Compose compiler / serialization plugin | 2.4.21 |
| KSP | 2.3.12 |
| Compose BOM | 2026.09.00 (material-icons-extended pinned separately to 1.7.8) |
| Hilt | 2.60.1, androidx.hilt 1.4.0 |
| Room | 2.8.5 |
| WorkManager | 2.12.0 |
| Glance | 1.2.0 |
| Navigation Compose | 2.10.2 |
| DataStore | 1.3.0 |
| Lifecycle | 2.11.0 |
| Activity Compose | 1.13.0 |
| Core KTX / Splashscreen | 1.19.1 / 1.2.0 |
| kotlinx.coroutines / serialization / datetime | 1.11.0 / 1.12.0 / 0.8.0 |
| OkHttp | 5.5.1 |
| socket.io-client | 2.1.2 |
| UnifiedPush connector | 3.3.5 |
| Firebase BoM (`full` only) | 35.1.0 (firebase-messaging 26.0.1), play-services-base 18.11.1 |
| Vico charts | 3.3.1 |
| JUnit | 6.1.3 |
| MockK / Turbine / Robolectric | 1.14.11 / 1.2.1 / 4.17 |
| detekt / ktlint-gradle / ktlint | 1.23.8 / 14.2.0 / 1.8.0 |
| foojay toolchain resolver | 1.0.0 |

## Known caveats

- AGP 9 "new DSL": the `CommonExtension` interface no longer has `defaultConfig { }`-style block
  functions (build scripts get them as generated accessors). Convention plugins therefore use
  `defaultConfig.apply { }`.
- `--warning-mode all` shows two deprecations that come from third-party plugins, not from this
  build: `ReportingExtension.file(String)` (removed in Gradle 10) and `Configuration.setVisible`
  (removed in Gradle 11). Expect fixes in detekt / ktlint-gradle releases.
- "Unable to strip the following libraries ..." during `assembleDebug` only means no NDK is
  installed; native libs are packaged unstripped in debug builds. Harmless.
- ktlint's worker prints `sun.misc.Unsafe` warnings on JDK 21+; they are noise from its embedded
  Kotlin compiler.
- Renovate (`renovate.json`) groups AndroidX, Kotlin/KSP, Hilt and AGP updates and runs weekly.
