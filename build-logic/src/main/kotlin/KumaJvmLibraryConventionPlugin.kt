import net.kirkanos.uptimekuma.buildlogic.configureJUnit5
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Pure Kotlin/JVM module (no Android SDK): fast to compile and to unit test.
 * Also registers a `testDebugUnitTest` alias so `./gradlew testDebugUnitTest`
 * runs these tests alongside the Android modules' ones.
 */
class KumaJvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("org.jetbrains.kotlin.jvm")
                apply("kuma.quality")
            }

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                    allWarningsAsErrors.set(false)
                }
            }

            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
            }

            tasks.register("testDebugUnitTest") {
                group = "verification"
                description = "Alias for 'test' so JVM modules take part in ./gradlew testDebugUnitTest."
                dependsOn("test")
            }

            configureJUnit5()
        }
    }
}
