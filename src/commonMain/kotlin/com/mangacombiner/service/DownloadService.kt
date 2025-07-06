package com.mangacombiner.service

import com.mangacombiner.util.FileUtils
import com.mangacombiner.util.Logger
import com.mangacombiner.util.SlugUtils
import com.mangacombiner.util.ZipUtils
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Locale

class DownloadService(
    private val scraperService: ScraperService,
    private val processorService: ProcessorService
) {
    private companion object {
        const val IMAGE_DOWNLOAD_DELAY_MS = 500L
        const val CHAPTER_DOWNLOAD_DELAY_MS = 1000L
        const val BUFFER_SIZE = 4096 // 4KB buffer
    }

    private fun String.titlecase(): String = this.split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
        }
    }

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
        client: HttpClient,
        chapterUrl: String,
        chapterTitle: String,
        baseDir: File,
        imageWorkers: Int
    ): File? {
        val chapterDir = File(baseDir, FileUtils.sanitizeFilename(chapterTitle))
        if (!chapterDir.exists()) {
            chapterDir.mkdirs()
        }

        val imageUrls = scraperService.findImageUrls(client, chapterUrl)
        if (imageUrls.isEmpty()) {
            Logger.logInfo(" --> Warning: No images found for chapter '$chapterTitle'. It might be empty or licensed.")
            return if (chapterDir.exists()) chapterDir else null
        }

        Logger.logInfo(" --> Found ${imageUrls.size} images for '$chapterTitle'. Starting download...")
        val dispatcher = Dispatchers.IO.limitedParallelism(imageWorkers)
        coroutineScope {
            imageUrls.mapIndexed { index, imageUrl ->
                launch(dispatcher) {
                    delay(IMAGE_DOWNLOAD_DELAY_MS)
                    Logger.logDebug { "Downloading image ${index + 1}/${imageUrls.size}: $imageUrl" }
                    val extension = imageUrl.substringAfterLast('.', "jpg").substringBefore('?')
                    val outputFile =
                        File(chapterDir, "page_${String.format(Locale.ROOT, "%03d", index + 1)}.$extension")
                    downloadFile(client, imageUrl, outputFile)
                }
            }.joinAll()
        }

        val downloadedCount = chapterDir.listFiles()?.count { it.isFile } ?: 0
        return if (downloadedCount > 0) {
            Logger.logDebug { "Finished download for chapter: '$chapterTitle' ($downloadedCount images)" }
            chapterDir
        } else {
            Logger.logDebug { "Warning: Failed to download any images for chapter: '$chapterTitle'" }
            chapterDir.takeIf { it.exists() }
        }
    }

    private suspend fun handleEpubSync(
        options: SyncOptions,
        localFile: File,
        urlsToDownload: List<String>
    ) {
        Logger.logInfo(
            "\nIMPORTANT: EPUB updates are not merged. New chapters will be downloaded to a separate folder."
        )
        val updatesDir = File(options.tempDir, "${localFile.nameWithoutExtension}-updates").apply { mkdirs() }
        Logger.logInfo("Downloading new chapters to: ${updatesDir.absolutePath}")
        // Since we only have URLs here, we derive the title from the URL slug as a fallback.
        urlsToDownload.forEach { url ->
            val title = url.substringAfterLast("/")
            downloadChapter(options.client, url, title, updatesDir, options.imageWorkers)
        }
    }

    private suspend fun handleCbzSync(
        options: SyncOptions,
        localFile: File,
        urlsToDownload: List<String>
    ) {
        val syncDir = File(options.tempDir, "manga-sync-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // Since we only have URLs here, we derive the title from the URL slug as a fallback.
            urlsToDownload.forEach { url ->
                val title = url.substringAfterLast("/")
                downloadChapter(options.client, url, title, syncDir, options.imageWorkers)
            }
            if (localFile.exists()) processorService.extractZip(localFile, syncDir)
            val allChapterFolders = syncDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
            processorService.createCbzFromFolders(localFile.nameWithoutExtension, allChapterFolders, localFile)
            Logger.logInfo("\n--- Sync complete for: ${localFile.name} ---")
        } finally {
            if (syncDir.exists()) syncDir.deleteRecursively()
        }
    }

    suspend fun syncLocalSource(options: SyncOptions) {
        val localFile = File(options.localPath)
        if (!localFile.exists()) {
            Logger.logError("Error: Local file not found at ${options.localPath}")
            return
        }

        Logger.logInfo("--- Starting Sync for: ${localFile.name} ---")
        val onlineUrls = scraperService.findChapterUrls(options.client, options.seriesUrl)
        if (onlineUrls.isNotEmpty()) {
            val urlsToDownload = getUrlsToDownload(options, onlineUrls, localFile)
            if (urlsToDownload.isNotEmpty()) {
                val chapterSlugs = urlsToDownload.joinToString { it.substringAfterLast("/") }
                Logger.logInfo("Found ${urlsToDownload.size} chapters to download/update: $chapterSlugs")

                if (options.dryRun) {
                    Logger.logInfo("[DRY RUN] Would sync ${urlsToDownload.size} chapters into ${localFile.name}.")
                    return
                }

                if (localFile.extension.equals("epub", true)) {
                    handleEpubSync(options, localFile, urlsToDownload)
                } else {
                    handleCbzSync(options, localFile, urlsToDownload)
                }
            } else {
                Logger.logInfo("Source is already up-to-date.")
            }
        } else {
            Logger.logInfo("Could not retrieve chapter list from source. Aborting sync.")
        }
    }

    private suspend fun getUrlsToDownload(
        options: SyncOptions,
        onlineUrls: List<String>,
        localFile: File
    ): List<String> {
        val onlineSlugToUrl = onlineUrls
            .associateBy { it.trimEnd('/').substringAfterLast('/') }
            .filterKeys { it !in options.exclude }

        val onlineSlugsRaw = onlineSlugToUrl.keys
        val onlineSlugsNormalized = onlineSlugsRaw.associateBy { SlugUtils.normalizeChapterSlug(it) }

        return if (options.checkPageCounts) {
            Logger.logInfo("Performing thorough check for missing and incomplete chapters...")
            findMissingChaptersThorough(localFile, onlineSlugsNormalized, onlineSlugToUrl, options.client)
        } else {
            Logger.logInfo("Performing fast check for missing chapters...")
            findMissingChaptersFast(localFile, onlineSlugsNormalized, onlineSlugToUrl)
        }
    }

    private fun findMissingChaptersFast(
        localFile: File,
        onlineSlugsNormalized: Map<String, String>,
        onlineSlugToUrl: Map<String, String>
    ): List<String> {
        val localSlugsRaw = if (localFile.extension.equals("epub", true)) {
            ZipUtils.inferChapterSlugsFromEpub(localFile)
        } else {
            ZipUtils.inferChapterSlugsFromZip(localFile)
        }
        val localSlugsNormalized = localSlugsRaw.map { SlugUtils.normalizeChapterSlug(it) }.toSet()
        val missingNormalizedSlugs = onlineSlugsNormalized.keys - localSlugsNormalized
        val rawSlugsToDownload = missingNormalizedSlugs.mapNotNull { onlineSlugsNormalized[it] }
        return rawSlugsToDownload.mapNotNull { onlineSlugToUrl[it] }
    }

    private suspend fun findMissingChaptersThorough(
        localFile: File,
        onlineSlugsNormalized: Map<String, String>,
        onlineSlugToUrl: Map<String, String>,
        client: HttpClient
    ): List<String> {
        val localChapterDataRaw = if (localFile.extension.equals("epub", true)) {
            ZipUtils.getChapterPageCountsFromEpub(localFile)
        } else {
            ZipUtils.getChapterPageCountsFromZip(localFile)
        }
        val localChapterDataNormalized = localChapterDataRaw.mapKeys { SlugUtils.normalizeChapterSlug(it.key) }

        return coroutineScope {
            onlineSlugsNormalized.map { (normalizedSlug, rawSlug) ->
                async(Dispatchers.IO) {
                    val localPageCount = localChapterDataNormalized[normalizedSlug]
                    val url = onlineSlugToUrl[rawSlug]!!
                    if (localPageCount == null) {
                        Logger.logInfo(" -> New chapter found: $rawSlug")
                        url
                    } else {
                        val onlineImageUrls = scraperService.findImageUrls(client, url)
                        if (onlineImageUrls.size > localPageCount) {
                            Logger.logInfo(
                                " -> Incomplete chapter found: $rawSlug " +
                                        "(Local: $localPageCount, Online: ${onlineImageUrls.size})"
                            )
                            url
                        } else {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun downloadChapters(
        options: DownloadOptions,
        chapterData: List<Pair<String, String>>,
        downloadDir: File
    ): List<File> {
        Logger.logInfo("Downloading ${chapterData.size} chapters...")
        val downloadedFolders = mutableListOf<File>()
        chapterData.forEachIndexed { index, (url, title) ->
            Logger.logInfo("--> Processing chapter ${index + 1}/${chapterData.size}: $title")
            downloadChapter(options.client, url, title, downloadDir, options.imageWorkers)?.let {
                downloadedFolders.add(it)
            }
            delay(CHAPTER_DOWNLOAD_DELAY_MS)
        }
        return downloadedFolders
    }

    private suspend fun processDownloadedChapters(
        options: DownloadOptions,
        mangaTitle: String,
        downloadedFolders: List<File>,
    ) {
        if (downloadedFolders.isEmpty()) {
            Logger.logInfo("\nNo chapters were downloaded. Exiting.")
            return
        }

        val outputFile = File(".", "${FileUtils.sanitizeFilename(mangaTitle)}.${options.format}")
        if (options.format == "cbz") {
            processorService.createCbzFromFolders(mangaTitle, downloadedFolders, outputFile)
        } else {
            processorService.createEpubFromFolders(mangaTitle, downloadedFolders, outputFile)
        }
        Logger.logInfo("\nDownload and packaging complete: ${outputFile.name}")
    }

    suspend fun downloadNewSeries(options: DownloadOptions) {
        Logger.logInfo("URL detected. Starting download process...")
        val mangaTitle = options.cliTitle ?: options.seriesUrl.substringAfterLast("/manga/", "")
            .substringBefore('/')
            .replace('-', ' ')
            .titlecase()

        Logger.logInfo("Fetching chapter list for: $mangaTitle")
        var chapterData = scraperService.findChapterUrlsAndTitles(options.client, options.seriesUrl)

        if (chapterData.isEmpty()) {
            Logger.logInfo("Could not find any chapters at the provided URL.")
        } else {
            if (options.exclude.isNotEmpty()) {
                chapterData = chapterData.filter { (url, _) ->
                    url.trimEnd('/').substringAfterLast('/') !in options.exclude
                }
            }

            if (chapterData.isEmpty()) {
                Logger.logInfo("No chapters left to download after exclusions.")
            } else if (options.dryRun) {
                Logger.logInfo("[DRY RUN] Would download ${chapterData.size} chapters for series '$mangaTitle'.")
                val outputFile = File(".", "${FileUtils.sanitizeFilename(mangaTitle)}.${options.format}")
                Logger.logInfo("[DRY RUN] Would create output file: ${outputFile.name}")
            } else {
                val downloadDir = File(options.tempDir, "manga-dl-${System.currentTimeMillis()}").apply { mkdirs() }
                try {
                    val downloadedFolders = downloadChapters(options, chapterData, downloadDir)
                    processDownloadedChapters(options, mangaTitle, downloadedFolders)
                } finally {
                    downloadDir.deleteRecursively()
                }
            }
        }
    }
}
