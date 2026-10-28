import com.android.build.api.dsl.ApplicationExtension
import net.kirkanos.uptimekuma.buildlogic.configureAndroidCompose
import net.kirkanos.uptimekuma.buildlogic.configureKotlinAndroid
import net.kirkanos.uptimekuma.buildlogic.intVersion
import net.kirkanos.uptimekuma.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** The single application module: Android app + Compose + quality tooling. */
class KumaAndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jetbrains.kotlin.plugin.compose")
                apply("kuma.quality")
            }

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                configureAndroidCompose(this)
                defaultConfig.targetSdk = libs.intVersion("targetSdk")
                testOptions.animationsDisabled = true
                packaging {
                    resources.excludes += setOf(
                        "/META-INF/{AL2.0,LGPL2.1}",
                        "/META-INF/LICENSE.md",
                        "/META-INF/LICENSE-notice.md",
                    )
                }
            }
        }
    }
}
