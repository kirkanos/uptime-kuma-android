plugins {
    alias(libs.plugins.kuma.jvm.library)
}

dependencies {
    api(projects.core.model)
    api(projects.core.domain)
    api(libs.kotlinx.coroutines.test)
    api(libs.junit.jupiter.api)
    api(libs.turbine)
    api(libs.mockk)
}
