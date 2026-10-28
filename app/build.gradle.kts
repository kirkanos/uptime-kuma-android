import java.util.Properties

plugins {
    alias(libs.plugins.kuma.android.application)
    alias(libs.plugins.kuma.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing. Resolved from, in order:
 *  1. `keystore.properties` in the repo root (gitignored) with keys
 *     storeFile, storePassword, keyAlias, keyPassword
 *  2. environment variables KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD (CI)
 * If neither is present the release build is unsigned (assembleRelease still works).
 */
val keystoreProperties: Properties? =
    rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }
    }

fun signingValue(
    propertyKey: String,
    envKey: String,
): String? =
    keystoreProperties?.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(envKey).orNull?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "KEY_PASSWORD")
val hasReleaseSigning =
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

android {
    namespace = "net.kirkanos.uptimekuma"

    defaultConfig {
        applicationId = "net.kirkanos.uptimekuma"
        versionCode = 1
        versionName = "0.0.1"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(checkNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }

    buildFeatures {
        buildConfig = true
    }

    dependenciesInfo {
        // Keep the APK free of Google-specific dependency metadata (reproducible, F-Droid friendly).
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(projects.feature.onboarding)
    implementation(projects.feature.monitors)
    implementation(projects.feature.maintenance)
    implementation(projects.feature.statuspage)
    implementation(projects.feature.notifications)
    implementation(projects.feature.settings)

    implementation(projects.core.model)
    implementation(projects.core.domain)
    implementation(projects.core.kumaClient)
    implementation(projects.core.data)
    implementation(projects.core.ui)
    implementation(projects.push)
    implementation(projects.widget)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)

    // TODO(merge): remove together with TemporaryBindingsModule once :core:data/:push bind the repositories.
    // The fakes only need model/domain/coroutines; keep test frameworks out of the APK.
    implementation(projects.core.testing) {
        exclude(group = "io.mockk")
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.junit.platform")
        exclude(group = "app.cash.turbine")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-test-jvm")
    }

    testImplementation(projects.core.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
