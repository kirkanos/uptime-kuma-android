plugins {
    alias(libs.plugins.kuma.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.kirkanos.uptimekuma.feature.settings"
}

dependencies {
    // PushStatusCard (push setup status) on the settings screen.
    implementation(projects.push)
    implementation(libs.kotlinx.serialization.json)
}
