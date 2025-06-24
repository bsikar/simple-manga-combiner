package com.mangacombiner.downloader

import com.mangacombiner.scraper.WPMangaScraper
import com.mangacombiner.util.logDebug
import com.mangacombiner.util.sanitizeFilename
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import java.io.File

/**
 * Handles the downloading of manga chapters and images.
 */
object Downloader {

    private suspend fun downloadFile(client: HttpClient, url: String, outputFile: File): Boolean {
        if (outputFile.exists()) {
            logDebug { "File already exists, skipping: ${outputFile.name}" }
            return true
        }
        return try {
            val response: HttpResponse = client.get(url)
            if (response.status.isSuccess()) {
                val byteChannel: ByteReadChannel = response.bodyAsChannel()
                byteChannel.copyAndClose(outputFile.writeChannel())
                true
            } else {
                println("Failed to download ${url}. Status: ${response.status}")
                false
            }
        } catch (e: Exception) {
            println("Error downloading file $url: ${e.message}")
            logDebug { "Full error for $url: ${e.stackTraceToString()}" }
            false
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun downloadChapter(
        client: HttpClient,
        chapterUrl: String,
        baseDir: File,
        imageWorkers: Int
    ): File? {
        val chapterSlug = chapterUrl.trimEnd('/').substringAfterLast('/')
        val chapterDir = File(baseDir, sanitizeFilename(chapterSlug))
        if (!chapterDir.exists()) {
            chapterDir.mkdirs()
        }

        val imageUrls = WPMangaScraper.findImageUrls(client, chapterUrl)
        if (imageUrls.isEmpty()) {
            println(" --> Warning: No images found for chapter $chapterSlug. It might be empty or licensed.")
            return if (chapterDir.exists()) chapterDir else null
        }

        println(" --> Found ${imageUrls.size} images for $chapterSlug. Starting download...")
        val dispatcher = Dispatchers.IO.limitedParallelism(imageWorkers)
        coroutineScope {
            imageUrls.mapIndexed { index, imageUrl ->
                launch(dispatcher) {
                    delay(500L) // Polite 0.5s delay between each image request
                    logDebug { "Downloading image ${index + 1}/${imageUrls.size}: $imageUrl" }
                    val extension = imageUrl.substringAfterLast('.').substringBefore('?')
                    val outputFile = File(chapterDir, "page_${String.format("%03d", index + 1)}.$extension")
                    downloadFile(client, imageUrl, outputFile)
                }
            }.joinAll()
        }

        val downloadedCount = chapterDir.listFiles()?.count { it.isFile } ?: 0
        return if (downloadedCount > 0) {
            println(" --> Finished download for chapter: $chapterSlug ($downloadedCount images)")
            chapterDir
        } else {
            println(" --> Warning: Failed to download any images for chapter: $chapterSlug")
            chapterDir
        }
    }
}
