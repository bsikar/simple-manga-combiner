package com.mangacombiner.service

import com.mangacombiner.util.getChapterPageCountsFromEpub
import com.mangacombiner.util.getChapterPageCountsFromZip
import com.mangacombiner.util.inferChapterSlugsFromEpub
import com.mangacombiner.util.inferChapterSlugsFromZip
import com.mangacombiner.util.logDebug
import com.mangacombiner.util.normalizeChapterSlug
import com.mangacombiner.util.sanitizeFilename
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
import org.springframework.stereotype.Service
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Locale

@Service
class DownloadService(
    private val scraperService: ScraperService,
    private val processorService: ProcessorService,
    private val infoPageGeneratorService: InfoPageGeneratorService,
) {
    private companion object {
        const val IMAGE_DOWNLOAD_DELAY_MS = 500L
        const val CHAPTER_DOWNLOAD_DELAY_MS = 1000L
        const val BUFFER_SIZE = 4096 // 4KB buffer
    }

    private fun String.titlecase(): String = this.split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase() else char.toString()
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
            logDebug { "File already exists, skipping: ${outputFile.name}" }
            return true
        }

        return try {
            val response: HttpResponse = client.get(url)
            if (response.status.isSuccess()) {
                writeChannelToFile(response.bodyAsChannel(), outputFile)
                true
            } else {
                println("Failed to download $url. Status: ${response.status}")
                false
            }
        } catch (e: IOException) {
            println("Error downloading file $url: ${e.message}")
            logDebug { "Full error for $url: ${e.stackTraceToString()}" }
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
        val chapterDir = File(baseDir, sanitizeFilename(chapterTitle))
        if (!chapterDir.exists()) {
            chapterDir.mkdirs()
        }

        val imageUrls = scraperService.findImageUrls(client, chapterUrl)
        if (imageUrls.isEmpty()) {
            println(" --> Warning: No images found for chapter '$chapterTitle'. It might be empty or licensed.")
            return if (chapterDir.exists()) chapterDir else null
        }

        println(" --> Found ${imageUrls.size} images for '$chapterTitle'. Starting download...")
        val dispatcher = Dispatchers.IO.limitedParallelism(imageWorkers)
        coroutineScope {
            imageUrls.mapIndexed { index, imageUrl ->
                launch(dispatcher) {
                    delay(IMAGE_DOWNLOAD_DELAY_MS)
                    logDebug { "Downloading image ${index + 1}/${imageUrls.size}: $imageUrl" }
                    val extension = imageUrl.substringAfterLast('.', "jpg").substringBefore('?')
                    val outputFile =
                        File(chapterDir, "page_${String.format(Locale.ROOT, "%03d", index + 1)}.$extension")
                    downloadFile(client, imageUrl, outputFile)
                }
            }.joinAll()
        }

        val downloadedCount = chapterDir.listFiles()?.count { it.isFile } ?: 0
        return if (downloadedCount > 0) {
            logDebug { "Finished download for chapter: '$chapterTitle' ($downloadedCount images)" }
            chapterDir
        } else {
            logDebug { "Warning: Failed to download any images for chapter: '$chapterTitle'" }
            chapterDir.takeIf { it.exists() }
        }
    }

    private suspend fun handleEpubSync(
        options: SyncOptions,
        localFile: File,
        urlsToDownload: List<String>
    ) {
        println(
            "\nIMPORTANT: EPUB updates are not merged. New chapters will be downloaded to a separate folder."
        )
        val updatesDir = File(options.tempDir, "${localFile.nameWithoutExtension}-updates").apply { mkdirs() }
        println("Downloading new chapters to: ${updatesDir.absolutePath}")
        // **FIXED**: Correctly pass arguments to downloadChapter.
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
        val syncDir = Files.createTempDirectory(options.tempDir.toPath(), "manga-sync-").toFile()
        try {
            // **FIXED**: Correctly pass arguments to downloadChapter.
            // Since we only have URLs here, we derive the title from the URL slug as a fallback.
            urlsToDownload.forEach { url ->
                val title = url.substringAfterLast("/")
                downloadChapter(options.client, url, title, syncDir, options.imageWorkers)
            }
            if (localFile.exists()) processorService.extractZip(localFile, syncDir)
            val allChapterFolders = syncDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
            processorService.createCbzFromFolders(localFile.nameWithoutExtension, allChapterFolders, localFile)
            println("\n--- Sync complete for: ${localFile.name} ---")
        } finally {
            if (syncDir.exists()) syncDir.deleteRecursively()
        }
    }

    suspend fun syncLocalSource(options: SyncOptions) {
        val localFile = File(options.localPath)
        if (!localFile.exists()) {
            println("Error: Local file not found at ${options.localPath}")
            return
        }

        println("--- Starting Sync for: ${localFile.name} ---")
        val onlineUrls = scraperService.findChapterUrls(options.client, options.seriesUrl)
        if (onlineUrls.isNotEmpty()) {
            val urlsToDownload = getUrlsToDownload(options, onlineUrls, localFile)
            if (urlsToDownload.isNotEmpty()) {
                val chapterSlugs = urlsToDownload.joinToString { it.substringAfterLast("/") }
                println("Found ${urlsToDownload.size} chapters to download/update: $chapterSlugs")
                if (localFile.extension.equals("epub", true)) {
                    handleEpubSync(options, localFile, urlsToDownload)
                } else {
                    handleCbzSync(options, localFile, urlsToDownload)
                }
            } else {
                println("Source is already up-to-date.")
            }
        } else {
            println("Could not retrieve chapter list from source. Aborting sync.")
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
        val onlineSlugsNormalized = onlineSlugsRaw.associateBy { normalizeChapterSlug(it) }

        return if (options.checkPageCounts) {
            println("Performing thorough check for missing and incomplete chapters...")
            findMissingChaptersThorough(localFile, onlineSlugsNormalized, onlineSlugToUrl, options.client)
        } else {
            println("Performing fast check for missing chapters...")
            findMissingChaptersFast(localFile, onlineSlugsNormalized, onlineSlugToUrl)
        }
    }

    private fun findMissingChaptersFast(
        localFile: File,
        onlineSlugsNormalized: Map<String, String>,
        onlineSlugToUrl: Map<String, String>
    ): List<String> {
        val localSlugsRaw = if (localFile.extension.equals("epub", true)) {
            inferChapterSlugsFromEpub(localFile)
        } else {
            inferChapterSlugsFromZip(localFile)
        }
        val localSlugsNormalized = localSlugsRaw.map { normalizeChapterSlug(it) }.toSet()
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
            getChapterPageCountsFromEpub(localFile)
        } else {
            getChapterPageCountsFromZip(localFile)
        }
        val localChapterDataNormalized = localChapterDataRaw.mapKeys { normalizeChapterSlug(it.key) }

        return coroutineScope {
            onlineSlugsNormalized.map { (normalizedSlug, rawSlug) ->
                async(Dispatchers.IO) {
                    val localPageCount = localChapterDataNormalized[normalizedSlug]
                    val url = onlineSlugToUrl[rawSlug]!!
                    if (localPageCount == null) {
                        println(" -> New chapter found: $rawSlug")
                        url
                    } else {
                        val onlineImageUrls = scraperService.findImageUrls(client, url)
                        if (onlineImageUrls.size > localPageCount) {
                            println(
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
        println("Downloading ${chapterData.size} chapters...")
        val downloadedFolders = mutableListOf<File>()
        chapterData.forEachIndexed { index, (url, title) ->
            println("--> Processing chapter ${index + 1}/${chapterData.size}: $title")
            downloadChapter(options.client, url, title, downloadDir, options.imageWorkers)?.let {
                downloadedFolders.add(it)
            }
            delay(CHAPTER_DOWNLOAD_DELAY_MS) // Politeness delay between chapters
        }
        return downloadedFolders
    }

    private suspend fun processDownloadedChapters(
        options: DownloadOptions,
        mangaTitle: String,
        downloadedFolders: List<File>,
        tempDir: File
    ) {
        if (downloadedFolders.isEmpty() && !options.generateInfoPage) {
            println("\nNo chapters were downloaded. Exiting.")
            return
        }

        var infoPageFile: File? = null
        if (options.generateInfoPage) {
            println("Generating informational page...")
            val totalPages = downloadedFolders.sumOf { it.listFiles()?.count { f -> f.isFile } ?: 0 }
            val lastUpdated = scraperService.findLastUpdateTime(options.client, options.seriesUrl)
            infoPageFile = infoPageGeneratorService.create(
                InfoPageGeneratorService.InfoPageData(
                    title = mangaTitle,
                    sourceUrl = options.seriesUrl,
                    lastUpdated = lastUpdated,
                    chapterCount = downloadedFolders.size,
                    pageCount = totalPages,
                    tempDir = tempDir
                )
            )
        }

        val outputFile = File(".", "${sanitizeFilename(mangaTitle)}.${options.format}")
        if (options.format == "cbz") {
            processorService.createCbzFromFolders(mangaTitle, downloadedFolders, outputFile, infoPageFile)
        } else {
            processorService.createEpubFromFolders(mangaTitle, downloadedFolders, outputFile, infoPageFile)
        }
        println("\nDownload and packaging complete: ${outputFile.name}")
        infoPageFile?.delete() // Clean up the generated info page
    }

    suspend fun downloadNewSeries(options: DownloadOptions) {
        println("URL detected. Starting download process...")
        val mangaTitle = options.cliTitle ?: options.seriesUrl.substringAfterLast("/manga/", "")
            .substringBefore('/')
            .replace('-', ' ')
            .titlecase()

        println("Fetching chapter list for: $mangaTitle")
        var chapterData = scraperService.findChapterUrlsAndTitles(options.client, options.seriesUrl)
        if (chapterData.isEmpty()) {
            println("Could not find any chapters at the provided URL.")
            return
        }
        if (options.exclude.isNotEmpty()) {
            chapterData = chapterData.filter { (url, _) ->
                url.trimEnd('/').substringAfterLast('/') !in options.exclude
            }
        }
        if (chapterData.isEmpty()) {
            println("No chapters left to download after exclusions.")
            return
        }

        val downloadDir = Files.createTempDirectory(options.tempDir.toPath(), "manga-dl-").toFile()
        try {
            val downloadedFolders = downloadChapters(options, chapterData, downloadDir)
            processDownloadedChapters(options, mangaTitle, downloadedFolders, options.tempDir)
        } finally {
            if (downloadDir.exists()) downloadDir.deleteRecursively()
        }
    }
}
