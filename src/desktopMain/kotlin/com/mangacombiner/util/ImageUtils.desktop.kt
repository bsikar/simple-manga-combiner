package com.mangacombiner.util

import java.io.File
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream

/**
 * Desktop-specific implementation for getting image dimensions using Java's ImageIO.
 */
actual fun getImageDimensions(path: String): ImageDimensions? {
    val file = File(path)
    if (!file.exists() || !file.isFile || !file.canRead()) return null

    var stream: ImageInputStream? = null
    try {
        // Use an ImageInputStream to allow reading metadata without the whole file
        stream = ImageIO.createImageInputStream(file)
        if (stream == null) {
            Logger.logDebug { "Could not create ImageInputStream for $path, falling back to full read." }
            return ImageIO.read(file)?.let { ImageDimensions(it.width, it.height) }
        }

        val readers = ImageIO.getImageReaders(stream)
        if (readers.hasNext()) {
            val reader = readers.next()
            try {
                reader.input = stream
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                return ImageDimensions(width, height)
            } finally {
                // Good practice to dispose of the reader to free resources
                reader.dispose()
            }
        } else {
            // This is a fallback in case no suitable reader is found for the format
            Logger.logDebug { "No ImageReader found for $path, falling back to full read." }
            return ImageIO.read(file)?.let { ImageDimensions(it.width, it.height) }
        }
    } catch (e: Exception) {
        Logger.logError("Failed to get image dimensions for path: $path", e)
        return null
    } finally {
        // Ensure the stream is always closed
        stream?.close()
    }
}
