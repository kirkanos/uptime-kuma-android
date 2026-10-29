/*
 * Baseline profile generator + startup macrobenchmark for :app. Needs a device (API 33+ or rooted 28+)
 * and a reachable Uptime Kuma server, see docs/performance.md:
 *
 *   ./gradlew :app:generateReleaseBaselineProfile -PsignReleaseWithDebugKey=true \
 *       -Pandroid.testInstrumentationRunnerArguments.kumaUrl=http://localhost:3001 \
 *       -Pandroid.testInstrumentationRunnerArguments.kumaUser=admin \
 *       -Pandroid.testInstrumentationRunnerArguments.kumaPassword=...
 *
 * Nothing here runs during a normal build or in CI (no device there); CI only compiles it.
 */
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
    id("kuma.quality")
}

android {
    namespace = "net.kirkanos.uptimekuma.baselineprofile"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        // Profile collection needs API 33+ without root; startup benchmarks run from 31 (the app's minSdk).
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // A connected device or emulator; the dev Kuma is reachable through `adb reverse tcp:3001 tcp:3001`.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
