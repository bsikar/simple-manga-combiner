package com.mangacombiner.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.net.MalformedURLException
import java.net.URL

actual fun createHttpClient(proxyUrl: String?): HttpClient = HttpClient(CIO) {
    engine {
        if (!proxyUrl.isNullOrBlank()) {
            try {
                proxy = ProxyBuilder.url(URL(proxyUrl))
                Logger.logInfo("Using proxy: $proxyUrl")
            } catch (e: MalformedURLException) {
                Logger.logError("Invalid proxy URL format: $proxyUrl", e)
            }
        }
    }
    install(HttpTimeout) { requestTimeoutMillis = 60000L }
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
