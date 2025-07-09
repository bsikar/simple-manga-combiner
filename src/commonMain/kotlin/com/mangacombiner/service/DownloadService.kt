package com.mangacombiner.service

import com.mangacombiner.ui.viewmodel.OperationState
import com.mangacombiner.util.FileUtils
import com.mangacombiner.util.Logger
import com.mangacombiner.util.createHttpClient
import com.mangacombiner.util.titlecase
import com.mangacombiner.util.toSlug
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.isNotEmpty
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

// New data class to hold the result for a single chapter
data class ChapterDownloadResult(
    val chapterDir: File?,
    val chapterTitle: String,
    val failedImageUrls: List<String>
)

// New data class to hold the result for the entire download operation
data class DownloadResult(
    val successfulFolders: List<File>,
    val failedChapters: Map<String, List<String>> // Map of Chapter Title -> List of failed image URLs
)

// Updated status to include partial success
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
        const val IMAGE_DOWNLOAD_DELAY_MS = 500L
        const val CHAPTER_DOWNLOAD_DELAY_MS = 1000L
        const val BUFFER_SIZE = 4096
    }

    private suspend fun writeChannelToFile(channel: ByteReadChannel, file: File) {
        file.outputStream().use { output ->
            while (!channel.isClosedForRead) {
                val packet = channel.readRemaining(BUFFER_SIZE.toLong())
                while (packet.isNotEmpty) {
                    val bytes = packet.readBytes()
                    output.write(bytes)
                }
            }
        }
    }

    private suspend fun downloadFile(client: HttpClient, url: String, outputFile: File, userAgent: String): Boolean {
        if (outputFile.exists()) {
            Logger.logDebug { "File already exists, skipping: ${outputFile.name}" }
            return true
        }

        return try {
            val response: HttpResponse = client.get(url) {
                header(HttpHeaders.UserAgent, userAgent)
            }
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
        client: HttpClient,
        baseProgress: Float,
        progressWeight: Float
    ): ChapterDownloadResult {
        val chapterDir = File(baseDir, FileUtils.sanitizeFilename(chapterTitle))
        if (!chapterDir.exists()) chapterDir.mkdirs()

        while (options.operationState.value == OperationState.PAUSED) {
            delay(1000)
        }
        if (options.operationState.value != OperationState.RUNNING) {
            return ChapterDownloadResult(null, chapterTitle, emptyList())
        }

        val scraperUserAgent = options.getUserAgents().random()
        val imageUrls = scraperService.findImageUrls(client, chapterUrl, scraperUserAgent)
        if (imageUrls.isEmpty()) {
            Logger.logInfo(" --> Warning: No images found for chapter '$chapterTitle'. It might be empty or licensed.")
            return ChapterDownloadResult(chapterDir.takeIf { it.exists() }, chapterTitle, listOf("Chapter page might be empty or licensed."))
        }

        Logger.logInfo(" --> Found ${imageUrls.size} images for '$chapterTitle'. Starting download...")
        val dispatcher = Dispatchers.IO.limitedParallelism(options.getWorkers())
        val downloadedImagesCount = AtomicInteger(0)
        val failedImageUrls = mutableListOf<String>()

        coroutineScope {
            imageUrls.mapIndexed { index, imageUrl ->
                launch(dispatcher) {
                    while (options.operationState.value == OperationState.PAUSED) {
                        delay(500)
                    }
                    if (options.operationState.value == OperationState.RUNNING) {
                        delay(IMAGE_DOWNLOAD_DELAY_MS)
                        val imageUserAgent = options.getUserAgents().random()
                        Logger.logDebug { "Downloading image ${index + 1}/${imageUrls.size}: $imageUrl" }
                        val extension = imageUrl.substringAfterLast('.', "jpg").substringBefore('?')
                        val outputFile = File(chapterDir, "page_${String.format(Locale.ROOT, "%03d", index + 1)}.$extension")

                        if (downloadFile(client, imageUrl, outputFile, imageUserAgent)) {
                            downloadedImagesCount.incrementAndGet()
                        } else {
                            failedImageUrls.add(imageUrl)
                        }

                        val imageProgress = (index + 1).toFloat() / imageUrls.size
                        val currentTotalProgress = baseProgress + (imageProgress * progressWeight)
                        val statusText = "Downloading: ${chapterTitle.take(30)}... (${index + 1}/${imageUrls.size})"
                        options.onProgressUpdate(currentTotalProgress, statusText)
                    }
                }
            }.joinAll()
        }

        if (options.operationState.value != OperationState.RUNNING) {
            return ChapterDownloadResult(null, chapterTitle, failedImageUrls)
        }

        val downloadedCount = chapterDir.listFiles()?.count { it.isFile } ?: 0
        return if (downloadedCount > 0) {
            if (failedImageUrls.isNotEmpty()) {
                Logger.logError("Warning: Failed to download ${failedImageUrls.size} images for chapter: '$chapterTitle'")
            }
            Logger.logDebug { "Finished download for chapter: '$chapterTitle' ($downloadedCount images)" }
            ChapterDownloadResult(chapterDir, chapterTitle, failedImageUrls)
        } else {
            Logger.logError("Error: Failed to download any images for chapter: '$chapterTitle'")
            ChapterDownloadResult(null, chapterTitle, imageUrls)
        }
    }

    suspend fun downloadChapters(
        options: DownloadOptions,
        downloadDir: File
    ): DownloadResult? {
        val chapterData = options.chaptersToDownload.toList()
        Logger.logInfo("Downloading ${chapterData.size} chapters...")
        val successfulFolders = mutableListOf<File>()
        val failedChapters = mutableMapOf<String, List<String>>()

        val client = createHttpClient("")

        try {
            for ((index, chapter) in chapterData.withIndex()) {
                val (url, title) = chapter
                while (options.operationState.value == OperationState.PAUSED) {
                    delay(1000)
                }
                if (options.operationState.value != OperationState.RUNNING) {
                    return null
                }

                Logger.logInfo("--> Processing chapter ${index + 1}/${chapterData.size}: $title")
                val baseProgress = index.toFloat() / chapterData.size
                val progressWeight = 1f / chapterData.size

                val result = downloadChapter(options, url, title, downloadDir, client, baseProgress, progressWeight)

                result.chapterDir?.let { successfulFolders.add(it) }
                if (result.failedImageUrls.isNotEmpty()) {
                    failedChapters[title] = result.failedImageUrls
                }

                if (options.operationState.value != OperationState.RUNNING) return null
                delay(CHAPTER_DOWNLOAD_DELAY_MS)
            }
        } finally {
            client.close()
        }

        return DownloadResult(successfulFolders, failedChapters)
    }
}
