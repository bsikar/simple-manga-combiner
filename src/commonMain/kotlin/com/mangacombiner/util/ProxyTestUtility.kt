package com.mangacombiner.util

import com.mangacombiner.model.IpInfo
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enhanced proxy testing utility that validates both proxy functionality and kill switch behavior.
 */
object ProxyTestUtility {

    private val testInProgress = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Comprehensive proxy test that validates connectivity, IP change, and kill switch behavior.
     */
    suspend fun runComprehensiveProxyTest(proxyUrl: String, lookupUrl: String): ProxyTestResult = coroutineScope {
        if (!testInProgress.compareAndSet(false, true)) {
            return@coroutineScope ProxyTestResult.inProgress()
        }

        try {
            Logger.logInfo("=== Starting Comprehensive Proxy Test ===")
            Logger.logInfo("Testing proxy: $proxyUrl")
            Logger.logInfo("Using lookup service: $lookupUrl")

            // Step 1: Test direct connection (baseline)
            Logger.logInfo("Step 1: Testing direct connection (baseline)...")
            val directResult = testDirectConnection(lookupUrl)
            val directIp = directResult.ipInfo?.ip

            // Step 2: Test proxy connection
            Logger.logInfo("Step 2: Testing proxy connection...")
            val proxyResult = testProxyConnection(proxyUrl, directIp, lookupUrl)

            if (!proxyResult.success) {
                val finalResult = ProxyTestResult(
                    success = false,
                    directIp = directIp,
                    proxyIp = null,
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
            val killSwitchResult = testKillSwitchBehavior(proxyUrl, directIp, lookupUrl)

            // Step 4: Combine results
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

    private suspend fun testDirectConnection(lookupUrl: String): SingleTestResult = coroutineScope {
        try {
            createHttpClient(null).use { client ->
                Logger.logInfo("Using Ktor HTTP client engine: ${client.engine::class.simpleName}")
                val response = client.get(lookupUrl)
                if (response.status.isSuccess()) {
                    val ipInfo = parseIpInfo(response.body())
                    Logger.logInfo("Direct connection: ${ipInfo.ip} (${ipInfo.city}, ${ipInfo.country})")
                    SingleTestResult.success(ipInfo)
                } else {
                    SingleTestResult.failed("HTTP ${response.status}")
                }
            }
        } catch (e: Exception) {
            Logger.logError("Direct connection test failed", e)
            SingleTestResult.failed(e.message ?: "Unknown error")
        }
    }

    private suspend fun testProxyConnection(proxyUrl: String, directIp: String?, lookupUrl: String): SingleTestResult = coroutineScope {
        try {
            createHttpClient(proxyUrl).use { client ->
                Logger.logInfo("Using Ktor HTTP client engine: ${client.engine::class.simpleName}")
                val response = client.get(lookupUrl)
                if (response.status.isSuccess()) {
                    val ipInfo = parseIpInfo(response.body())
                    if (ipInfo.ip == directIp) {
                        Logger.logError("Proxy connection failed: IP address is the same as direct connection.")
                        return@coroutineScope SingleTestResult.failed("Proxy is not being used. IP address did not change.", ipInfo = ipInfo, authWorked = true)
                    }
                    Logger.logInfo("Proxy connection: ${ipInfo.ip} (${ipInfo.city}, ${ipInfo.country})")
                    SingleTestResult.success(ipInfo, authWorked = true)
                } else {
                    SingleTestResult.failed("HTTP ${response.status}", authWorked = false)
                }
            }
        } catch (e: Exception) {
            Logger.logError("Proxy connection test failed", e)
            if (e.message?.contains("authentication", ignoreCase = true) == true || e.message?.contains("rejected", ignoreCase = true) == true) {
                return@coroutineScope SingleTestResult.failed("Proxy authentication failed: ${e.message}", authWorked = false)
            }
            return@coroutineScope SingleTestResult.failed(e.message ?: "Unknown connection error", authWorked = false)
        }
    }

    private suspend fun testKillSwitchBehavior(proxyUrl: String, directIp: String?, lookupUrl: String): SingleTestResult = coroutineScope {
        try {
            // Test with an invalid proxy port to ensure it doesn't fall back to direct connection
            val invalidProxyUrl = proxyUrl.replace(Regex(":(\\d+)"), ":${(60000..65535).random()}")

            Logger.logInfo("Testing kill switch with invalid proxy: $invalidProxyUrl")

            createHttpClient(invalidProxyUrl).use { client ->
                try {
                    // This request should fail and NOT fall back to a direct connection
                    val response = client.get(lookupUrl) {
                        timeout { requestTimeoutMillis = 10000 }
                    }
                    val ipInfo = parseIpInfo(response.body())
                    if (ipInfo.ip == directIp) {
                        Logger.logError("KILL SWITCH FAILED! Connection succeeded through direct connection: ${ipInfo.ip}")
                        return@use SingleTestResult.failed("Kill switch failed - fell back to direct connection")
                    } else {
                        Logger.logError("KILL SWITCH FAILED! Connection succeeded through an unexpected IP: ${ipInfo.ip}")
                        return@use SingleTestResult.failed("Kill switch failed - connected to an unexpected IP")
                    }
                } catch (e: Exception) {
                    // This is the EXPECTED outcome for a working kill switch. The connection must fail.
                    Logger.logInfo("Kill switch working correctly - connection failed as expected: ${e.message}")
                    return@use SingleTestResult.success(null, "Connection failed as expected (kill switch active)")
                }
            }
        } catch (e: Exception) {
            Logger.logError("Kill switch test failed unexpectedly", e)
            SingleTestResult.failed("Kill switch test error: ${e.message}")
        }
    }

    private fun parseIpInfo(jsonString: String): IpInfo {
        try {
            return json.decodeFromString<IpInfo>(jsonString)
        } catch (e: Exception) {
            Logger.logDebug { "Could not parse full IpInfo object, falling back to manual parsing." }
            try {
                val jsonElement = Json.parseToJsonElement(jsonString)
                val ip = jsonElement.jsonObject["ip"]?.jsonPrimitive?.content
                    ?: jsonElement.jsonObject["origin"]?.jsonPrimitive?.content
                if (ip != null) {
                    return IpInfo(ip = ip)
                }
            } catch (e2: Exception) {
                Logger.logError("Failed to manually parse IP from JSON: $jsonString", e2)
            }
        }
        return IpInfo(error = "Could not parse IP from response")
    }

    private fun logTestResults(result: ProxyTestResult) {
        if (result.success) {
            Logger.logInfo("🟢 PROXY TEST PASSED")
            Logger.logInfo("  ✓ IP changed: ${result.directIp} → ${result.proxyIp}")
            Logger.logInfo("  ✓ Location: ${result.directLocation} → ${result.proxyLocation}")
            Logger.logInfo("  ✓ Kill switch: ${if (result.killSwitchWorking) "Working" else "Failed"}")
        } else {
            Logger.logError("🔴 PROXY TEST FAILED")
            result.error?.let { Logger.logError("    Error: $it") }
            if (!result.ipChanged && result.proxyIp != null) {
                Logger.logError("    ⚠️  IP did not change - proxy may not be working")
            }
            if (!result.killSwitchWorking) {
                Logger.logError("    ⚠️  Kill switch not working - traffic may leak through direct connection")
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
        val ipInfo: IpInfo? = null,
        val error: String? = null,
        val message: String? = null,
        val authWorked: Boolean? = null
    ) {
        companion object {
            fun success(ipInfo: IpInfo?, message: String? = null, authWorked: Boolean? = null) =
                SingleTestResult(true, ipInfo, null, message, authWorked)
            fun failed(error: String, ipInfo: IpInfo? = null, authWorked: Boolean? = null) =
                SingleTestResult(false, ipInfo, error, null, authWorked)
        }
    }
}
