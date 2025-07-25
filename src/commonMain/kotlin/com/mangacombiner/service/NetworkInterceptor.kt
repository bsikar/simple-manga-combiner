package com.mangacombiner.service

import com.mangacombiner.util.Logger
import kotlinx.coroutines.flow.StateFlow

/**
 * Interceptor that enforces kill switch behavior by blocking network operations
 * when the proxy is not connected.
 */
class NetworkInterceptor(
    private val proxyMonitor: ProxyMonitorService,
    private val isProxyRequired: () -> Boolean
) {

    /**
     * Checks if network operations should be allowed based on proxy status.
     * Throws an exception if the kill switch is active (proxy required but not connected).
     */
    fun checkNetworkAllowed() {
        if (!isProxyRequired()) {
            return // No proxy required, allow all operations
        }

        when (proxyMonitor.connectionState.value) {
            ProxyMonitorService.ProxyConnectionState.CONNECTED -> {
                // Proxy is connected, allow operation
                return
            }
            ProxyMonitorService.ProxyConnectionState.UNKNOWN,
            ProxyMonitorService.ProxyConnectionState.RECONNECTING -> {
                // Still checking or reconnecting, block operation
                throw ProxyKillSwitchException("Proxy connection is being established")
            }
            ProxyMonitorService.ProxyConnectionState.DISCONNECTED -> {
                // Proxy is disconnected, activate kill switch
                throw ProxyKillSwitchException("Proxy connection lost - Kill switch activated")
            }
            ProxyMonitorService.ProxyConnectionState.DISABLED -> {
                // Monitoring is disabled, allow operation
                return
            }
        }
    }

    /**
     * Wraps a network operation with kill switch protection.
     */
    suspend fun <T> withKillSwitch(operation: suspend () -> T): T {
        checkNetworkAllowed()
        return operation()
    }
}

class ProxyKillSwitchException(message: String) : Exception(message)
