import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import net.kirkanos.uptimekuma.buildlogic.libs
import net.kirkanos.uptimekuma.buildlogic.version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * Static analysis shared by every Kotlin module: ktlint (formatting, `ktlint_official`
 * style from .editorconfig) and detekt (code smells, config/detekt/detekt.yml).
 */
class KumaQualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("org.jlleitschuh.gradle.ktlint")
                apply("io.gitlab.arturbosch.detekt")
            }

            extensions.configure<KtlintExtension> {
                version.set(libs.version("ktlint"))
                android.set(true)
                ignoreFailures.set(false)
                filter {
                    exclude { element -> element.file.path.contains("${java.io.File.separator}build${java.io.File.separator}") }
                }
            }

            extensions.configure<DetektExtension> {
                buildUponDefaultConfig = true
                allRules = false
                parallel = true
                config.setFrom(isolated.rootProject.projectDirectory.file("config/detekt/detekt.yml"))
                basePath = isolated.rootProject.projectDirectory.asFile.absolutePath
            }

            tasks.withType<Detekt>().configureEach {
                jvmTarget = "17"
                reports {
                    html.required.set(true)
                    xml.required.set(false)
                    sarif.required.set(true)
                    md.required.set(false)
                    txt.required.set(false)
                }
            }
        }
    }
}
