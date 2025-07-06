package com.mangacombiner.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent

private const val REQUEST_TIMEOUT_MS = 60000L
private const val MAX_RETRIES = 3
private val BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

actual fun createHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = MAX_RETRIES)
        exponentialDelay()
    }
    install(UserAgent) { agent = BROWSER_USER_AGENT }
}

actual fun getTmpDir(): String = System.getProperty("java.io.tmpdir")
