package io.github.roberbarras.racha.desktop.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.NetworkInterface

/**
 * Whether some non-loopback interface is up. The JDK has no change notification, so it is read every 30 s: one
 * cheap kernel query while the app is open, used only to sync as soon as the network comes back.
 */
object NetworkMonitor {
    private const val PERIOD_MS = 30_000L

    fun online(): Flow<Boolean> = flow {
        while (true) {
            emit(isOnline())
            delay(PERIOD_MS)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private fun isOnline(): Boolean = runCatching {
        NetworkInterface.networkInterfaces().anyMatch { it.isUp && !it.isLoopback && !it.isVirtual && it.interfaceAddresses.isNotEmpty() }
    }.getOrDefault(true)
}
