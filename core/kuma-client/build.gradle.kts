plugins {
    alias(libs.plugins.kuma.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.socketio.client)
}

val fixturesDir: String =
    isolated.rootProject.projectDirectory
        .dir("fixtures/kuma-2.5.8")
        .asFile.absolutePath

// Unit tests: fixtures only, no network. Tests tagged "integration" talk to a live Kuma and are excluded.
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("integration") }
    systemProperty("kuma.fixtures", fixturesDir)
    inputs.dir(fixturesDir).withPropertyName("kumaFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
}

// ./gradlew :core:kuma-client:integrationTest  (needs infra/docker-compose.dev.yml's kuma on :3001)
// Override with -Dkuma.url=... -Dkuma.user=... -Dkuma.password=... or KUMA_URL/KUMA_USER/KUMA_PASSWORD.
val integrationTest by tasks.registering(Test::class) {
    description = "Runs the @Tag(\"integration\") tests against a live Uptime Kuma (default http://localhost:3001)."
    group = "verification"
    val testSourceSet = sourceSets.named("test").get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    systemProperty("kuma.fixtures", fixturesDir)
    val overrides = mapOf("kuma.url" to "KUMA_URL", "kuma.user" to "KUMA_USER", "kuma.password" to "KUMA_PASSWORD")
    overrides.forEach { (property, env) ->
        val value = providers.systemProperty(property).orElse(providers.environmentVariable(env)).orNull
        if (value != null) systemProperty(property, value)
    }
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
