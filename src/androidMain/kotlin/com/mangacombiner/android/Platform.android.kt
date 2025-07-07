package com.mangacombiner.util

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

// Use Koin to get the Android Context when needed
object ContextProvider : KoinComponent {
    val context: Context by inject()
}

actual fun createHttpClient(userAgent: String): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) { requestTimeoutMillis = 60000L }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 3)
        exponentialDelay()
    }
    install(UserAgent) { agent = userAgent }
}

actual fun getTmpDir(): String = ContextProvider.context.cacheDir.absolutePath
