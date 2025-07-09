package com.mangacombiner.util

import java.io.File
import javax.imageio.ImageIO

/**
 * Desktop-specific implementation for getting image dimensions using Java's ImageIO.
 */
actual fun getImageDimensions(path: String): ImageDimensions? {
    return try {
        val file = File(path)
        if (!file.exists()) return null
        ImageIO.read(file)?.let { ImageDimensions(it.width, it.height) }
    } catch (e: Exception) {
        Logger.logError("Failed to get image dimensions for path: $path", e)
        null
    }
}
