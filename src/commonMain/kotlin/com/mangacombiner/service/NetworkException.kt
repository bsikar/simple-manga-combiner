package com.mangacombiner.service

import java.io.IOException

/**
 * A custom exception to signal that a failure was likely caused by a network issue,
 * allowing the app to handle it gracefully (e.g., by pausing instead of failing).
 */
class NetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)
