package net.kirkanos.uptimekuma.buildlogic

import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** JUnit 5 (Jupiter) plus the coroutine/mock/flow testing basics every module gets for free. */
internal fun Project.configureJUnit5() {
    dependencies {
        add("testImplementation", libs.findBundle("unit-test").get())
        add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
    }
}
