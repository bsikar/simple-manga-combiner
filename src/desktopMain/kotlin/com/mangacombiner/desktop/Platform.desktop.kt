package com.mangacombiner.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent

private const val REQUEST_TIMEOUT_MS = 60000L
private const val MAX_RETRIES = 3

actual fun createHttpClient(userAgent: String): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = MAX_RETRIES)
        exponentialDelay()
    }
    install(UserAgent) { agent = userAgent }
}

actual fun getTmpDir(): String = System.getProperty("java.io.tmpdir")
