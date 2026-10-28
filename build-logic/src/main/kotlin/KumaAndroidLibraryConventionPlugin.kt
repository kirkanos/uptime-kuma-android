import com.android.build.api.dsl.LibraryExtension
import net.kirkanos.uptimekuma.buildlogic.configureKotlinAndroid
import net.kirkanos.uptimekuma.buildlogic.intVersion
import net.kirkanos.uptimekuma.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class KumaAndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("kuma.quality")
            }

            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                testOptions.targetSdk = libs.intVersion("targetSdk")
                lint.targetSdk = libs.intVersion("targetSdk")
                // Libraries do not need their own BuildConfig / resource shrinking.
                buildFeatures.buildConfig = false
                defaultConfig.consumerProguardFiles("consumer-rules.pro")
            }
        }
    }
}
