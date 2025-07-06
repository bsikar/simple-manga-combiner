package com.mangacombiner.util

import java.util.concurrent.atomic.AtomicBoolean

object Logger {
    private val debugEnabled = AtomicBoolean(false)

    var isDebugEnabled: Boolean
        get() = debugEnabled.get()
        set(value) = debugEnabled.set(value)

    inline fun logDebug(message: () -> String) {
        if (isDebugEnabled) {
            println("[DEBUG] ${message()}")
        }
    }

    fun logError(message: String, throwable: Throwable? = null) {
        println("[ERROR] $message")
        if (isDebugEnabled && throwable != null) {
            println("[ERROR] Stack trace: ${throwable.stackTraceToString()}")
        }
    }
}
