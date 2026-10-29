# Performance, release build and baseline profile

## Release build (R8)

- `isMinifyEnabled` + `isShrinkResources`, R8 **full mode** (`android.enableR8.fullMode=true` in
  `gradle.properties`, AGP's default since 8.0). Keep rules: `app/proguard-rules.pro`; most libraries
  (Hilt, WorkManager, Room, Glance, Navigation, kotlinx.serialization, OkHttp) ship their own.
- `org.json` is excluded from the APK: Android has it in the framework (the boot class path wins
  anyway); socket.io-client only needs the jar on the plain JVM (unit tests keep it).
- CI job `release-build` runs `./gradlew assembleRelease -PsignReleaseWithDebugKey=true` on every pull
  request and uploads the APK plus `mapping.txt` / `usage.txt` / `configuration.txt`, so problems with
  stripped classes show up before a release tag.
- Size (0.1.0, 2026-10-29): **5.7 MiB** APK, of which 4.3 MiB `classes.dex` and 0.7 MiB
  `resources.arsc`.

Checked in `mapping.txt` / `usage.txt`: our `@Serializable` DTOs and navigation routes keep their
`$$serializer`/`Companion`, `KumaDatabase_Impl` and both `@HiltWorker`s keep their names (WorkManager
persists worker class names), manifest components (UnifiedPush service, widget receiver, tile service)
are kept, Tink is only used through its `subtle` classes (no reflection).

## Baseline profile

`:baselineprofile` (`com.android.test` + `androidx.baselineprofile`, Macrobenchmark 1.5) holds:

- `BaselineProfileGenerator`: cold start -> dashboard -> fling the monitor list -> open the first
  monitor -> back, collected as baseline **and** startup profile (DEX layout).
- `StartupBenchmarks`: cold start without compilation vs. with the baseline profile, and dashboard
  scrolling (frame timing).

**Decision: no fake "demo mode" build type.** The generator drives the real release build against a
real Kuma. A demo mode would need replacement Hilt modules for every repository binding in
`:core:data`, `:push` and `:widget` (internal classes spread over several modules), and the fakes in
`:core:testing` drag JUnit/MockK in as `api` dependencies. That would be a second, fake app to keep in
sync, and the profile would then cover fake code paths instead of the real socket/Room/serialization
startup that matters. The dev stack (`infra/docker-compose.dev.yml` + `infra/kuma-seed`) already gives a
deterministic server in one command.

Generate (device or emulator with API 33+, or rooted API 28+; dev Kuma running and seeded):

```bash
adb reverse tcp:3001 tcp:3001
./gradlew :app:generateBaselineProfile -PsignReleaseWithDebugKey=true \
  -Pandroid.testInstrumentationRunnerArguments.kumaUrl=http://localhost:3001 \
  -Pandroid.testInstrumentationRunnerArguments.kumaUser=admin \
  -Pandroid.testInstrumentationRunnerArguments.kumaPassword='devpassword123!'
git add app/src/main/generated/baselineProfiles/
```

The profile is collected from the `full` flavor (`missingDimensionStrategy("distribution", "full")` in
`:baselineprofile`) and merged into `src/main` (`baselineProfile.mergeIntoMain = true`), so the `foss`
APK ships the same profile; rules for Firebase classes that `foss` does not contain are ignored.

The journey logs in through the onboarding UI only when the app is not logged in yet (the instrumented
APK is reinstalled per run), then restarts the process so the profiled start is the normal one.
`-PsignReleaseWithDebugKey=true` is needed without a release key because the plugin's
`nonMinifiedRelease` / `benchmarkRelease` build types copy the release signing config.

Benchmarks (real device recommended, results in `baselineprofile/build/outputs/`):

```bash
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest -PsignReleaseWithDebugKey=true \
  -Pandroid.testInstrumentationRunnerArguments.kumaUrl=... (same arguments)
```

`profileinstaller` is a dependency of the app, so the profile is also applied for sideloaded and
F-Droid/Obtainium installs, not only for Play.

## Large servers (500 monitors)

Measured by `core/data/src/test/.../cache/LargeServerPerformanceTest.kt` (JVM, real Room with the
bundled SQLite driver, data generated from the real 2.5.8 `monitorList` payload; MacBook, 2026-10-29):

| Step (500 monitors x 100 beats) | Time |
|---|---|
| Parse `monitorList` + 500 `heartbeatList` (JSON text -> events) | ~130 ms |
| Write everything to Room (one transaction per event, as the live sync does) | ~170 - 210 ms |
| Build the dashboard overview (queries + join) | ~45 ms |
| 500 live heartbeats (one transaction each) | ~60 ms |
| Beat query, all rows (150 per monitor, steady state) | 75 000 rows, ~21 ms |
| Beat query, newest 100 per monitor (`observeLatest`) | 50 000 rows, ~14 ms |

Phones are several times slower than this, so what matters is how often the work runs. Fixes:

- **Windowed beat query** (`HeartbeatDao.observeLatest`): the ring buffer keeps up to 150 recent +
  500 important beats per monitor (up to 325 000 rows at 500 monitors), the list only shows 100.
  `CROSS JOIN` pins the loop order: per monitor one cutoff lookup + one primary-key range scan. A
  plain JOIN made SQLite scan all beats and evaluate the cutoff per row (4x slower than reading
  everything), a `ROW_NUMBER()` window needed two temp B-trees.
- **One shared, throttled pipeline** (`MonitorOverviewSource`): dashboard, detail, widget and tile
  share one set of Room queries (`shareIn`, 5 s stop timeout). Every table flow is throttled by
  suspending its collector (Room coalesces the invalidations of a burst into one re-query), and the
  join runs on the newest snapshot at most every 250 ms. A server with 500 monitors at 20 s sends ~25
  heartbeats/s plus uptime events; before, each one re-read every heartbeat and rebuilt every row.
