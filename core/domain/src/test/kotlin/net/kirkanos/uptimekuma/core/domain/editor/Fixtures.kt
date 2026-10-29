package net.kirkanos.uptimekuma.core.domain.editor

import java.io.File

/** Recorded Kuma 2.5.8 payloads (`fixtures/kuma-2.5.8`), as plain Kotlin values. */
object Fixtures {
    private val dir: File =
        System.getProperty("kuma.fixtures")?.let(::File)
            ?: File(System.getProperty("user.dir")).resolve("../../fixtures/kuma-2.5.8").normalize()

    @Suppress("UNCHECKED_CAST")
    fun ack(name: String): Map<String, Any?> =
        JsonValues.toKotlin(JsonValues.parseOrNull(File(dir, "ack/$name.json").readText())) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun request(name: String): Map<String, Any?> = (ack(name)["request"] as List<Any?>).first() as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun monitor(name: String): Map<String, Any?> =
        (ack("getMonitor.$name")["response"] as Map<String, Any?>)["monitor"] as Map<String, Any?>
}
