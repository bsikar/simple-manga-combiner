package com.mangacombiner.util

import android.graphics.BitmapFactory

/**
 * Android-specific implementation for getting image dimensions using BitmapFactory.
 */
actual fun getImageDimensions(path: String): ImageDimensions? {
    return try {
        val options = BitmapFactory.Options().apply {
            // This flag tells the decoder to avoid allocating memory for the pixels,
            // reading only the image's dimensions into options.outWidth and options.outHeight.
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            ImageDimensions(options.outWidth, options.outHeight)
        } else {
            null
        }
    } catch (e: Exception) {
        Logger.logError("Failed to get image dimensions for path: $path", e)
        null
    }
}
