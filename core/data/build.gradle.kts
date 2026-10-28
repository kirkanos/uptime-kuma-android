plugins {
    alias(libs.plugins.kuma.android.library)
    alias(libs.plugins.kuma.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.kirkanos.uptimekuma.core.data"
}

ksp {
    // Room schema history (version 1 onwards) is committed under core/data/schemas.
    arg(
        "room.schemaLocation",
        layout.projectDirectory
            .dir("schemas")
            .asFile.absolutePath,
    )
    arg("room.generateKotlin", "true")
}

val fixturesDir: String =
    isolated.rootProject.projectDirectory
        .dir("fixtures/kuma-2.5.8")
        .asFile.absolutePath

// Tests tagged "integration" need the dev Kuma (infra/docker-compose.dev.yml); opt in with -Pintegration.
val runIntegration = providers.gradleProperty("integration").isPresent
val kumaOverrides =
    mapOf("kuma.url" to "KUMA_URL", "kuma.user" to "KUMA_USER", "kuma.password" to "KUMA_PASSWORD")
        .mapNotNull { (property, env) ->
            providers
                .systemProperty(property)
                .orElse(providers.environmentVariable(env))
                .orNull
                ?.let { property to it }
        }.toMap()

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        if (runIntegration) includeTags("integration") else excludeTags("integration")
    }
    if (runIntegration) {
        outputs.upToDateWhen { false }
        testLogging.showStandardStreams = true
    }
    kumaOverrides.forEach { (key, value) -> systemProperty(key, value) }
    systemProperty("kuma.fixtures", fixturesDir)
    inputs.dir(fixturesDir).withPropertyName("kumaFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
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
