package com.mangacombiner.service

import com.mangacombiner.util.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service that continuously monitors proxy connection status and enforces kill switch behavior.
 */
class ProxyMonitorService(private val ipLookupService: IpLookupService) {

    companion object {
        private const val CHECK_INTERVAL_MS = 5000L
        private const val QUICK_CHECK_INTERVAL_MS = 1000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val RECONNECT_ATTEMPTS = 5
    }

    private val _connectionState = MutableStateFlow(ProxyConnectionState.UNKNOWN)
    val connectionState: StateFlow<ProxyConnectionState> = _connectionState.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private var monitoringJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    data class ProxyConfig(
        val url: String,
        val expectedIp: String? = null,
        val lookupUrl: String
    )

    enum class ProxyConnectionState {
        UNKNOWN, CONNECTED, DISCONNECTED, RECONNECTING, DISABLED
    }

    fun startMonitoring(proxyConfig: ProxyConfig, scope: CoroutineScope) {
        if (!isRunning.compareAndSet(false, true)) {
            Logger.logDebug { "Proxy monitoring already running" }
            return
        }

        Logger.logInfo("Starting proxy kill switch monitoring for: ${proxyConfig.url}")
        _isMonitoring.value = true
        _connectionState.value = ProxyConnectionState.UNKNOWN

        monitoringJob = scope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0
            var lastState = ProxyConnectionState.UNKNOWN

            // Perform an initial, full check to ensure it's working before we start light monitoring
            val initialCheckSuccess = performFullConnectionCheck(proxyConfig)
            if (!initialCheckSuccess) {
                Logger.logError("❌ Initial proxy connection check failed - KILL SWITCH ACTIVATED")
                _connectionState.value = ProxyConnectionState.DISCONNECTED
                lastState = ProxyConnectionState.DISCONNECTED
            } else {
                Logger.logInfo("✅ Initial proxy connection successful. Starting lightweight monitoring.")
                _connectionState.value = ProxyConnectionState.CONNECTED
                lastState = ProxyConnectionState.CONNECTED
            }

            while (isActive) {
                try {
                    val isConnected = checkProxyConnection(proxyConfig)

                    if (isConnected) {
                        consecutiveFailures = 0
                        if (lastState != ProxyConnectionState.CONNECTED) {
                            // If we were previously disconnected, run a full check before marking as connected
                            if (performFullConnectionCheck(proxyConfig)) {
                                Logger.logInfo("✅ Proxy re-established and verified.")
                                _connectionState.value = ProxyConnectionState.CONNECTED
                                lastState = ProxyConnectionState.CONNECTED
                            } else {
                                Logger.logWarn("Proxy socket is alive, but full verification failed. Keeping kill switch active.")
                                // Stay in disconnected state and retry
                                lastState = ProxyConnectionState.DISCONNECTED
                                _connectionState.value = ProxyConnectionState.DISCONNECTED
                            }
                        }
                    } else {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            if (lastState != ProxyConnectionState.DISCONNECTED) {
                                Logger.logError("❌ Proxy connection lost - KILL SWITCH ACTIVATED")
                                _connectionState.value = ProxyConnectionState.DISCONNECTED
                                lastState = ProxyConnectionState.DISCONNECTED
                            }
                        }
                    }

                    val interval = if (lastState == ProxyConnectionState.DISCONNECTED) QUICK_CHECK_INTERVAL_MS else CHECK_INTERVAL_MS
                    delay(interval)

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.logError("Error in proxy monitor loop", e)
                    delay(CHECK_INTERVAL_MS)
                }
            }
        }

        monitoringJob?.invokeOnCompletion {
            isRunning.set(false)
            _isMonitoring.value = false
            Logger.logInfo("Proxy monitoring stopped")
        }
    }

    fun stopMonitoring() {
        Logger.logInfo("Stopping proxy monitoring")
        monitoringJob?.cancel()
        monitoringJob = null
        _connectionState.value = ProxyConnectionState.DISABLED
        _isMonitoring.value = false
    }

    /**
     * Performs a lightweight, local check to see if the proxy server is reachable.
     */
    private fun isProxySocketAlive(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1000) // 1-second timeout
                true
            }
        } catch (e: Exception) {
            Logger.logDebug { "Proxy socket check failed for $host:$port: ${e.message}" }
            false
        }
    }

    /**
     * This is the new lightweight check that runs every few seconds.
     * It relies on isProxySocketAlive to see if the proxy server is even running.
     */
    private suspend fun checkProxyConnection(config: ProxyConfig): Boolean {
        return try {
            val uri = URI(config.url)
            isProxySocketAlive(uri.host, uri.port)
        } catch (e: Exception) {
            Logger.logError("Could not parse proxy URL for socket check: ${config.url}", e)
            false
        }
    }

    /**
     * This is the original heavyweight check that connects to an external IP service.
     * We now only call this on startup and after a reconnect.
     */
    private suspend fun performFullConnectionCheck(config: ProxyConfig): Boolean {
        Logger.logInfo("Performing full external IP verification...")
        val result = ipLookupService.getIpInfo(config.lookupUrl, config.url)
        return result.fold(
            onSuccess = { ipInfo ->
                val currentIp = ipInfo.ip
                if (currentIp == null) {
                    Logger.logWarn("Full check failed: Response did not contain an IP address.")
                    false
                } else if (config.expectedIp != null) {
                    val matches = currentIp == config.expectedIp
                    if (!matches) {
                        Logger.logWarn("Proxy IP mismatch: expected ${config.expectedIp}, got $currentIp")
                    }
                    matches
                } else {
                    // If we don't have an expected IP, just confirming we got *any* valid IP is enough.
                    val isIpFormatValid = currentIp.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))
                    if (!isIpFormatValid) {
                        Logger.logWarn("Full check failed: Received an invalid IP format: $currentIp")
                    }
                    isIpFormatValid
                }
            },
            onFailure = {
                Logger.logWarn("Full check failed: ${it.message}")
                false
            }
        )
    }

    // This reconnection logic is no longer needed here as the main loop handles it.
    // The main loop will keep checking the socket and trigger a full re-verification when it comes back online.
    private suspend fun attemptReconnection(config: ProxyConfig): Boolean {
        _connectionState.value = ProxyConnectionState.RECONNECTING
        Logger.logInfo("Attempting to reconnect to proxy...")

        repeat(RECONNECT_ATTEMPTS) { attempt ->
            if (checkProxyConnection(config)) {
                return true
            }
            val backoffDelay = (1000L * (1 shl attempt)).coerceAtMost(16000L)
            Logger.logDebug { "Reconnection attempt ${attempt + 1} failed, waiting ${backoffDelay}ms" }
            delay(backoffDelay)
        }
        return false
    }
}
