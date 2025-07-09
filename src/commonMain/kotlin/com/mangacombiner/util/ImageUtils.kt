package com.mangacombiner.util

/**
 * A simple data class to hold image dimensions.
 */
data class ImageDimensions(val width: Int, val height: Int)

/**
 * Defines a platform-agnostic function to get the dimensions of an image file.
 * Each platform (Android, Desktop) will provide its own implementation.
 *
 * @param path The absolute path to the image file.
 * @return An ImageDimensions object or null if dimensions cannot be read.
 */
expect fun getImageDimensions(path: String): ImageDimensions?
