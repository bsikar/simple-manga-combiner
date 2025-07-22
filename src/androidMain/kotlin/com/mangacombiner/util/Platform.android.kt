package com.mangacombiner.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
import java.net.URI

actual fun createHttpClient(proxyUrl: String?): HttpClient {
    // Clear any previous proxy system properties to ensure a clean state
    System.clearProperty("socksProxyHost")
    System.clearProperty("socksProxyPort")
    System.clearProperty("java.net.socks.username")
    System.clearProperty("java.net.socks.password")
    Authenticator.setDefault(null)

    return HttpClient(CIO) {
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
                            proxy = Proxy(Proxy.Type.HTTP, socketAddress)
                            Logger.logInfo("Using HTTP proxy: ${uri.host}:$port")

                            if (!username.isNullOrBlank()) {
                                Authenticator.setDefault(object : Authenticator() {
                                    override fun getPasswordAuthentication(): PasswordAuthentication {
                                        return PasswordAuthentication(username, password?.toCharArray() ?: "".toCharArray())
                                    }
                                })
                                Logger.logInfo("Proxy authentication enabled for user: '$username'")
                            }
                        }
                        "socks", "socks5" -> {
                            // For SOCKS, setting system properties is the most reliable way to prevent DNS leaks.
                            System.setProperty("socksProxyHost", uri.host)
                            System.setProperty("socksProxyPort", port.toString())
                            Logger.logInfo("Using SOCKS proxy: ${uri.host}:$port (via system properties)")

                            if (!username.isNullOrBlank()) {
                                System.setProperty("java.net.socks.username", username)
                                password?.let { System.setProperty("java.net.socks.password", it) }
                                Logger.logInfo("Proxy authentication enabled for user: '$username'")
                            }
                        }
                        else -> {
                            Logger.logError("Unsupported proxy protocol in URL: $proxyUrl", null)
                        }
                    }
                } catch (e: Exception) {
                    Logger.logError("Invalid proxy URL format: $proxyUrl", e)
                }
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20000L
            connectTimeoutMillis = 15000L
            socketTimeoutMillis = 15000L
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
