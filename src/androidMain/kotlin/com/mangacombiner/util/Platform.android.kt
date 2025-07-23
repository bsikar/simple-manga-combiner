package com.mangacombiner.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Custom ProxySelector that enforces kill switch behavior.
 * If a proxy is configured, it will ONLY return that proxy. If the connection to that
 * proxy fails, the `connectFailed` method is called, and because we don't provide
 * an alternative, the connection fails entirely instead of falling back to a direct connection.
 */
private class KillSwitchProxySelector(
    private val configuredProxy: Proxy?,
    private val allowDirectConnection: Boolean = false
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        return if (configuredProxy != null) {
            // Only return the configured proxy, no fallback to DIRECT
            listOf(configuredProxy)
        } else {
            // No proxy configured, allow direct connections
            listOf(Proxy.NO_PROXY)
        }
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {
        // Log proxy connection failures - this is where the kill switch activates
        Logger.logError("Proxy connection failed for ${uri}. Kill switch activated - no direct fallback allowed.", ioe)
        // By not providing an alternative proxy here, we prevent fallback to a direct connection.
    }
}

/**
 * Creates a Ktor HttpClient using the Java engine, with robust, explicit proxy configuration.
 * This implementation avoids relying on global system properties for the proxy itself,
 * instead using the standard Java Authenticator and ProxySelector mechanisms, which the
 * Ktor Java engine is designed to respect. This provides reliable proxy support and a
 * functional kill switch.
 */
actual fun createHttpClient(proxyUrl: String?): HttpClient {
    // Reset all proxy-related global state to ensure a clean slate for each client creation.
    System.clearProperty("http.proxyHost")
    System.clearProperty("http.proxyPort")
    System.clearProperty("https.proxyHost")
    System.clearProperty("https.proxyPort")
    System.clearProperty("socksProxyHost")
    System.clearProperty("socksProxyPort")
    System.clearProperty("java.net.socks.username")
    System.clearProperty("java.net.socks.password")
    Authenticator.setDefault(null)
    ProxySelector.setDefault(null)

    var configuredProxy: Proxy? = null

    if (!proxyUrl.isNullOrBlank()) {
        try {
            val uri = URI(proxyUrl)
            val host = uri.host
            val port = if (uri.port != -1) uri.port else when (uri.scheme?.lowercase()) {
                "http" -> 80
                "https" -> 443
                "socks", "socks5" -> 1080
                else -> 1080 // Default SOCKS port
            }

            val proxyType = when (uri.scheme?.lowercase()) {
                "http", "https" -> Proxy.Type.HTTP
                "socks", "socks5" -> Proxy.Type.SOCKS
                else -> throw IllegalArgumentException("Unsupported proxy scheme: ${uri.scheme}")
            }

            configuredProxy = Proxy(proxyType, InetSocketAddress(host, port))
            Logger.logInfo("Configuring proxy: ${proxyType}://$host:$port")

            val username = uri.userInfo?.split(":", limit = 2)?.getOrNull(0)
            val password = uri.userInfo?.split(":", limit = 2)?.getOrNull(1)

            if (!username.isNullOrBlank()) {
                // This Authenticator works for both SOCKS5 and HTTP Basic Auth with the Java engine
                Authenticator.setDefault(object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        Logger.logDebug { "Authenticator providing credentials for ${getRequestingHost()}:${getRequestingPort()}" }
                        return PasswordAuthentication(username, password?.toCharArray() ?: "".toCharArray())
                    }
                })
                Logger.logInfo("Proxy authentication enabled for user: '$username'")
            }
        } catch (e: Exception) {
            Logger.logError("Invalid proxy URL format or configuration error: $proxyUrl", e)
            configuredProxy = null
        }
    }

    // Install our custom ProxySelector. This is the key to the kill switch.
    // The Java engine will use this selector to get the proxy for each connection.
    ProxySelector.setDefault(KillSwitchProxySelector(configuredProxy, allowDirectConnection = configuredProxy == null))
    if (configuredProxy != null) {
        Logger.logInfo("Kill switch activated: All traffic will use proxy or fail.")
    }

    return HttpClient(Java) {
        // The Java engine automatically uses the default ProxySelector we just set.
        // No extra engine configuration is needed here for the proxy itself.

        install(HttpTimeout) {
            requestTimeoutMillis = 20000L
            connectTimeoutMillis = 15000L
            socketTimeoutMillis = 15000L
        }

        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
            exponentialDelay()

            retryIf { _, httpResponse ->
                val shouldRetry = httpResponse.status.value >= 500
                if (!shouldRetry && configuredProxy != null) {
                    Logger.logDebug { "Not retrying request - kill switch prevents fallback" }
                }
                shouldRetry
            }
        }

        install(Logging) {
            logger = KtorLogger()
            level = LogLevel.ALL
        }

        install(HttpCookies)

        defaultRequest {
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            header("Upgrade-Insecure-Requests", "1")
            header("Sec-Fetch-Dest", "document")
            header("Sec-Fetch-Mode", "navigate")
            header("Sec-Fetch-Site", "same-origin")
            header("Sec-Fetch-User", "?1")
            header("sec-ch-ua", "\"Not/A)Brand\";v=\"99\", \"Google Chrome\";v=\"135\", \"Chromium\";v=\"135\"")
            header("sec-ch-ua-mobile", "?0")
            header("sec-ch-ua-platform", "\"Windows\"")
        }
    }
}
