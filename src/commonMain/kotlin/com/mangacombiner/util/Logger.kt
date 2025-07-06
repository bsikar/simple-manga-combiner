package com.mangacombiner.util

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object Logger {
    private val debugEnabled = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    var isDebugEnabled: Boolean
        get() = debugEnabled.get()
        set(value) = debugEnabled.set(value)

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    internal fun broadcast(message: String) {
        println(message)
        listeners.forEach { it(message) }
    }

    // 'inline' keyword removed to fix the visibility compilation error
    fun logDebug(message: () -> String) {
        if (isDebugEnabled) {
            broadcast("[DEBUG] ${message()}")
        }
    }

    fun logInfo(message: String) {
        broadcast(message)
    }

    fun logError(message: String, throwable: Throwable? = null) {
        broadcast("[ERROR] $message")
        if (isDebugEnabled && throwable != null) {
            broadcast("[ERROR] Stack trace: ${throwable.stackTraceToString()}")
        }
    }
}
