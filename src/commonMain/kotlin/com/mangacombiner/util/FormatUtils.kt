package com.mangacombiner.util

/**
 * Formats a size in bytes into a human-readable string (B, KB, MB).
 * @param bytes The size in bytes.
 * @return A formatted string representing the size.
 */
internal fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
        else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
    }
}
