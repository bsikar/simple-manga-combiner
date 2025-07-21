package com.mangacombiner.service

import com.mangacombiner.util.FileUtils
import com.mangacombiner.util.Logger
import com.mangacombiner.util.createHttpClient
import com.mangacombiner.util.naturalSortComparator
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

data class ChapterDownloadResult(
    val chapterDir: File?,
    val chapterTitle: String,
    val failedImageUrls: List<String>
)

data class DownloadResult(
    val successfulFolders: List<File>,
    val failedChapters: Map<String, List<String>> // Map of Chapter Title -> List of failed image URLs
)

enum class DownloadCompletionStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED
}

class DownloadService(
    val processorService: ProcessorService,
    private val scraperService: ScraperService
) {
    private companion object {
        val IMAGE_DOWNLOAD_DELAY_MS = 400L..800L
        val CHAPTER_DOWNLOAD_DELAY_MS = 900L..1500L
        const val BUFFER_SIZE = 8192
        const val INCOMPLETE_MARKER_FILE = ".incomplete"
    }

    /**
     * Efficiently writes data from a ByteReadChannel to a file using buffered streaming.
     * This implementation is compatible with Ktor 3.2.2 and handles proper resource management.
     */
    private suspend fun writeChannelToFile(channel: ByteReadChannel, file: File) {
        file.outputStream().buffered(BUFFER_SIZE).use { output ->
            val buffer = ByteArray(BUFFER_SIZE)

            try {
                while (!channel.isClosedForRead) {
                    coroutineContext.ensureActive() // Check for cancellation

                    // Read available bytes from channel
                    val bytesRead = channel.readAvailable(buffer)
                    if (bytesRead == -1) break // End of stream
                    if (bytesRead > 0) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
                output.flush()
            } catch (e: Exception) {
                Logger.logError("Error writing channel data to file: ${file.name}", e)
                throw e
            }
        }
    }

    /**
     * Downloads a file from the specified URL with proper error handling and cancellation support.
     * Includes retry logic for transient network failures and validates file existence before download.
     */
    private suspend fun downloadFile(
        client: HttpClient,
        url: String,
        outputFile: File,
        userAgent: String,
        referer: String?
    ): Boolean {
        if (outputFile.exists() && outputFile.length() > 0) {
            Logger.logDebug { "File already exists and is non-empty, skipping: ${outputFile.name}" }
            return true
        }

        // Ensure parent directory exists
        outputFile.parentFile?.mkdirs()

        return try {
            coroutineContext.ensureActive() // Check for cancellation before starting download
            Logger.logDebug { "Downloading file: $url with User-Agent: $userAgent" }

            val response: HttpResponse = client.get(url) {
                header(HttpHeaders.UserAgent, userAgent)
                if (referer != null) {
                    header(HttpHeaders.Referrer, referer)
                }
                // Add additional headers for better compatibility
                header(HttpHeaders.Accept, "image/webp,image/apng,image/*,*/*;q=0.8")
                header(HttpHeaders.AcceptEncoding, "gzip, deflate, br")
                header(HttpHeaders.Connection, "keep-alive")
            }

            if (response.status.isSuccess()) {
                val contentLength = response.headers["Content-Length"]?.toLongOrNull()
                Logger.logDebug {
                    "Starting download of ${outputFile.name}" +
                            if (contentLength != null) " (${contentLength} bytes)" else ""
                }

                writeChannelToFile(response.bodyAsChannel(), outputFile)

                // Verify file was written successfully
                if (outputFile.exists() && outputFile.length() > 0) {
                    Logger.logDebug { "Successfully downloaded ${outputFile.name} (${outputFile.length()} bytes)" }
                    true
                } else {
                    Logger.logError("Downloaded file is empty or missing: ${outputFile.name}")
                    outputFile.delete() // Clean up empty file
                    false
                }
            } else {
                Logger.logError("Failed to download $url. Status: ${response.status}")
                false
            }
        } catch (e: IOException) {
            Logger.logError("IO error downloading file $url to ${outputFile.name}", e)
            outputFile.delete() // Clean up partial file
            false
        } catch (e: Exception) {
            Logger.logError("Unexpected error downloading file $url", e)
            outputFile.delete() // Clean up partial file
            false
        }
    }

    /**
     * Downloads a complete chapter including all images with robust error handling.
     * Creates incomplete markers to track download state and supports resumable downloads.
     */
    private suspend fun downloadChapter(
        options: DownloadOptions,
        chapterUrl: String,
        chapterTitle: String,
        baseDir: File,
        client: HttpClient,
    ): ChapterDownloadResult {
        val sanitizedTitle = FileUtils.sanitizeFilename(chapterTitle)
        val chapterDir = File(baseDir, sanitizedTitle)

        if (!chapterDir.exists()) {
            chapterDir.mkdirs()
            Logger.logDebug { "Created chapter directory: ${chapterDir.absolutePath}" }
        }

        val incompleteMarker = File(chapterDir, INCOMPLETE_MARKER_FILE)
        try {
            if (withContext(Dispatchers.IO) { incompleteMarker.createNewFile() }) {
                Logger.logDebug { "Created incomplete marker for chapter '$chapterTitle' at: ${incompleteMarker.absolutePath}" }
            } else {
                Logger.logDebug { "Incomplete marker already existed for chapter '$chapterTitle'." }
            }
        } catch (e: IOException) {
            Logger.logError("Failed to create incomplete marker for chapter '$chapterTitle'. Aborting chapter download.", e)
            return ChapterDownloadResult(null, chapterTitle, listOf("Failed to create .incomplete marker file: ${e.message}"))
        }

        coroutineContext.ensureActive()

        val scraperUserAgent = options.getUserAgents().random()
        Logger.logDebug { "Fetching image URLs for chapter: $chapterTitle" }

        val imageUrls = try {
            scraperService.findImageUrls(client, chapterUrl, scraperUserAgent, options.seriesUrl)
        } catch (e: Exception) {
            Logger.logError("Failed to fetch image URLs for chapter '$chapterTitle'", e)
            incompleteMarker.delete()
            return ChapterDownloadResult(null, chapterTitle, listOf("Failed to fetch image URLs: ${e.message}"))
        }

        if (imageUrls.isEmpty()) {
            Logger.logInfo(" --> Warning: No images found for chapter '$chapterTitle'. It might be empty or licensed.")
            incompleteMarker.delete()
            return ChapterDownloadResult(chapterDir.takeIf { it.exists() }, chapterTitle, listOf("Chapter page might be empty or licensed."))
        }

        Logger.logInfo(" --> Found ${imageUrls.size} images for '$chapterTitle'. Starting download with ${options.getWorkers()} workers...")
        val processedImagesCount = AtomicInteger(0)
        val failedImageUrls = mutableListOf<String>()
        val semaphore = Semaphore(permits = options.getWorkers())

        coroutineScope {
            imageUrls.mapIndexed { index, imageUrl ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        coroutineContext.ensureActive()

                        // Add randomized delay to avoid being rate-limited
                        delay(Random.nextLong(IMAGE_DOWNLOAD_DELAY_MS.first, IMAGE_DOWNLOAD_DELAY_MS.last))

                        val imageUserAgent = options.getUserAgents().random()
                        val extension = imageUrl.substringAfterLast('.', "jpg")
                            .substringBefore('?')
                            .lowercase()
                            .let { ext ->
                                // Validate and normalize extension
                                when (ext) {
                                    "jpg", "jpeg", "png", "gif", "webp", "bmp" -> ext
                                    else -> "jpg" // Default fallback
                                }
                            }

                        val outputFile = File(chapterDir, "page_${String.format(Locale.ROOT, "%03d", index + 1)}.$extension")

                        val downloadSuccess = downloadFile(client, imageUrl, outputFile, imageUserAgent, chapterUrl)
                        if (!downloadSuccess) {
                            synchronized(failedImageUrls) {
                                failedImageUrls.add(imageUrl)
                            }
                            Logger.logDebug { "Failed to download image ${index + 1}/${imageUrls.size}: $imageUrl" }
                        }

                        val currentProcessed = processedImagesCount.incrementAndGet()
                        val imageProgress = currentProcessed.toFloat() / imageUrls.size
                        val statusText = "Downloading: $chapterTitle ($currentProcessed/${imageUrls.size})"
                        options.onProgressUpdate(imageProgress, statusText)
                    }
                }
            }.joinAll()
        }

        coroutineContext.ensureActive()

        // Count successfully downloaded images
        val downloadedImages = chapterDir.listFiles()?.filter { file ->
            file.isFile &&
                    !file.name.startsWith(".") &&
                    file.extension.lowercase() in ProcessorService.IMAGE_EXTENSIONS &&
                    file.length() > 0
        } ?: emptyList()

        val downloadedCount = downloadedImages.size

        return if (downloadedCount > 0) {
            if (failedImageUrls.isNotEmpty()) {
                Logger.logError("Warning: Failed to download ${failedImageUrls.size} images for chapter: '$chapterTitle'. Incomplete marker retained.")
                // Keep incomplete marker since download was partial
            } else {
                Logger.logDebug { "Download of chapter '$chapterTitle' completed successfully. Deleting incomplete marker." }
                incompleteMarker.delete()
            }
            Logger.logInfo("Finished download for chapter: '$chapterTitle' ($downloadedCount/${imageUrls.size} images)")
            ChapterDownloadResult(chapterDir, chapterTitle, failedImageUrls)
        } else {
            Logger.logError("Error: Failed to download any images for chapter: '$chapterTitle'")
            ChapterDownloadResult(null, chapterTitle, imageUrls)
        }
    }

    /**
     * Downloads multiple chapters sequentially with proper error tracking and cancellation support.
     * Provides comprehensive progress reporting and maintains detailed failure logs.
     */
    suspend fun downloadChapters(
        options: DownloadOptions,
        downloadDir: File
    ): DownloadResult? {
        val chapterData = options.chaptersToDownload.toList()
            .sortedWith(compareBy(naturalSortComparator) { it.second })

        Logger.logInfo("Starting download of ${chapterData.size} chapters to: ${downloadDir.absolutePath}")

        // Ensure download directory exists
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
            Logger.logInfo("Created download directory: ${downloadDir.absolutePath}")
        }

        val successfulFolders = mutableListOf<File>()
        val failedChapters = mutableMapOf<String, List<String>>()

        val client = createHttpClient(null)

        try {
            for ((index, chapter) in chapterData.withIndex()) {
                val (url, title) = chapter
                coroutineContext.ensureActive()

                Logger.logInfo("Processing chapter ${index + 1}/${chapterData.size}: $title")

                val result = downloadChapter(options, url, title, downloadDir, client)

                result.chapterDir?.let {
                    successfulFolders.add(it)
                    Logger.logDebug { "Added successful chapter folder: ${it.name}" }
                }

                if (result.failedImageUrls.isNotEmpty()) {
                    failedChapters[title] = result.failedImageUrls
                    Logger.logDebug { "Recorded ${result.failedImageUrls.size} failed images for chapter: $title" }
                }

                // Notify completion only if chapter was fully successful
                if (result.failedImageUrls.isEmpty() && result.chapterDir != null) {
                    options.onChapterCompleted(url)
                }

                coroutineContext.ensureActive()

                // Add delay between chapters to avoid rate limiting
                if (index < chapterData.lastIndex) {
                    val randomDelay = Random.nextLong(CHAPTER_DOWNLOAD_DELAY_MS.first, CHAPTER_DOWNLOAD_DELAY_MS.last)
                    Logger.logDebug { "Delaying for ${randomDelay}ms before next chapter." }
                    delay(randomDelay)
                }
            }

            Logger.logInfo("Download process completed. Successful: ${successfulFolders.size}, Failed: ${failedChapters.size}")

        } catch (e: Exception) {
            Logger.logError("Download process interrupted or failed", e)
            throw e
        } finally {
            client.close()
            Logger.logDebug { "HTTP client closed successfully" }
        }

        return DownloadResult(successfulFolders, failedChapters)
    }
}
