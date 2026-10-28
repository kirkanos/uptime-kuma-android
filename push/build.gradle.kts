plugins {
    alias(libs.plugins.kuma.android.library)
    alias(libs.plugins.kuma.android.hilt)
}

android {
    namespace = "net.kirkanos.uptimekuma.push"
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.model)

    implementation(libs.unifiedpush.connector)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(projects.core.testing)
}
