package com.mangacombiner.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout

actual fun createHttpClient(userAgent: String): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) { requestTimeoutMillis = 60000L }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 3)
        exponentialDelay()
    }
}
