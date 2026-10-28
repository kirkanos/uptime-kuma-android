plugins {
    alias(libs.plugins.kuma.android.library.compose)
}

android {
    namespace = "net.kirkanos.uptimekuma.core.ui"
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
