package com.mangacombiner.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Collections

/**
 * Custom ProxySelector that enforces kill switch behavior.
 * If proxy is configured but fails, it prevents fallback to direct connection.
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
        // Log proxy connection failures - this is where kill switch activates
        Logger.logError("Proxy connection failed for ${uri}. Kill switch activated - no direct fallback allowed.", ioe)
        // Don't provide alternative - this enforces the kill switch
    }
}

actual fun createHttpClient(proxyUrl: String?): HttpClient {
    // Clear any previous proxy settings to ensure clean state
    System.clearProperty("http.proxyHost")
    System.clearProperty("http.proxyPort")
    System.clearProperty("https.proxyHost")
    System.clearProperty("https.proxyPort")
    System.clearProperty("socksProxyHost")
    System.clearProperty("socksProxyPort")
    System.clearProperty("java.net.socks.username")
    System.clearProperty("java.net.socks.password")
    Authenticator.setDefault(null)

    var configuredProxy: Proxy? = null

    return HttpClient(Java) {
        engine {
            if (!proxyUrl.isNullOrBlank()) {
                try {
                    val uri = URI(proxyUrl)
                    val username = uri.userInfo?.split(":", limit = 2)?.getOrNull(0)
                    val password = uri.userInfo?.split(":", limit = 2)?.getOrNull(1)

                    val port = if (uri.port == -1) {
                        when (uri.scheme?.lowercase()) {
                            "http" -> 80
                            "https" -> 443
                            "socks", "socks5" -> 1080
                            else -> 8080
                        }
                    } else {
                        uri.port
                    }

                    when (uri.scheme?.lowercase()) {
                        "http", "https" -> {
                            val socketAddress = InetSocketAddress(uri.host, port)
                            configuredProxy = Proxy(Proxy.Type.HTTP, socketAddress)
                            Logger.logInfo("Using HTTP proxy: ${uri.host}:$port")

                            // Set system properties for HTTP proxies (Java engine respects these)
                            System.setProperty("http.proxyHost", uri.host)
                            System.setProperty("http.proxyPort", port.toString())
                            System.setProperty("https.proxyHost", uri.host)
                            System.setProperty("https.proxyPort", port.toString())

                            if (!username.isNullOrBlank()) {
                                Authenticator.setDefault(object : Authenticator() {
                                    override fun getPasswordAuthentication(): PasswordAuthentication {
                                        if (requestorType == RequestorType.PROXY) {
                                            return PasswordAuthentication(username, password?.toCharArray() ?: "".toCharArray())
                                        }
                                        return super.getPasswordAuthentication()
                                    }
                                })
                                Logger.logInfo("Proxy authentication enabled for user: '$username'")
                            }
                        }
                        "socks", "socks5" -> {
                            val socketAddress = InetSocketAddress(uri.host, port)
                            configuredProxy = Proxy(Proxy.Type.SOCKS, socketAddress)
                            Logger.logInfo("Using SOCKS5 proxy: ${uri.host}:$port")

                            // For SOCKS, system properties work with Java engine
                            System.setProperty("socksProxyHost", uri.host)
                            System.setProperty("socksProxyPort", port.toString())

                            if (!username.isNullOrBlank()) {
                                System.setProperty("java.net.socks.username", username)
                                password?.let { System.setProperty("java.net.socks.password", it) }

                                // Also set up authenticator for additional compatibility
                                Authenticator.setDefault(object : Authenticator() {
                                    override fun getPasswordAuthentication(): PasswordAuthentication {
                                        if (requestorType == RequestorType.PROXY) {
                                            return PasswordAuthentication(username, password?.toCharArray() ?: "".toCharArray())
                                        }
                                        return super.getPasswordAuthentication()
                                    }
                                })
                                Logger.logInfo("SOCKS5 proxy authentication enabled for user: '$username'")
                            }
                        }
                        else -> {
                            Logger.logError("Unsupported proxy protocol in URL: $proxyUrl", null)
                        }
                    }

                    // Install kill switch proxy selector
                    if (configuredProxy != null) {
                        ProxySelector.setDefault(KillSwitchProxySelector(configuredProxy, false))
                        Logger.logInfo("Kill switch activated: All traffic will use proxy or fail")
                    }

                } catch (e: Exception) {
                    Logger.logError("Invalid proxy URL format: $proxyUrl", e)
                }
            } else {
                // No proxy configured - allow direct connections
                ProxySelector.setDefault(KillSwitchProxySelector(null, true))
            }
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 30000L
            connectTimeoutMillis = 20000L
            socketTimeoutMillis = 20000L
        }

        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
            exponentialDelay()

            // Custom retry logic that respects kill switch
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

        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            })
        }

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
