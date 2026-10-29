package net.kirkanos.uptimekuma.core.data.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject

/**
 * The device's default network as an opaque id (`null` while offline). [KumaConnectionManager]
 * uses changes to reconnect the live socket immediately instead of waiting for Socket.IO's backoff
 * (up to 30 s) or, after a Wi-Fi <-> mobile switch, for the ping timeout of the dead connection.
 */
internal fun interface NetworkMonitor {
    /** Emits the current default network on collection, then every change. Cold, unregisters on cancel. */
    fun defaultNetwork(): Flow<Long?>

    companion object {
        val NONE = NetworkMonitor { emptyFlow() }
    }
}

internal class ConnectivityNetworkMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : NetworkMonitor {
        override fun defaultNetwork(): Flow<Long?> =
            callbackFlow {
                val connectivity = context.getSystemService(ConnectivityManager::class.java)
                if (connectivity == null) {
                    close()
                    return@callbackFlow
                }
                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        private var current: Network? = null

                        override fun onAvailable(network: Network) {
                            current = network
                            trySend(network.networkHandle)
                        }

                        override fun onLost(network: Network) {
                            // A switch reports onAvailable(new); only losing the current default means offline.
                            if (network == current) {
                                current = null
                                trySend(null)
                            }
                        }
                    }
                connectivity.registerDefaultNetworkCallback(callback)
                awaitClose { connectivity.unregisterNetworkCallback(callback) }
            }.conflate().distinctUntilChanged()
    }
