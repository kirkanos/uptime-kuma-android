package net.kirkanos.uptimekuma.core.kumaclient

import io.socket.client.IO
import java.net.URI

/** Defaults for connecting to an Uptime Kuma server's Socket.IO endpoint. */
object KumaSocketOptions {
    const val SOCKET_PATH: String = "/socket.io/"
    private const val RECONNECTION_DELAY_MS = 1_000L
    private const val RECONNECTION_DELAY_MAX_MS = 30_000L

    /** Builds Socket.IO client options for the given server base URL (e.g. `https://status.example.org`). */
    fun forServer(baseUrl: String): IO.Options {
        val uri = URI(baseUrl)
        val basePath = uri.path.trimEnd('/')
        return IO.Options
            .builder()
            .setPath(basePath + SOCKET_PATH)
            .setReconnection(true)
            .setReconnectionDelay(RECONNECTION_DELAY_MS)
            .setReconnectionDelayMax(RECONNECTION_DELAY_MAX_MS)
            .setTransports(arrayOf("websocket"))
            .build()
    }
}
