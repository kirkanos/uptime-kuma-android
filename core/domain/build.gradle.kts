plugins {
    alias(libs.plugins.kuma.jvm.library)
}

dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)
}
