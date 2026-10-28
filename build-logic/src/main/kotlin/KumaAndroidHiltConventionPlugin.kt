import net.kirkanos.uptimekuma.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Hilt dependency injection with KSP code generation. Only for Android modules. */
class KumaAndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.google.devtools.ksp")
                apply("com.google.dagger.hilt.android")
            }

            dependencies {
                add("implementation", libs.findLibrary("hilt-android").get())
                add("ksp", libs.findLibrary("hilt-compiler").get())
                add("testImplementation", libs.findLibrary("hilt-android-testing").get())
                add("kspTest", libs.findLibrary("hilt-compiler").get())
            }
        }
    }
}
