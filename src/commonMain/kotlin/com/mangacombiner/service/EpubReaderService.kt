package com.mangacombiner.service

data class Book(
    val filePath: String,
    val title: String,
    val coverImage: ByteArray?,
    val chapters: List<ChapterContent>
)

data class ChapterContent(
    val title: String,
    val imageResources: List<ByteArray>
)

expect class EpubReaderService {
    suspend fun parseEpub(filePath: String): Book?
}
