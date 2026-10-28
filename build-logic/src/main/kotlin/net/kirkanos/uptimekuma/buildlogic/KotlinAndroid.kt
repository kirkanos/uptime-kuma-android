package net.kirkanos.uptimekuma.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Shared configuration for every Android module (application and libraries).
 *
 * Kotlin is compiled by AGP's built-in Kotlin support (AGP >= 9.0), so there is no
 * `org.jetbrains.kotlin.android` plugin. AGP 9's "new DSL" no longer exposes
 * `defaultConfig { }`-style block functions on [CommonExtension] (build scripts get
 * them as generated Kotlin DSL accessors), hence the `.apply { }` idiom here. The Kotlin `jvmTarget` defaults to
 * `compileOptions.targetCompatibility`, which is pinned to Java 17 here.
 */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension) {
    commonExtension.apply {
        compileSdk = libs.intVersion("compileSdk")

        defaultConfig.apply {
            minSdk = libs.intVersion("minSdk")
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        testOptions.unitTests.apply {
            isIncludeAndroidResources = false
            isReturnDefaultValues = true
            // JUnit 5 for local unit tests, using Gradle's built-in JUnit Platform support.
            all { test ->
                test.useJUnitPlatform()
            }
        }

        lint.apply {
            abortOnError = true
            checkDependencies = false
            warningsAsErrors = false
        }
    }

    configureJUnit5()

    dependencies {
        add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
    }
}
