package com.mangacombiner.util

import io.ktor.client.call.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enhanced proxy testing utility that validates both SOCKS5 functionality and kill switch behavior.
 */
object ProxyTestUtility {

    private val testInProgress = AtomicBoolean(false)

    /**
     * Comprehensive proxy test that validates:
     * 1. SOCKS5 connectivity and authentication.
     * 2. IP address change verification.
     * 3. Kill switch behavior (no fallback to direct connection).
     * 4. Consistency across multiple geo-location endpoints.
     */
    suspend fun runComprehensiveProxyTest(proxyUrl: String): ProxyTestResult = coroutineScope {
        if (!testInProgress.compareAndSet(false, true)) {
            return@coroutineScope ProxyTestResult.inProgress()
        }

        try {
            Logger.logInfo("=== Starting Comprehensive Proxy Test ===")
            Logger.logInfo("Testing proxy: $proxyUrl")

            // Step 1: Test direct connection (baseline)
            Logger.logInfo("Step 1: Testing direct connection (baseline)...")
            val directResult = testDirectConnection()
            val directIp = directResult.ipInfo?.ip

            // Step 2: Test proxy connection
            Logger.logInfo("Step 2: Testing proxy connection...")
            val proxyResult = testProxyConnection(proxyUrl, directIp)

            // If the main proxy connection fails, we can't test anything else. Return early with detailed error.
            if (!proxyResult.success) {
                val finalResult = ProxyTestResult(
                    success = false,
                    directIp = directIp,
                    proxyIp = null, // It failed
                    ipChanged = false,
                    killSwitchWorking = false, // Can't be tested
                    proxyLocation = null,
                    directLocation = directResult.ipInfo?.let { "${it.city}, ${it.country}" },
                    error = proxyResult.error ?: "Proxy connection failed.",
                    details = listOf(
                        "Direct IP: ${directIp ?: "Unknown"}",
                        "Proxy IP: FAILED",
                        "Error: ${proxyResult.error}"
                    )
                )
                logTestResults(finalResult)
                return@coroutineScope finalResult
            }

            // Step 3: Test kill switch behavior
            Logger.logInfo("Step 3: Testing kill switch behavior...")
            val killSwitchResult = testKillSwitchBehavior(proxyUrl, directIp)

            // Step 4: Test multiple endpoints simultaneously
            Logger.logInfo("Step 4: Testing multiple endpoints...")
            val multiEndpointResult = testMultipleEndpoints(proxyUrl)

            // Combine results
            val overallResult = ProxyTestResult(
                success = proxyResult.success && killSwitchResult.success,
                directIp = directIp,
                proxyIp = proxyResult.ipInfo?.ip,
                ipChanged = directIp != null && proxyResult.ipInfo?.ip != null && directIp != proxyResult.ipInfo.ip,
                killSwitchWorking = killSwitchResult.success,
                proxyLocation = proxyResult.ipInfo?.let { "${it.city}, ${it.country}" },
                directLocation = directResult.ipInfo?.let { "${it.city}, ${it.country}" },
                error = proxyResult.error ?: killSwitchResult.error,
                details = buildList {
                    add("Direct IP: ${directIp ?: "Unknown"}")
                    add("Proxy IP: ${proxyResult.ipInfo?.ip ?: "Failed to get"}")
                    add("Location change: ${directResult.ipInfo?.let { "${it.city}, ${it.country}" }} → ${proxyResult.ipInfo?.let { "${it.city}, ${it.country}" }}")
                    add("Kill switch: ${if (killSwitchResult.success) "✓ Working" else "✗ Failed"}")
                    add("Multi-endpoint test: ${if (multiEndpointResult.success) "✓ All consistent" else "✗ Inconsistent"}")
                }
            )

            Logger.logInfo("=== Proxy Test Complete ===")
            logTestResults(overallResult)
            overallResult

        } catch (e: Exception) {
            Logger.logError("Proxy test failed with exception", e)
            ProxyTestResult.failed("Test failed: ${e.message}")
        } finally {
            testInProgress.set(false)
        }
    }

