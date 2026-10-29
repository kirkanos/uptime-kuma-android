/*
 * Firebase Cloud Messaging transport for push alerts (docs/push/firebase.md).
 *
 * Only the `full` flavor of :app depends on this module (`fullImplementation`), so the `foss` APK contains no
 * Firebase / Google Play services classes. Nothing project-specific is compiled in: no google-services.json and no
 * google-services Gradle plugin; the user's own Firebase values are entered at runtime and the default FirebaseApp
 * is initialized in code.
 */
plugins {
    alias(libs.plugins.kuma.android.library)
    alias(libs.plugins.kuma.android.hilt)
}

android {
    namespace = "net.kirkanos.uptimekuma.push.fcm"
}

dependencies {
    implementation(projects.push)
    implementation(projects.core.model)
    implementation(projects.core.domain)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.play.services.base)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(projects.core.testing)
}
