package com.mangacombiner.service

import com.mangacombiner.ui.viewmodel.OperationState
import com.mangacombiner.util.FileUtils
import com.mangacombiner.util.Logger
import com.mangacombiner.util.createHttpClient
import com.mangacombiner.util.titlecase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale

enum class DownloadCompletionStatus {
    SUCCESS,
    FAILED,
    CANCELLED
}

class DownloadService(
    private val scraperService: ScraperService,
    private val processorService: ProcessorService
) {
    private companion object {
        const val IMAGE_DOWNLOAD_DELAY_MS = 500L
        const val CHAPTER_DOWNLOAD_DELAY_MS = 1000L
        const val BUFFER_SIZE = 4096
    }

    fun String.toSlug(): String = this.lowercase()
        .replace(Regex("https?://(www\\.)?"), "") // Remove http/s and www
        .replace(Regex("[^a-z0-9]"), "-") // Replace non-alphanumeric with hyphen
        .replace(Regex("-+"), "-") // Replace multiple hyphens with one
        .trim('-')

    private suspend fun writeChannelToFile(channel: ByteReadChannel, file: File) {
        file.outputStream().use { output ->
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            while (channel.readAvailable(buffer) != -1) {
                buffer.flip()
                output.write(buffer.array(), buffer.arrayOffset(), buffer.limit())
                buffer.clear()
            }
        }
    }

    private suspend fun downloadFile(client: HttpClient, url: String, outputFile: File): Boolean {
        if (outputFile.exists()) {
            Logger.logDebug { "File already exists, skipping: ${outputFile.name}" }
            return true
        }

        return try {
            val response: HttpResponse = client.get(url)
            if (response.status.isSuccess()) {
                writeChannelToFile(response.bodyAsChannel(), outputFile)
                true
            } else {
                Logger.logError("Failed to download $url. Status: ${response.status}")
                false
            }
        } catch (e: IOException) {
            Logger.logError("Error downloading file $url: ${e.message}")
            Logger.logDebug { "Full error for $url: ${e.stackTraceToString()}" }
            false
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun downloadChapter(
        options: DownloadOptions,
        chapterUrl: String,
        chapterTitle: String,
        baseDir: File,
        client: HttpClient
    ): File? {
        val chapterDir = File(baseDir, FileUtils.sanitizeFilename(chapterTitle))
        if (!chapterDir.exists()) {
            chapterDir.mkdirs()
        }

        // Check for pause/cancel before fetching image URLs
        while (options.operationState.value == OperationState.PAUSED) {
            delay(1000)
        }
        if (options.operationState.value != OperationState.RUNNING) return null

        val imageUrls = scraperService.findImageUrls(client, chapterUrl)
        if (imageUrls.isEmpty()) {
            Logger.logInfo(" --> Warning: No images found for chapter '$chapterTitle'. It might be empty or licensed.")
            return if (chapterDir.exists()) chapterDir else null
        }

        Logger.logInfo(" --> Found ${imageUrls.size} images for '$chapterTitle'. Starting download...")
        val dispatcher = Dispatchers.IO.limitedParallelism(options.imageWorkers)
        coroutineScope {
            imageUrls.mapIndexed { index, imageUrl ->
                launch(dispatcher) {
                    while (options.operationState.value == OperationState.PAUSED) {
                        delay(500)
                    }
                    if (options.operationState.value == OperationState.RUNNING) {
                        delay(IMAGE_DOWNLOAD_DELAY_MS)
                        Logger.logDebug { "Downloading image ${index + 1}/${imageUrls.size}: $imageUrl" }
                        val extension = imageUrl.substringAfterLast('.', "jpg").substringBefore('?')
                        val outputFile =
                            File(chapterDir, "page_${String.format(Locale.ROOT, "%03d", index + 1)}.$extension")
                        downloadFile(client, imageUrl, outputFile)
                    }
                }
            }.joinAll()
        }

        if (options.operationState.value != OperationState.RUNNING) return null

        val downloadedCount = chapterDir.listFiles()?.count { it.isFile } ?: 0
        return if (downloadedCount > 0) {
            Logger.logDebug { "Finished download for chapter: '$chapterTitle' ($downloadedCount images)" }
            chapterDir
        } else {
            Logger.logDebug { "Warning: Failed to download any images for chapter: '$chapterTitle'" }
            chapterDir.takeIf { it.exists() }
        }
    }

    private suspend fun downloadChapters(
        options: DownloadOptions,
        downloadDir: File
    ): List<File>? {
        val chapterData = options.chaptersToDownload.toList()
        Logger.logInfo("Downloading ${chapterData.size} chapters...")
        val downloadedFolders = mutableListOf<File>()
        for ((url, title) in chapterData) {
            // Check for pause/cancel before each chapter
            while (options.operationState.value == OperationState.PAUSED) {
                Logger.logDebug { "Operation is paused. Waiting..." }
                delay(1000)
            }
            if (options.operationState.value != OperationState.RUNNING) {
                return null // Cancelled
            }

            val userAgent = options.userAgents.random()
            val client = createHttpClient(userAgent)
            try {
                Logger.logInfo("--> Processing chapter ${downloadedFolders.size + 1}/${chapterData.size}: $title")
                downloadChapter(options, url, title, downloadDir, client)?.let {
                    downloadedFolders.add(it)
                }
                delay(CHAPTER_DOWNLOAD_DELAY_MS)
            } finally {
                client.close()
            }
        }
        return downloadedFolders
    }

    private fun processDownloadedChapters(
        options: DownloadOptions,
        mangaTitle: String,
        downloadedFolders: List<File>,
    ) {
        if (downloadedFolders.isEmpty()) {
            Logger.logInfo("\nNo chapters were downloaded. Exiting.")
            return
        }

        val outputDir = if (options.outputPath.isNotBlank()) File(options.outputPath) else File(".")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val outputFile = File(outputDir, "${FileUtils.sanitizeFilename(mangaTitle)}.${options.format}")
        if (options.format == "cbz") {
            processorService.createCbzFromFolders(mangaTitle, downloadedFolders, outputFile)
        } else {
            processorService.createEpubFromFolders(mangaTitle, downloadedFolders, outputFile)
        }
        Logger.logInfo("\nDownload and packaging complete: ${outputFile.absolutePath}")
    }

    suspend fun downloadNewSeries(options: DownloadOptions): DownloadCompletionStatus {
        if (options.operationState.value != OperationState.RUNNING) return DownloadCompletionStatus.CANCELLED

        val mangaTitle = options.cliTitle ?: options.seriesUrl.substringAfterLast("/manga/", "")
            .substringBefore('/')
            .replace('-', ' ')
            .titlecase()

        Logger.logInfo("Processing chapter list for: $mangaTitle")

        if (options.chaptersToDownload.isEmpty()) {
            Logger.logInfo("Could not find any chapters at the provided URL or none were selected.")
            return DownloadCompletionStatus.SUCCESS
        }

        if (options.dryRun) {
            Logger.logInfo("[DRY RUN] Would download ${options.chaptersToDownload.size} chapters for series '$mangaTitle'.")
            val outputFile = File(".", "${FileUtils.sanitizeFilename(mangaTitle)}.${options.format}")
            Logger.logInfo("[DRY RUN] Would create output file: ${outputFile.name}")
            return DownloadCompletionStatus.SUCCESS
        }

        val seriesSlug = options.seriesUrl.toSlug()
        val downloadDir = File(options.tempDir, "manga-dl-$seriesSlug")
        if (downloadDir.exists()) {
            Logger.logInfo("Reusing existing temporary directory: ${downloadDir.name}")
        }
        downloadDir.mkdirs()

        try {
            val downloadedFolders = downloadChapters(options, downloadDir)

            // If the operation state changed from RUNNING, it was cancelled.
            if (options.operationState.value != OperationState.RUNNING) {
                return DownloadCompletionStatus.CANCELLED
            }

            // downloadChapters returns null if cancelled, or a list (even if empty) otherwise.
            if (downloadedFolders != null) {
                processDownloadedChapters(options, mangaTitle, downloadedFolders)
                Logger.logInfo("Cleaning up temporary files...")
                if (downloadDir.deleteRecursively()) {
                    Logger.logInfo("Cleanup successful.")
                } else {
                    Logger.logError("Failed to clean up temporary directory: ${downloadDir.absolutePath}")
                }
                return DownloadCompletionStatus.SUCCESS
            } else {
                return DownloadCompletionStatus.CANCELLED
            }
        } catch (e: Exception) {
            Logger.logError("Operation failed. Temporary files have been kept for a future resume.", e)
            return DownloadCompletionStatus.FAILED
        }
    }
}
