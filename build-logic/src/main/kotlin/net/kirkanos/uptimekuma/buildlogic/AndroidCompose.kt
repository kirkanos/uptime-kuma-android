package net.kirkanos.uptimekuma.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/** Jetpack Compose via the Kotlin Compose compiler Gradle plugin. */
internal fun Project.configureAndroidCompose(commonExtension: CommonExtension) {
    commonExtension.buildFeatures.compose = true

    dependencies {
        val bom = libs.findLibrary("androidx-compose-bom").get()
        add("implementation", platform(bom))
        add("androidTestImplementation", platform(bom))
        add("implementation", libs.findBundle("compose").get())
        add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
        add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
        add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
    }

    extensions.configure<ComposeCompilerGradlePluginExtension> {
        // Opt in with -PcomposeCompilerReports=true to get stability/metrics reports under build/compose-reports.
        if (providers.gradleProperty("composeCompilerReports").orNull == "true") {
            val reportsDir = layout.buildDirectory.dir("compose-reports")
            reportsDestination.set(reportsDir)
            metricsDestination.set(reportsDir)
        }
        stabilityConfigurationFiles.add(
            isolated.rootProject.projectDirectory.file("config/compose/compose-stability.conf"),
        )
    }
}