    private suspend fun testDirectConnection(): SingleTestResult = coroutineScope {
        try {
            val client = createHttpClient(null) // No proxy
            try {
                Logger.logInfo("Using Ktor HTTP client engine: ${client.engine::class.simpleName}")
                val response = client.get("https://ipinfo.io/json")
                if (response.status.isSuccess()) {
                    val ipInfo = response.body<com.mangacombiner.model.IpInfo>()
                    Logger.logInfo("Direct connection: ${ipInfo.ip} (${ipInfo.city}, ${ipInfo.country})")
                    SingleTestResult.success(ipInfo)
                } else {
                    SingleTestResult.failed("HTTP ${response.status}")
                }
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            Logger.logError("Direct connection test failed", e)
            SingleTestResult.failed(e.message ?: "Unknown error")
        }
    }

    private suspend fun testProxyConnection(proxyUrl: String, directIp: String?): SingleTestResult = coroutineScope {
        try {
            val client = createHttpClient(proxyUrl)
            try {
                Logger.logInfo("Using Ktor HTTP client engine: ${client.engine::class.simpleName}")
                val response = client.get("https://ipinfo.io/json")
                if (response.status.isSuccess()) {
                    val ipInfo = response.body<com.mangacombiner.model.IpInfo>()
                    if (ipInfo.ip == directIp) {
                        Logger.logError("Proxy connection failed: IP address is the same as direct connection.")
                        return@coroutineScope SingleTestResult.failed("Proxy is not being used. IP address did not change.", ipInfo = ipInfo, authWorked = true)
                    }
                    Logger.logInfo("Proxy connection: ${ipInfo.ip} (${ipInfo.city}, ${ipInfo.country})")
                    SingleTestResult.success(ipInfo, authWorked = true)
                } else {
                    SingleTestResult.failed("HTTP ${response.status}", authWorked = false)
                }
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            Logger.logError("Proxy connection test failed", e)
            if (e.message?.contains("authentication", ignoreCase = true) == true || e.message?.contains("rejected", ignoreCase = true) == true) {
                return@coroutineScope SingleTestResult.failed("Proxy authentication failed: ${e.message}", authWorked = false)
            }
            return@coroutineScope SingleTestResult.failed(e.message ?: "Unknown connection error", authWorked = false)
        }
    }

    private suspend fun testKillSwitchBehavior(proxyUrl: String, directIp: String?): SingleTestResult = coroutineScope {
        try {
            // Test with an invalid proxy port to ensure it doesn't fall back to direct connection
            val invalidProxyUrl = proxyUrl.replace(
                Regex(":(\\d+)"),
                ":${(10000..65000).random()}" // Random invalid port
            )

            Logger.logInfo("Testing kill switch with invalid proxy: $invalidProxyUrl")

            val client = createHttpClient(invalidProxyUrl)
            try {
                // This request should fail with a connection error and NOT fall back to a direct connection
                val response = client.get("https://ipinfo.io/json") {
                    timeout {
                        requestTimeoutMillis = 10000 // Short timeout for testing
                    }
                }

                // If we get here, the kill switch has FAILED because a connection was made
                val ipInfo = response.body<com.mangacombiner.model.IpInfo>()
                if (ipInfo.ip == directIp) {
                    Logger.logError("KILL SWITCH FAILED! Connection succeeded through direct connection: ${ipInfo.ip}")
                    return@coroutineScope SingleTestResult.failed("Kill switch failed - fell back to direct connection")
                } else {
                    Logger.logError("KILL SWITCH FAILED! Connection succeeded through an unexpected IP: ${ipInfo.ip}")
                    return@coroutineScope SingleTestResult.failed("Kill switch failed - connected to an unexpected IP")
                }

            } catch (e: Exception) {
                // This is the EXPECTED outcome for a working kill switch. The connection must fail.
                Logger.logInfo("Kill switch working correctly - connection failed as expected: ${e.message}")
                return@coroutineScope SingleTestResult.success(null, "Connection failed as expected (kill switch active)")
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            Logger.logError("Kill switch test failed unexpectedly", e)
            SingleTestResult.failed("Kill switch test error: ${e.message}")
        }
    }

    private suspend fun testMultipleEndpoints(proxyUrl: String): SingleTestResult = coroutineScope {
        try {
            val endpoints = listOf(
                "https://ipinfo.io/json",
                "https://api.ipify.org?format=json",
                "https://httpbin.org/ip"
            )

            val client = createHttpClient(proxyUrl)
            try {
                val results = endpoints.map { endpoint ->
                    async {
                        try {
                            val response = client.get(endpoint)
                            if (response.status.isSuccess()) {
                                val body = response.bodyAsText()
                                extractIpFromResponse(body, endpoint)
                            } else null
                        } catch (e: Exception) {
                            Logger.logDebug { "Endpoint $endpoint failed: ${e.message}" }
                            null
                        }
                    }
                }.awaitAll()

                val validResults = results.filterNotNull()
                val uniqueIps = validResults.toSet()

                if (uniqueIps.size == 1) {
                    Logger.logInfo("Multi-endpoint test: All endpoints consistent (${uniqueIps.first()})")
                    SingleTestResult.success(null, "All endpoints consistent")
                } else {
                    Logger.logWarn("Multi-endpoint test: Inconsistent IPs detected: $uniqueIps")
                    SingleTestResult.failed("Inconsistent IPs across endpoints: $uniqueIps")
                }

            } finally {
                client.close()
            }
        } catch (e: Exception) {
            Logger.logError("Multi-endpoint test failed", e)
            SingleTestResult.failed("Multi-endpoint test error: ${e.message}")
        }
    }

    private fun extractIpFromResponse(body: String, endpoint: String): String? {
        return try {
            when {
                endpoint.contains("ipinfo.io") -> {
                    // JSON: {"ip":"1.2.3.4",...}
                    Regex(""""ip"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                }
                endpoint.contains("ipify.org") -> {
                    // JSON: {"ip":"1.2.3.4"}
                    Regex(""""ip"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                }
                endpoint.contains("httpbin.org") -> {
                    // JSON: {"origin":"1.2.3.4"}
                    Regex(""""origin"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                }
                else -> null
            }
        } catch (e: Exception) {
            Logger.logDebug { "Failed to extract IP from $endpoint: ${e.message}" }
            null
        }
    }

    private fun logTestResults(result: ProxyTestResult) {
        if (result.success) {
            Logger.logInfo("🟢 PROXY TEST PASSED")
            Logger.logInfo("  ✓ IP changed: ${result.directIp} → ${result.proxyIp}")
            Logger.logInfo("  ✓ Location: ${result.directLocation} → ${result.proxyLocation}")
            Logger.logInfo("  ✓ Kill switch: ${if (result.killSwitchWorking) "Working" else "Failed"}")
        } else {
            Logger.logError("🔴 PROXY TEST FAILED")
            result.error?.let { Logger.logError("   Error: $it") }
            if (!result.ipChanged && result.proxyIp != null) {
                Logger.logError("   ⚠️  IP did not change - proxy may not be working")
            }
            if (!result.killSwitchWorking) {
                Logger.logError("   ⚠️  Kill switch not working - traffic may leak through direct connection")
            }
        }

        result.details.forEach { detail ->
            Logger.logInfo("  $detail")
        }
    }

    data class ProxyTestResult(
        val success: Boolean,
        val directIp: String? = null,
        val proxyIp: String? = null,
        val ipChanged: Boolean = false,
        val killSwitchWorking: Boolean = false,
        val proxyLocation: String? = null,
        val directLocation: String? = null,
        val error: String? = null,
        val details: List<String> = emptyList()
    ) {
        companion object {
            fun failed(error: String) = ProxyTestResult(success = false, error = error)
            fun inProgress() = ProxyTestResult(success = false, error = "Test already in progress")
        }
    }

    private data class SingleTestResult(
        val success: Boolean,
        val ipInfo: com.mangacombiner.model.IpInfo? = null,
        val error: String? = null,
        val message: String? = null,
        val authWorked: Boolean? = null
    ) {
        companion object {
            fun success(ipInfo: com.mangacombiner.model.IpInfo?, message: String? = null, authWorked: Boolean? = null) =
                SingleTestResult(true, ipInfo, null, message, authWorked)
            fun failed(error: String, ipInfo: com.mangacombiner.model.IpInfo? = null, authWorked: Boolean? = null) =
                SingleTestResult(false, ipInfo, error, null, authWorked)
        }
    }
}
