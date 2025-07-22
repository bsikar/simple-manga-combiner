package com.mangacombiner.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URI

actual fun createHttpClient(proxyUrl: String?): HttpClient = HttpClient(CIO) {
    engine {
        if (!proxyUrl.isNullOrBlank()) {
            try {
                val uri = URI(proxyUrl)
                val proxyType = when (uri.scheme?.lowercase()) {
                    "http", "https" -> Proxy.Type.HTTP
                    "socks", "socks5" -> Proxy.Type.SOCKS
                    else -> null
                }

                if (proxyType != null) {
                    val port = if (uri.port == -1) {
                        when (uri.scheme?.lowercase()) {
                            "http" -> 80
                            "https" -> 443
                            "socks", "socks5" -> 1080 // Default SOCKS port
                            else -> 8080 // Generic default
                        }
                    } else {
                        uri.port
                    }
                    val socketAddress = InetSocketAddress(uri.host, port)
                    proxy = Proxy(proxyType, socketAddress)
                    Logger.logInfo("Using ${proxyType.name} proxy: ${uri.host}:$port")

                    // Clear previous authenticator
                    Authenticator.setDefault(null)

                    uri.userInfo?.split(":", limit = 2)?.let { userInfo ->
                        val username = userInfo.getOrNull(0)
                        val password = userInfo.getOrNull(1)
                        if (!username.isNullOrBlank()) {
                            Authenticator.setDefault(object : Authenticator() {
                                override fun getPasswordAuthentication(): PasswordAuthentication {
                                    return PasswordAuthentication(username, password?.toCharArray() ?: "".toCharArray())
                                }
                            })
                            Logger.logInfo("Proxy authentication enabled for user: '$username'")
                        }
                    }
                } else {
                    Logger.logError("Unsupported proxy protocol in URL: $proxyUrl", null)
                }
            } catch (e: Exception) {
                Logger.logError("Invalid proxy URL format: $proxyUrl", e)
            }
        } else {
            // Ensure no proxy is used if URL is blank and clear any default authenticator
            Authenticator.setDefault(null)
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
    }
    install(Logging) {
        logger = KtorLogger()
        level = LogLevel.ALL
    }
    install(HttpCookies)
    install(ContentNegotiation) {
        json() // Configure the client to handle JSON responses
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
