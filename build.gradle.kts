// Root build file. All real build configuration lives in the convention plugins
// under build-logic/ and is applied per module via the `kuma.*` plugin ids.
// Plugins are listed here with `apply false` so that a single version of each
// is loaded into the root classloader and shared by every module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}
