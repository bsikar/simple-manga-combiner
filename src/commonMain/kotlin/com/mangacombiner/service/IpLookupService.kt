package com.mangacombiner.service

import com.mangacombiner.model.IpInfo
import com.mangacombiner.util.Logger
import com.mangacombiner.util.createHttpClient
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A centralized service to fetch and parse IP information from various lookup services.
 * It handles different JSON formats gracefully.
 */
class IpLookupService {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetches IP information from a given URL, optionally through a proxy.
     *
     * @param lookupUrl The URL of the IP lookup service.
     * @param proxyUrl The proxy URL to use for the request, if any.
     * @return A Result containing the parsed IpInfo on success, or an Exception on failure.
     */
    suspend fun getIpInfo(lookupUrl: String, proxyUrl: String?): Result<IpInfo> {
        val client = createHttpClient(proxyUrl)
        return try {
            val response = client.get(lookupUrl) {
                timeout {
                    requestTimeoutMillis = 10000
                    connectTimeoutMillis = 10000
                    socketTimeoutMillis = 10000
                }
            }

            if (response.status.isSuccess()) {
                val body = response.body<String>()
                val parsedIpInfo = parseIpInfo(body)
                if (parsedIpInfo.ip.isNullOrBlank()) {
                    Result.failure(Exception("Response did not contain a valid IP address."))
                } else {
                    Result.success(parsedIpInfo)
                }
            } else {
                Result.failure(Exception("HTTP ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    /**
     * Parses a JSON string into an IpInfo object, with fallbacks for different formats.
     */
    private fun parseIpInfo(jsonString: String): IpInfo {
        try {
            // First, try to parse as the full IpInfo object (for ipinfo.io, ip-api.com)
            return json.decodeFromString<IpInfo>(jsonString)
        } catch (e: Exception) {
            // If that fails, try to manually find an "ip" or "origin" field (for ipify.org, httpbin.org)
            Logger.logDebug { "Could not parse full IpInfo object, falling back to manual parsing." }
            try {
                val jsonElement = Json.parseToJsonElement(jsonString)
                val ip = jsonElement.jsonObject["ip"]?.jsonPrimitive?.content
                    ?: jsonElement.jsonObject["origin"]?.jsonPrimitive?.content // for httpbin.org
                if (ip != null) {
                    return IpInfo(ip = ip)
                }
            } catch (e2: Exception) {
                Logger.logError("Failed to manually parse IP from JSON: $jsonString", e2)
            }
        }
        // If all else fails
        return IpInfo(error = "Could not parse IP from response")
    }
}
