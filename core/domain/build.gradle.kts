plugins {
    alias(libs.plugins.kuma.jvm.library)
}

dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)
    // Only the JSON tree API (no compiler plugin): parses spec/monitor-types.json and JSON form fields.
    implementation(libs.kotlinx.serialization.json)
}

// Single source of truth for the monitor editor: the repository-level spec/monitor-types.json is copied at
// build time (not duplicated in git) to a namespaced classpath resource that every consumer loads.
val monitorSpecResources = layout.buildDirectory.dir("generated/monitorSpec")
val copyMonitorSpec by tasks.registering(Sync::class) {
    from(isolated.rootProject.projectDirectory.file("spec/monitor-types.json"))
    into(monitorSpecResources.map { it.dir("net/kirkanos/uptimekuma/core/domain/editor") })
}
sourceSets.named("main") {
    resources.srcDir(files(monitorSpecResources).builtBy(copyMonitorSpec))
}
