package com.mangacombiner.service

data class Book(
    val filePath: String,
    val title: String,
    val coverImage: ByteArray?, // Cover is loaded once for the library view
    val chapters: List<ChapterContent>,
    val localCachePath: String? = null // Add this line to store the path to the temp file on Android
)

data class ChapterContent(
    val title: String,
    val imageHrefs: List<String>,
    val textContent: String? = null
)

expect class EpubReaderService {
    /**
     * Parses the EPUB structure without loading all image data into memory.
     */
    suspend fun parseEpub(filePath: String): Book?

    /**
     * Extracts the image data for a single page on-demand.
     */
    suspend fun extractImage(filePath: String, imageHref: String): ByteArray?
}
