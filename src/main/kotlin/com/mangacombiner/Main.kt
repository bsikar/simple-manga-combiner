package com.mangacombiner

import com.mangacombiner.core.Processor
import com.mangacombiner.downloader.Downloader
import com.mangacombiner.scraper.WPMangaScraper
import com.mangacombiner.util.expandGlobPath
import com.mangacombiner.util.inferChapterSlugsFromZip
import com.mangacombiner.util.isDebugEnabled
import com.mangacombiner.util.logDebug
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.cli.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files

/**
 * Main entry point for the Manga Combiner application.
 * Handles command-line argument parsing and orchestrates the main operations.
 */
object MainKt {
    // Default to a very polite rate
    private const val DEFAULT_IMAGE_WORKERS = 2
    private const val DEFAULT_CHAPTER_WORKERS = 1 // This is now effectively ignored, but kept for consistency

    private const val DEFAULT_BATCH_WORKERS = 4
    private const val DEFAULT_FORMAT = "cbz"
    private val SUPPORTED_FORMATS = setOf("cbz", "epub")

    private fun String.titlecase(): String = this.split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase() else char.toString()
        }
    }

    private fun getConfirmation(prompt: String): Boolean {
        print("$prompt (yes/no): ")
        return readlnOrNull()?.trim()?.equals("yes", ignoreCase = true) ?: false
    }

    private fun validateFormat(format: String): String {
        val lowerFormat = format.lowercase()
        require(lowerFormat in SUPPORTED_FORMATS) {
            "Unsupported format: $format. Supported formats are: ${SUPPORTED_FORMATS.joinToString()}"
        }
        return lowerFormat
    }

    private fun handleOperationConfirmation(
        trueDangerousMode: Boolean,
        finalDeleteOriginal: Boolean,
        ultraLowStorageMode: Boolean,
        lowStorageMode: Boolean
    ): Boolean {
        when {
            trueDangerousMode -> {
                println("---")
                println("--- EXTREME DANGER: '--true-dangerous-mode' is enabled! ---")
                println("--- This mode moves images one-by-one, modifying the source file as it runs.")
                println("--- PRESSING CTRL+C WILL CORRUPT YOUR ORIGINAL FILE BEYOND RECOVERY.")
                println("---")
                if (!getConfirmation("You have been warned. Do you understand the risk and wish to continue?")) {
                    println("Operation cancelled. Good choice.")
                    return false
                }
                println("\n--- FINAL CONFIRMATION ---")
                if (!getConfirmation("This is irreversible. Are you absolutely certain you want to risk your source file?")) {
                    println("Operation cancelled.")
                    return false
                }
                println("\nConfirmation received. Proceeding with dangerous operation...")
            }
            finalDeleteOriginal -> {
                val mode = when {
                    ultraLowStorageMode -> "ultra-low-storage"
                    lowStorageMode      -> "low-storage"
                    else                -> "delete-original"
                }
                println("---")
                println("--- WARNING: '$mode' mode is enabled. ---")
                println("--- This is a safe 'copy-then-delete' operation.")
                println("--- The original file will be deleted only after a successful conversion.")
                println("---")
                if (!getConfirmation("Are you sure you want to delete the original file upon success?")) {
                    println("Operation cancelled.")
                    return false
                }
                println("\nConfirmation received. Proceeding...")
            }
        }
        return true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun processBatchFiles(
        files: List<File>,
        title: String?,
        force: Boolean,
        format: String,
        deleteOriginal: Boolean,
        useStreamingConversion: Boolean,
        useTrueStreaming: Boolean,
        trueDangerousMode: Boolean,
        batchWorkers: Int,
        skipIfTargetExists: Boolean
    ) {
        println("Found ${files.size} files to process using up to $batchWorkers parallel workers.")
        val dispatcher = Dispatchers.IO.limitedParallelism(batchWorkers)
        coroutineScope {
            files.forEach { file ->
                launch(dispatcher) {
                    try {
                        Processor.processLocalFile(
                            file, title, force, format, deleteOriginal,
                            useStreamingConversion, useTrueStreaming, trueDangerousMode,
                            skipIfTargetExists
                        )
                    } catch (e: Exception) {
                        println("Error processing ${file.name}: ${e.message}")
                        logDebug { "Stack trace: ${e.stackTraceToString()}" }
                    }
                }
            }
        }
        println("\nBatch processing complete.")
    }

    /**
     * Creates a patiently configured HttpClient.
     */
    private fun createPoliteHttpClient(): HttpClient {
        return HttpClient(CIO) {
            // Give server plenty of time to respond
            install(HttpTimeout) {
                requestTimeoutMillis = 60000 // 60 seconds
                connectTimeoutMillis = 60000 // 60 seconds
                socketTimeoutMillis = 60000  // 60 seconds
            }
            // Automatically retry failed requests with increasing delays
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                exponentialDelay()
            }
        }
    }

    private suspend fun syncCbzWithSource(
        cbzPath: String,
        seriesUrl: String,
        imageWorkers: Int,
        exclude: List<String>
    ) {
        val cbzFile = File(cbzPath)
        if (!cbzFile.exists()) {
            println("Error: Local file not found at $cbzPath"); return
        }

        println("--- Starting Sync for: ${cbzFile.name} ---")
        val client = createPoliteHttpClient()
        val localSlugs = inferChapterSlugsFromZip(cbzFile)
        println("Found ${localSlugs.size} chapters locally.")

        var onlineUrls = WPMangaScraper.findChapterUrls(client, seriesUrl)
        if (onlineUrls.isEmpty()) {
            println("Could not retrieve chapter list from source. Aborting sync."); client.close(); return
        }
        if (exclude.isNotEmpty()) {
            onlineUrls = onlineUrls.filter { url -> url.trimEnd('/').substringAfterLast('/') !in exclude }
        }

        val slugToUrl = onlineUrls.associateBy { it.trimEnd('/').substringAfterLast('/') }
        val missingSlugs = (slugToUrl.keys - localSlugs).sorted()

        if (missingSlugs.isEmpty()) {
            println("CBZ is already up-to-date. No new chapters found."); client.close(); return
        }

        println("Found ${missingSlugs.size} new chapters to download: ${missingSlugs.joinToString()}")
        val urlsToDownload = missingSlugs.mapNotNull { slugToUrl[it] }
        val tempDir = Files.createTempDirectory("manga-sync-").toFile()
        try {
            // Process chapters one by one sequentially
            for ((index, chapterUrl) in urlsToDownload.withIndex()) {
                println("--> Downloading new chapter ${index + 1}/${urlsToDownload.size}: ${chapterUrl.substringAfterLast("/")}")
                Downloader.downloadChapter(client, chapterUrl, tempDir, imageWorkers)
                delay(2000L) // Wait 2 seconds before starting next chapter
            }

            println("\nExtracting existing chapters...")
            if (!Processor.extractZip(cbzFile, tempDir)) {
                println("Failed to extract existing CBZ file. Aborting sync."); return
            }

            val allChapterFolders = tempDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
            if (allChapterFolders.isEmpty()) {
                println("No chapters found after download and extraction. Aborting."); return
            }

            val mangaTitle = cbzFile.nameWithoutExtension
            println("\nRebuilding CBZ archive...")
            Processor.createCbzFromFolders(mangaTitle, allChapterFolders, cbzFile)
            println("\n--- Sync complete for: ${cbzFile.name} ---")
        } finally {
            client.close()
            tempDir.deleteRecursively()
        }
    }

    private suspend fun downloadNewSeries(
        seriesUrl: String,
        cliTitle: String?,
        imageWorkers: Int,
        exclude: List<String>,
        format: String
    ) {
        println("URL detected. Starting download process...")
        val client = createPoliteHttpClient()

        val mangaTitle = cliTitle ?: seriesUrl.substringAfterLast("/manga/", "")
            .substringBefore('/')
            .replace('-', ' ')
            .titlecase()

        println("Fetching chapter list for: $mangaTitle")
        var chapterUrls = WPMangaScraper.findChapterUrls(client, seriesUrl)
        if (chapterUrls.isEmpty()) {
            println("Could not find any chapters at the provided URL."); client.close(); return
        }
        if (exclude.isNotEmpty()) {
            val originalCount = chapterUrls.size
            chapterUrls = chapterUrls.filter { url -> url.trimEnd('/').substringAfterLast('/') !in exclude }
            println("Excluded ${originalCount - chapterUrls.size} chapters. New count: ${chapterUrls.size}")
        }
        if (chapterUrls.isEmpty()) {
            println("No chapters left to download after exclusions."); client.close(); return
        }

        val tempDir = Files.createTempDirectory("manga-dl-").toFile()
        try {
            val downloadedFolders = mutableListOf<File>()
            println("Downloading ${chapterUrls.size} chapters into ${tempDir.name}...")

            // Process chapters one by one sequentially
            for ((index, chapterUrl) in chapterUrls.withIndex()) {
                println("--> Processing chapter ${index + 1}/${chapterUrls.size}: ${chapterUrl.substringAfterLast("/")}")
                val downloadedFolder = Downloader.downloadChapter(client, chapterUrl, tempDir, imageWorkers)
                if (downloadedFolder != null) {
                    downloadedFolders.add(downloadedFolder)
                }
                delay(2000L) // Wait 2 seconds before the next chapter to be polite
            }

            if (downloadedFolders.isNotEmpty()) {
                val outputFile = File(".", "$mangaTitle.$format")
                when (format) {
                    "cbz" -> Processor.createCbzFromFolders(mangaTitle, downloadedFolders, outputFile)
                    "epub" -> Processor.createEpubFromFolders(mangaTitle, downloadedFolders, outputFile)
                }
                println("\nDownload and packaging complete.")
            } else {
                println("\nNo chapters were downloaded. Exiting.")
            }
        } finally {
            client.close()
            tempDir.deleteRecursively()
        }
    }


    @JvmStatic
    fun main(args: Array<String>) {
        val parser = ArgParser(programName = "MangaCombiner")

        val source by parser.argument(
            type = ArgType.String,
            fullName = "source",
            description = "Source URL, local file (.cbz or .epub), or a glob pattern (e.g., \"*.cbz\")."
        )

        val update by parser.option(
            type = ArgType.String,
            fullName = "update",
            description = "Path to a local CBZ file to update with missing chapters from the source URL."
        )

        val exclude by parser.option(
            type = ArgType.String,
            fullName = "exclude",
            description = "A chapter URL slug to exclude. Can be used multiple times.",
        ).multiple()

        val workers by parser.option(
            type = ArgType.Int,
            shortName = "w",
            fullName = "workers",
            description = "Number of concurrent image download threads per chapter."
        ).default(DEFAULT_IMAGE_WORKERS)

        val chapterWorkers by parser.option(
            type = ArgType.Int,
            fullName = "chapter-workers",
            description = "DEPRECATED: Chapter downloads are now sequential. This flag is ignored."
        ).default(DEFAULT_CHAPTER_WORKERS)

        val title by parser.option(
            type = ArgType.String,
            shortName = "t",
            fullName = "title",
            description = "Set a custom title for the output file, overriding the source filename."
        )

        val format by parser.option(
            type = ArgType.String,
            fullName = "format",
            description = "The desired output format ('cbz' or 'epub')."
        ).default(DEFAULT_FORMAT)

        // ... (rest of the parser options are unchanged) ...
        val force by parser.option(ArgType.Boolean, shortName = "f", fullName = "force").default(false)
        val deleteOriginal by parser.option(ArgType.Boolean, fullName = "delete-original").default(false)
        val lowStorageMode by parser.option(ArgType.Boolean, fullName = "low-storage-mode").default(false)
        val ultraLowStorageMode by parser.option(ArgType.Boolean, fullName = "ultra-low-storage-mode").default(false)
        val trueDangerousMode by parser.option(ArgType.Boolean, fullName = "true-dangerous-mode").default(false)
        val batchWorkers by parser.option(ArgType.Int, fullName = "batch-workers").default(DEFAULT_BATCH_WORKERS)
        val debug by parser.option(ArgType.Boolean, fullName = "debug").default(false)
        val skipIfTargetExists by parser.option(ArgType.Boolean, fullName = "skip-if-target-exists").default(false)


        try {
            parser.parse(args)
        } catch (e: Exception) {
            println("Error parsing arguments: ${e.message}")
            return
        }

        isDebugEnabled = debug

        val validatedFormat = try {
            validateFormat(format)
        } catch (e: IllegalArgumentException) {
            println(e.message)
            return
        }

        val finalBatchWorkers = if (ultraLowStorageMode) 1 else batchWorkers
        val finalDeleteOriginal = deleteOriginal || lowStorageMode || ultraLowStorageMode || trueDangerousMode
        val useStreamingConversion = lowStorageMode || ultraLowStorageMode
        val useTrueStreaming = ultraLowStorageMode

        if (chapterWorkers != DEFAULT_CHAPTER_WORKERS) {
            println("Warning: --chapter-workers is deprecated and will be ignored. Chapters are now downloaded sequentially to be server-friendly.")
        }

        if (!handleOperationConfirmation(trueDangerousMode, finalDeleteOriginal, ultraLowStorageMode, lowStorageMode)) {
            return
        }

        runBlocking {
            try {
                val isUrlSource = source.startsWith("http://", true) || source.startsWith("https://", true)

                when {
                    isUrlSource && update != null -> {
                        syncCbzWithSource(update!!, source, workers, exclude)
                    }
                    isUrlSource -> {
                        downloadNewSeries(source, title, workers, exclude, validatedFormat)
                    }
                    "*" in source || "?" in source -> {
                        val files = expandGlobPath(source).filter {
                            it.extension.equals("cbz", true) || it.extension.equals("epub", true)
                        }
                        if (files.isEmpty()) {
                            println("No files found matching pattern '$source'.")
                            return@runBlocking
                        }
                        processBatchFiles(
                            files, title, force, validatedFormat, finalDeleteOriginal,
                            useStreamingConversion, useTrueStreaming, trueDangerousMode,
                            finalBatchWorkers, skipIfTargetExists
                        )
                    }
                    File(source).exists() -> {
                        Processor.processLocalFile(
                            File(source), title, force, validatedFormat, finalDeleteOriginal,
                            useStreamingConversion, useTrueStreaming, trueDangerousMode,
                            skipIfTargetExists
                        )
                    }
                    else -> {
                        println("Error: Source '$source' is not an existing file path or a recognized file pattern.")
                    }
                }
            } catch (e: Exception) {
                println("Fatal error: ${e.message}")
                logDebug { "Stack trace: ${e.stackTraceToString()}" }
            }
        }
    }
}
