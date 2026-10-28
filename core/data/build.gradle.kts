plugins {
    alias(libs.plugins.kuma.android.library)
    alias(libs.plugins.kuma.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.kirkanos.uptimekuma.core.data"
}

dependencies {
    api(projects.core.domain)
    api(projects.core.model)
    implementation(projects.core.kumaClient)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(projects.core.testing)
}
