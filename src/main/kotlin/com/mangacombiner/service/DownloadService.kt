package com.mangacombiner.service

import com.mangacombiner.util.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files

@Service
class DownloadService(
    private val scraperService: ScraperService,
    private val processorService: ProcessorService,
) {
    private fun String.titlecase(): String = this.split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase() else char.toString()
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
                val byteChannel: ByteReadChannel = response.bodyAsChannel()
                byteChannel.copyAndClose(outputFile.writeChannel())
                true
            } else {
                println("Failed to download $url. Status: ${response.status}")
                false
            }
        } catch (e: Exception) {
            println("Error downloading file $url: ${e.message}")
            logDebug { "Full error for $url: ${e.stackTraceToString()}" }
            false
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun downloadChapter(client: HttpClient, chapterUrl: String, baseDir: File, imageWorkers: Int): File? {
        val chapterSlug = chapterUrl.trimEnd('/').substringAfterLast('/')
        val chapterDir = File(baseDir, sanitizeFilename(chapterSlug))
        if (!chapterDir.exists()) {
            chapterDir.mkdirs()
        }

        val imageUrls = scraperService.findImageUrls(client, chapterUrl)
        if (imageUrls.isEmpty()) {
            println(" --> Warning: No images found for chapter $chapterSlug. It might be empty or licensed.")
            return if (chapterDir.exists()) chapterDir else null
        }

        println(" --> Found ${imageUrls.size} images for $chapterSlug. Starting download...")
        val dispatcher = Dispatchers.IO.limitedParallelism(imageWorkers)
        coroutineScope {
            imageUrls.mapIndexed { index, imageUrl ->
                launch(dispatcher) {
                    delay(500L) // Politeness delay
                    logDebug { "Downloading image ${index + 1}/${imageUrls.size}: $imageUrl" }
                    val extension = imageUrl.substringAfterLast('.', "jpg").substringBefore('?')
                    val outputFile = File(chapterDir, "page_${String.format("%03d", index + 1)}.$extension")
                    downloadFile(client, imageUrl, outputFile)
                }
            }.joinAll()
        }

        val downloadedCount = chapterDir.listFiles()?.count { it.isFile } ?: 0
        return if (downloadedCount > 0) {
            logDebug { "Finished download for chapter: $chapterSlug ($downloadedCount images)" }
            chapterDir
        } else {
            logDebug { "Warning: Failed to download any images for chapter: $chapterSlug" }
            chapterDir.takeIf { it.exists() }
        }
    }

    suspend fun syncLocalSource(localPath: String, seriesUrl: String, imageWorkers: Int, exclude: List<String>, checkPageCounts: Boolean, tempDir: File, client: HttpClient) {
        val localFile = File(localPath)
        if (!localFile.exists()) {
            println("Error: Local file not found at $localPath"); return
        }

        println("--- Starting Sync for: ${localFile.name} ---")
        val onlineUrls = scraperService.findChapterUrls(client, seriesUrl)
        if (onlineUrls.isEmpty()) {
            println("Could not retrieve chapter list from source. Aborting sync."); return
        }

        val onlineSlugToUrl = onlineUrls.associateBy { it.trimEnd('/').substringAfterLast('/') }.filterKeys { it !in exclude }
        val urlsToDownload = mutableListOf<String>()
        val onlineSlugsRaw = onlineSlugToUrl.keys
        val onlineSlugsNormalized = onlineSlugsRaw.associateBy { normalizeChapterSlug(it) }

        if (!checkPageCounts) {
            println("Performing fast check for missing chapters...")
            val localSlugsRaw = if (localFile.extension.equals("epub", true)) inferChapterSlugsFromEpub(localFile) else inferChapterSlugsFromZip(localFile)
            val localSlugsNormalized = localSlugsRaw.map { normalizeChapterSlug(it) }.toSet()
            val missingNormalizedSlugs = onlineSlugsNormalized.keys - localSlugsNormalized
            val rawSlugsToDownload = missingNormalizedSlugs.mapNotNull { onlineSlugsNormalized[it] }
            urlsToDownload.addAll(rawSlugsToDownload.mapNotNull { onlineSlugToUrl[it] })
        } else {
            println("Performing thorough check for missing and incomplete chapters...")
            val localChapterDataRaw = if (localFile.extension.equals("epub", true)) getChapterPageCountsFromEpub(localFile) else getChapterPageCountsFromZip(localFile)
            val localChapterDataNormalized = localChapterDataRaw.mapKeys { normalizeChapterSlug(it.key) }

            coroutineScope {
                val chaptersToProcess = onlineSlugsNormalized.map { (normalizedSlug, rawSlug) ->
                    async(Dispatchers.IO) {
                        val localPageCount = localChapterDataNormalized[normalizedSlug]
                        val url = onlineSlugToUrl[rawSlug]!!
                        if (localPageCount == null) {
                            println(" -> New chapter found: $rawSlug"); url
                        } else {
                            val onlineImageUrls = scraperService.findImageUrls(client, url)
                            if (onlineImageUrls.size > localPageCount) {
                                println(" -> Incomplete chapter found: $rawSlug (Local: $localPageCount, Online: ${onlineImageUrls.size})"); url
                            } else null
                        }
                    }
                }.awaitAll().filterNotNull()
                urlsToDownload.addAll(chaptersToProcess)
            }
        }

        if (urlsToDownload.isEmpty()) {
            println("Source is already up-to-date."); return
        }

        println("Found ${urlsToDownload.size} chapters to download/update: ${urlsToDownload.map { it.substringAfterLast("/") }.joinToString()}")

        if (localFile.extension.equals("epub", true)) {
            println("\nIMPORTANT: EPUB updates are not merged. New chapters will be downloaded to a separate folder.")
            val updatesDir = File(tempDir, "${localFile.nameWithoutExtension}-updates").apply { mkdirs() }
            println("Downloading new chapters to: ${updatesDir.absolutePath}")
            urlsToDownload.forEach { downloadChapter(client, it, updatesDir, imageWorkers) }
            return
        }

        val syncDir = Files.createTempDirectory(tempDir.toPath(), "manga-sync-").toFile()
        try {
            urlsToDownload.forEach { downloadChapter(client, it, syncDir, imageWorkers) }
            if (localFile.exists()) processorService.extractZip(localFile, syncDir)
            val allChapterFolders = syncDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
            processorService.createCbzFromFolders(localFile.nameWithoutExtension, allChapterFolders, localFile)
            println("\n--- Sync complete for: ${localFile.name} ---")
        } finally {
            if (syncDir.exists()) syncDir.deleteRecursively()
        }
    }

    suspend fun downloadNewSeries(seriesUrl: String, cliTitle: String?, imageWorkers: Int, exclude: List<String>, format: String, tempDir: File, client: HttpClient) {
        println("URL detected. Starting download process...")
        val mangaTitle = cliTitle ?: seriesUrl.substringAfterLast("/manga/", "").substringBefore('/').replace('-', ' ').titlecase()
        println("Fetching chapter list for: $mangaTitle")
        var chapterUrls = scraperService.findChapterUrls(client, seriesUrl)
        if (chapterUrls.isEmpty()) {
            println("Could not find any chapters at the provided URL."); return
        }
        if (exclude.isNotEmpty()) {
            chapterUrls = chapterUrls.filter { url -> url.trimEnd('/').substringAfterLast('/') !in exclude }
        }
        if (chapterUrls.isEmpty()) {
            println("No chapters left to download after exclusions."); return
        }

        val downloadDir = Files.createTempDirectory(tempDir.toPath(), "manga-dl-").toFile()
        try {
            println("Downloading ${chapterUrls.size} chapters...")
            val downloadedFolders = mutableListOf<File>()
            chapterUrls.forEachIndexed { index, url ->
                val slug = url.trimEnd('/').substringAfterLast('/')
                println("--> Processing chapter ${index + 1}/${chapterUrls.size}: $slug")
                downloadChapter(client, url, downloadDir, imageWorkers)?.let {
                    downloadedFolders.add(it)
                }
                delay(1000L) // Politeness delay between chapters
            }

            if (downloadedFolders.isNotEmpty()) {
                val outputFile = File(".", "$mangaTitle.$format")
                if (format == "cbz") processorService.createCbzFromFolders(mangaTitle, downloadedFolders, outputFile)
                else processorService.createEpubFromFolders(mangaTitle, downloadedFolders, outputFile)
                println("\nDownload and packaging complete: ${outputFile.name}")
            } else {
                println("\nNo chapters were downloaded. Exiting.")
            }
        } finally {
            if (downloadDir.exists()) downloadDir.deleteRecursively()
        }
    }
}
