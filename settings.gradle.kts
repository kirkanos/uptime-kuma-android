// The build runs on a JDK 21 daemon regardless of the JDK that launches ./gradlew
// (see gradle/gradle-daemon-jvm.properties). The foojay resolver lets Gradle
// download a matching JDK automatically when none is installed.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "uptime-kuma-android"

// Allows `projects.core.model` instead of `project(":core:model")` in build files.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:model")
include(":core:domain")
include(":core:kuma-client")
include(":core:data")
include(":core:ui")
include(":core:testing")
include(":feature:onboarding")
include(":feature:monitors")
include(":feature:maintenance")
include(":feature:statuspage")
include(":feature:notifications")
include(":feature:settings")
include(":push")
// Firebase transport; only the `full` app flavor depends on it, `foss` stays free of Google libraries.
include(":push-fcm")
include(":widget")
include(":baselineprofile")
