import com.android.build.api.dsl.LibraryExtension
import net.kirkanos.uptimekuma.buildlogic.configureAndroidCompose
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

class KumaAndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("kuma.android.library")
                apply("org.jetbrains.kotlin.plugin.compose")
            }
            configureAndroidCompose(extensions.getByType<LibraryExtension>())
        }
    }
}
