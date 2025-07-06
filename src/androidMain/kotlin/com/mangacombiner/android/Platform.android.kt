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

// For Android, we don't have a shared MangaCombinerRunner, so we define a generic user agent
private val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36"

actual fun createHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) { requestTimeoutMillis = 60000L }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 3)
        exponentialDelay()
    }
    install(UserAgent) { agent = BROWSER_USER_AGENT }
}

actual fun getTmpDir(): String = ContextProvider.context.cacheDir.absolutePath
