plugins {
    alias(libs.plugins.kuma.android.library.compose)
    alias(libs.plugins.kuma.android.hilt)
}

android {
    namespace = "net.kirkanos.uptimekuma.widget"
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.ui)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(projects.core.testing)
}
