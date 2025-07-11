package com.mangacombiner.service

import com.mangacombiner.ui.viewmodel.OperationState
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
import io.ktor.utils.io.core.isNotEmpty
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.*
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
        const val BUFFER_SIZE = 4096
        const val INCOMPLETE_MARKER_FILE = ".incomplete"
    }

    private suspend fun writeChannelToFile(channel: ByteReadChannel, file: File, isPaused: () -> Boolean) {
        file.outputStream().use { output ->
            while (!channel.isClosedForRead) {
                while (isPaused()) {
                    delay(500)
                }
                coroutineContext.ensureActive()
                val packet = channel.readRemaining(BUFFER_SIZE.toLong())
                while (packet.isNotEmpty) {
                    val bytes = packet.readBytes()
                    output.write(bytes)
                }
            }
        }
    }

    private suspend fun downloadFile(
        client: HttpClient,
        url: String,
        outputFile: File,
        userAgent: String,
        isPaused: () -> Boolean,
        referer: String?
    ): Boolean {
        if (outputFile.exists()) {
            Logger.logDebug { "File already exists, skipping: ${outputFile.name}" }
            return true
        }

        return try {
            while (isPaused()) {
                delay(500)
            }
            coroutineContext.ensureActive()
            Logger.logDebug { "Downloading file: $url with User-Agent: $userAgent" }
            val response: HttpResponse = client.get(url) {
                header(HttpHeaders.UserAgent, userAgent)
                if (referer != null) {
                    header(HttpHeaders.Referrer, referer)
                }
            }
            if (response.status.isSuccess()) {
                writeChannelToFile(response.bodyAsChannel(), outputFile, isPaused)
                Logger.logDebug { "Successfully downloaded ${outputFile.name}" }
                true
            } else {
                Logger.logError("Failed to download $url. Status: ${response.status}")
                false
            }
        } catch (e: IOException) {
            Logger.logError("Error downloading file $url", e)
            false
        }
    }

    private suspend fun downloadChapter(
        options: DownloadOptions,
        chapterUrl: String,
        chapterTitle: String,
        baseDir: File,
        client: HttpClient,
    ): ChapterDownloadResult {
        val chapterDir = File(baseDir, FileUtils.sanitizeFilename(chapterTitle))
        if (!chapterDir.exists()) chapterDir.mkdirs()

        val incompleteMarker = File(chapterDir, INCOMPLETE_MARKER_FILE)
        incompleteMarker.createNewFile()
        Logger.logDebug { "Created incomplete marker for chapter '$chapterTitle' at: ${incompleteMarker.absolutePath}" }

        while (options.isPaused()) {
            delay(1000)
        }
        coroutineContext.ensureActive()

        val scraperUserAgent = options.getUserAgents().random()
        val imageUrls = scraperService.findImageUrls(client, chapterUrl, scraperUserAgent, options.seriesUrl)
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
                        while (options.isPaused()) {
                            delay(500)
                        }
                        coroutineContext.ensureActive()

                        delay(Random.nextLong(IMAGE_DOWNLOAD_DELAY_MS.first, IMAGE_DOWNLOAD_DELAY_MS.last))
                        val imageUserAgent = options.getUserAgents().random()
                        val extension = imageUrl.substringAfterLast('.', "jpg").substringBefore('?')
                        val outputFile = File(chapterDir, "page_${String.format(Locale.ROOT, "%03d", index + 1)}.$extension")

                        if (!downloadFile(client, imageUrl, outputFile, imageUserAgent, options.isPaused, chapterUrl)) {
                            failedImageUrls.add(imageUrl)
                        }

                        val currentProcessed = processedImagesCount.incrementAndGet()
                        val imageProgress = currentProcessed.toFloat() / imageUrls.size
                        val statusText = "Downloading: ${chapterTitle.take(30)}... ($currentProcessed/${imageUrls.size})"
                        options.onProgressUpdate(imageProgress, statusText)
                    }
                }
            }.joinAll()
        }

        coroutineContext.ensureActive()

        val downloadedCount = chapterDir.listFiles()?.count { it.isFile && it.extension.lowercase() in ProcessorService.IMAGE_EXTENSIONS } ?: 0
        return if (downloadedCount > 0) {
            if (failedImageUrls.isNotEmpty()) {
                Logger.logError("Warning: Failed to download ${failedImageUrls.size} images for chapter: '$chapterTitle'")
            } else {
                Logger.logDebug { "Download of chapter '$chapterTitle' completed successfully. Deleting incomplete marker." }
                incompleteMarker.delete()
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
        val chapterData = options.chaptersToDownload.toList().sortedWith(compareBy(naturalSortComparator) { it.second })
        Logger.logInfo("Downloading ${chapterData.size} chapters...")
        val successfulFolders = mutableListOf<File>()
        val failedChapters = mutableMapOf<String, List<String>>()

        val client = createHttpClient(null)

        try {
            for ((index, chapter) in chapterData.withIndex()) {
                val (url, title) = chapter
                while (options.isPaused()) {
                    delay(1000)
                }
                coroutineContext.ensureActive()

                Logger.logInfo("--> Processing chapter ${index + 1}/${chapterData.size}: $title")

                val result = downloadChapter(options, url, title, downloadDir, client)

                result.chapterDir?.let { successfulFolders.add(it) }
                if (result.failedImageUrls.isNotEmpty()) {
                    failedChapters[title] = result.failedImageUrls
                }
                options.onChapterCompleted()

                coroutineContext.ensureActive()
                if (index < chapterData.lastIndex) {
                    val randomDelay = Random.nextLong(CHAPTER_DOWNLOAD_DELAY_MS.first, CHAPTER_DOWNLOAD_DELAY_MS.last)
                    Logger.logDebug { "Delaying for ${randomDelay}ms before next chapter." }
                    delay(randomDelay)
                }
            }
        } finally {
            client.close()
        }

        return DownloadResult(successfulFolders, failedChapters)
    }
}
