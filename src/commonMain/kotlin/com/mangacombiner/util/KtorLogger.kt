package com.mangacombiner.util

/**
 * An adapter to bridge Ktor's logging with the app's custom Logger.
 * This allows HTTP client logs to be enabled/disabled via the debug setting.
 */
class KtorLogger : io.ktor.client.plugins.logging.Logger {
    override fun log(message: String) {
        // We prepend a string to make it easy to filter these logs
        Logger.logDebug { "[HttpClient] $message" }
    }
}
