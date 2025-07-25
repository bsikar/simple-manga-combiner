package com.mangacombiner.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp // Use OkHttp engine
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.net.Authenticator // Use the standard Java Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URI

/**
 * Creates a Ktor HttpClient using the OkHttp engine with the standard Java Authenticator
 * for reliable SOCKS5 proxy support and a strict no-fallback policy.
 */
actual fun createHttpClient(proxyUrl: String?): HttpClient {
    // Clear the JVM's global authenticator to ensure a clean state for each client.
    Authenticator.setDefault(null)

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

            // For SOCKS5, OkHttp delegates to the JVM, which uses this global authenticator.
            if (!username.isNullOrBlank()) {
                Authenticator.setDefault(object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(username, password?.toCharArray() ?: "".toCharArray())
                    }
                })
                Logger.logInfo("Proxy authentication enabled for user: '$username'")
            }
        } catch (e: Exception) {
            Logger.logError("Invalid proxy URL format or configuration error: $proxyUrl", e)
            configuredProxy = null
        }
    } else {
        configuredProxy = Proxy.NO_PROXY
    }

    return HttpClient(OkHttp) {
        engine {
            // Explicitly set the proxy for this client instance.
            proxy = configuredProxy
        }

        install(HttpTimeout) {
            connectTimeoutMillis = 30000L
            requestTimeoutMillis = 45000L
            socketTimeoutMillis = 30000L
        }

        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
            exponentialDelay()
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
        }
    }
}
