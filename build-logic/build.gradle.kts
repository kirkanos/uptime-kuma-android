import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "net.kirkanos.uptimekuma.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // Putting the Gradle plugins on the compile classpath lets the convention plugins
    // configure them with their typed DSL, and pins the versions used by the whole build
    // (AGP 9 bundles its own KGP; the higher version declared here wins).
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.compiler.gradlePlugin)
    compileOnly(libs.kotlin.serialization.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.ktlint.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "kuma.android.application"
            implementationClass = "KumaAndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "kuma.android.library"
            implementationClass = "KumaAndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "kuma.android.library.compose"
            implementationClass = "KumaAndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "kuma.android.feature"
            implementationClass = "KumaAndroidFeatureConventionPlugin"
        }
        register("androidHilt") {
            id = "kuma.android.hilt"
            implementationClass = "KumaAndroidHiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "kuma.jvm.library"
            implementationClass = "KumaJvmLibraryConventionPlugin"
        }
        register("quality") {
            id = "kuma.quality"
            implementationClass = "KumaQualityConventionPlugin"
        }
    }
}
