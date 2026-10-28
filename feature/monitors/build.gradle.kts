plugins {
    alias(libs.plugins.kuma.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.kirkanos.uptimekuma.feature.monitors"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
