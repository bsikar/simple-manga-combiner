package com.mangacombiner.desktop

import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.di.appModule
import com.mangacombiner.model.ScrapedSeries
import com.mangacombiner.model.ScrapedSeriesCache
import com.mangacombiner.model.SearchResult
import com.mangacombiner.service.*
import com.mangacombiner.util.*
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.get
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.*
import java.io.*
import java.net.URI
import kotlin.random.Random

/**
 * Data class to pass results from the download phase to the packaging phase.
 */
private data class DownloadResultForPackaging(
    val mangaTitle: String,
    val downloadDir: File,
    val seriesSlug: String,
    val outputFile: File,
    val sourceUrl: String,
    val failedChapters: Map<String, List<String>>?,
    val allFoldersForPackaging: List<File>
)

/**
 * PHASE 2: Takes a completed download and packages it into the final file format.
 */
private suspend fun packageSeries(
    result: DownloadResultForPackaging,
    cliArgs: CliArguments,
    processorService: ProcessorService
) {
    Logger.logInfo("Creating ${cliArgs.format.uppercase()} for ${result.mangaTitle} from ${result.allFoldersForPackaging.size} total chapters...")
    // Since we only support EPUB now, the conditional is removed.
    // The CLI doesn't fetch detailed metadata, so we pass null for it.
    processorService.createEpubFromFolders(
        mangaTitle = result.mangaTitle,
        chapterFolders = result.allFoldersForPackaging,
        outputFile = result.outputFile,
        seriesUrl = result.sourceUrl,
        failedChapters = result.failedChapters,
        seriesMetadata = null, // CLI does not fetch this detailed metadata
        maxWidth = cliArgs.maxWidth,
        jpegQuality = cliArgs.jpegQuality
    )

    if (result.outputFile.exists()) {
        // Always log the full path
        Logger.logInfo("Successfully created: ${result.outputFile.absolutePath}")
        if (cliArgs.cleanCache) {
            val size = result.downloadDir.walk().sumOf { it.length() }
            Logger.logInfo("Cleaning cache for ${result.seriesSlug}... Reclaimed ${formatSize(size)}")
            result.downloadDir.deleteRecursively()
        }
    }
}

/**
 * PHASE 1: Downloads all chapters for a single series to the cache.
 * Returns a data object for the packaging phase, or null on failure.
 */
private suspend fun downloadSeriesToCache(
    source: String,
    cliArgs: CliArguments,
    downloadService: DownloadService,
    scraperService: ScraperService,
    platformProvider: PlatformProvider,
    cacheService: CacheService
): DownloadResultForPackaging? {
    val tempDir = File(platformProvider.getTmpDir())
    val listClient = createHttpClient(cliArgs.proxy)

    try {
        if (!source.startsWith("http", ignoreCase = true)) {
            Logger.logError("Cannot download from local file source '$source'. Skipping.")
            return null
        }

        val mangaTitle = cliArgs.title?.ifBlank { null } ?: source.toSlug().replace('-', ' ').titlecase()
        val finalOutputPath = cliArgs.outputPath.ifBlank { platformProvider.getUserDownloadsDir() ?: "" }
        val finalFileName = "${FileUtils.sanitizeFilename(mangaTitle)}.${cliArgs.format}"
        val outputFile = File(finalOutputPath, finalFileName)

        if (outputFile.exists()) {
            when {
                cliArgs.skipExisting -> { Logger.logInfo("Output file ${outputFile.name} already exists. Skipping."); return null }
                cliArgs.update -> {
                    Logger.logInfo("Update logic is not yet fully implemented in this CLI version. Use --force to redownload.")
                    return null
                }
                !cliArgs.force && !cliArgs.dryRun -> {
                    Logger.logError("Output file exists for $source: ${outputFile.absolutePath}. Use --force, --skip-existing, or --update.")
                    return null
                }
            }
        }

        Logger.logInfo("--- Processing URL: $source ---")
        val listScraperAgent = UserAgent.browsers[cliArgs.userAgentName] ?: UserAgent.browsers.values.first()
        var allOnlineChapters = scraperService.findChapterUrlsAndTitles(listClient, source, listScraperAgent)
        if (allOnlineChapters.isEmpty()) {
            Logger.logError("No chapters found at $source. Skipping."); return null
        }

        if (cliArgs.exclude.isNotEmpty()) {
            allOnlineChapters = allOnlineChapters.filter { (url, _) -> url.trimEnd('/').substringAfterLast('/') !in cliArgs.exclude }
        }

        val seriesSlug = source.toSlug()
        var chaptersToDownload = allOnlineChapters

        if (!cliArgs.ignoreCache) {
            val cachedChapterStatus = cacheService.getCachedChapterStatus(seriesSlug)
            val (cached, toDownload) = allOnlineChapters.partition {
                cachedChapterStatus[FileUtils.sanitizeFilename(it.second)] == true
            }
            if (cached.isNotEmpty()) Logger.logInfo("${cached.size} chapters already in cache. Skipping download portion.")
            chaptersToDownload = toDownload
        }

        val downloadDir = File(tempDir, "manga-dl-$seriesSlug").apply { mkdirs() }

        if (chaptersToDownload.isEmpty()) {
            Logger.logInfo("All chapters are available in cache. Proceeding to packaging...")
            if (downloadDir.exists()) {
                val allFoldersForPackaging = downloadDir.listFiles { file -> file.isDirectory }?.toList() ?: emptyList()
                return DownloadResultForPackaging(mangaTitle, downloadDir, seriesSlug, outputFile, source, null, allFoldersForPackaging)
            } else {
                Logger.logError("Cache is inconsistent; files not found. Please try again with --ignore-cache.")
                return null
            }
        }

        val downloadOptions = createDownloadOptions(source, chaptersToDownload.toMap(), mangaTitle, cliArgs, platformProvider)
        logOperationSettings(downloadOptions, chaptersToDownload.size, cliArgs.userAgentName, cliArgs.perWorkerUserAgent, cacheCount = allOnlineChapters.size - chaptersToDownload.size, optimizeMode = cliArgs.optimize, cleanCache = cliArgs.cleanCache, skipExisting = cliArgs.skipExisting, updateExisting = cliArgs.update, force = cliArgs.force, maxWidth = cliArgs.maxWidth, jpegQuality = cliArgs.jpegQuality)

        if (cliArgs.dryRun) {
            Logger.logInfo("DRY RUN: Would download ${chaptersToDownload.size} new chapters for $source.")
            return null
        }

        val downloadResult = downloadService.downloadChapters(downloadOptions, downloadDir)
        return if (downloadResult != null && downloadResult.successfulFolders.isNotEmpty()) {
            val allFoldersForPackaging = (downloadDir.listFiles { file -> file.isDirectory }?.toList() ?: emptyList())
            DownloadResultForPackaging(mangaTitle, downloadDir, seriesSlug, outputFile, source, downloadResult.failedChapters, allFoldersForPackaging)
        } else {
            null
        }
    } catch (e: Exception) {
        Logger.logError("A fatal error occurred while processing '$source'", e)
        return null
    } finally {
        listClient.close()
    }
}

/**
 * Updates the metadata of an existing EPUB file with information scraped from a source URL.
 * This includes cover image, title, authors, and other series metadata.
 *
 * @param epubFile The existing EPUB file to update
 * @param sourceUrl The URL to scrape metadata from
 * @param cliArgs CLI arguments containing proxy and user agent settings
 * @param scraperService Service for scraping series metadata
 * @param processorService Service for EPUB processing and metadata updates
 */
private suspend fun updateEpubMetadata(
    epubFile: File,
    sourceUrl: String,
    cliArgs: CliArguments,
    scraperService: ScraperService,
    processorService: ProcessorService
) {
    Logger.logInfo("--- Updating metadata for: ${epubFile.name} ---")

    if (!epubFile.exists()) {
        Logger.logError("EPUB file does not exist: ${epubFile.absolutePath}")
        return
    }

    if (!epubFile.name.endsWith(".epub", ignoreCase = true)) {
        Logger.logError("File is not an EPUB: ${epubFile.name}")
        return
    }

    if (cliArgs.dryRun) {
        Logger.logInfo("DRY RUN: Would update metadata for ${epubFile.name} using source: $sourceUrl")
        return
    }

    val client = createHttpClient(cliArgs.proxy)
    try {
        // Get user agent for scraping
        val userAgent = UserAgent.browsers[cliArgs.userAgentName] ?: UserAgent.browsers.values.first()
        Logger.logInfo("Scraping metadata from source: $sourceUrl")

        // Use the existing fetchSeriesDetails method to get both metadata and chapters
        val (seriesMetadata, _) = scraperService.fetchSeriesDetails(client, sourceUrl, userAgent)

        Logger.logInfo("Successfully scraped metadata for: ${seriesMetadata.title}")
        Logger.logInfo("Found ${seriesMetadata.authors?.size ?: 0} author(s), ${seriesMetadata.artists?.size ?: 0} artist(s), ${seriesMetadata.genres?.size ?: 0} genre(s)")

        // Create backup of original file
        val backupFile = File(epubFile.parent, "${epubFile.nameWithoutExtension}.backup.epub")
        if (!backupFile.exists()) {
            Logger.logInfo("Creating backup: ${backupFile.name}")
            epubFile.copyTo(backupFile, overwrite = false)
        }

        // Update the EPUB metadata
        val success = processorService.updateEpubMetadata(
            epubFile = epubFile,
            seriesMetadata = seriesMetadata,
            sourceUrl = sourceUrl
        )

        if (success) {
            Logger.logInfo("Successfully updated metadata for: ${epubFile.name}")
            Logger.logInfo("Updated title: ${seriesMetadata.title}")

            // Log updated metadata details
            seriesMetadata.authors?.let { authors ->
                if (authors.isNotEmpty()) {
                    Logger.logInfo("Updated authors: ${authors.joinToString(", ")}")
                }
            }

            seriesMetadata.artists?.let { artists ->
                if (artists.isNotEmpty()) {
                    Logger.logInfo("Updated artists: ${artists.joinToString(", ")}")
                }
            }

            seriesMetadata.genres?.let { genres ->
                if (genres.isNotEmpty()) {
                    Logger.logInfo("Updated genres: ${genres.joinToString(", ")}")
                }
            }

            seriesMetadata.coverImageUrl?.let { coverUrl ->
                Logger.logInfo("Updated cover image from: $coverUrl")
            }

            // Log additional metadata if available
            seriesMetadata.status?.let { status ->
                Logger.logInfo("Series status: $status")
            }

            seriesMetadata.type?.let { type ->
                Logger.logInfo("Series type: $type")
            }

            seriesMetadata.release?.let { release ->
                Logger.logInfo("Release year: $release")
            }

        } else {
            Logger.logError("Failed to update metadata for: ${epubFile.name}")

            // Restore from backup if update failed
            if (backupFile.exists()) {
                Logger.logInfo("Restoring from backup due to update failure...")
                backupFile.copyTo(epubFile, overwrite = true)
                backupFile.delete()
            }
        }

    } catch (e: Exception) {
        Logger.logError("Error updating metadata for ${epubFile.name}: ${e.message}", e)

        // Restore from backup on exception
        val backupFile = File(epubFile.parent, "${epubFile.nameWithoutExtension}.backup.epub")
        if (backupFile.exists()) {
            Logger.logInfo("Restoring from backup due to error...")
            try {
                backupFile.copyTo(epubFile, overwrite = true)
                backupFile.delete()
                Logger.logInfo("Successfully restored from backup")
            } catch (restoreException: Exception) {
                Logger.logError("Failed to restore from backup: ${restoreException.message}")
            }
        }
    } finally {
        client.close()
    }
}

/**
 * Handles processing of a single local file.
 */
private suspend fun processLocalFile(
    source: String,
    cliArgs: CliArguments,
    fileConverter: FileConverter,
    processorService: ProcessorService,
    platformProvider: PlatformProvider
) {
    Logger.logInfo("--- Processing File: $source ---")
    if (cliArgs.dryRun) {
        Logger.logInfo("DRY RUN: Would process local file ${File(source).name}. No files will be changed.")
        return
    }

    val inputFile = File(source)
    val mangaTitle = cliArgs.title?.ifBlank { null } ?: inputFile.nameWithoutExtension
    val finalOutputPath = cliArgs.outputPath.ifBlank { inputFile.parent }
    val finalFileName = "${FileUtils.sanitizeFilename(mangaTitle)}.${cliArgs.format}"
    val outputFile = File(finalOutputPath, finalFileName)

    if (outputFile.exists() && !cliArgs.force && !cliArgs.skipExisting) {
        Logger.logError("Output file exists for $source: ${outputFile.absolutePath}. Use --force or --skip-existing.")
        return
    }
    if (outputFile.exists() && cliArgs.skipExisting) {
        Logger.logInfo("Output file ${outputFile.name} already exists. Skipping.")
        return
    }

    val localFileOptions = LocalFileOptions(
        inputFile = inputFile, customTitle = mangaTitle, outputFormat = cliArgs.format,
        forceOverwrite = cliArgs.force, deleteOriginal = cliArgs.deleteOriginal,
        useStreamingConversion = false, useTrueStreaming = false, useTrueDangerousMode = false,
        skipIfTargetExists = !cliArgs.force, tempDirectory = platformProvider.getTmpDir(),
        dryRun = cliArgs.dryRun
    )

    val result = fileConverter.process(localFileOptions, mangaTitle, outputFile, processorService)
    if (result.success) {
        Logger.logInfo("Successfully processed: ${result.outputFile?.absolutePath}")
        if (cliArgs.deleteOriginal && inputFile.delete()) {
            Logger.logInfo("Deleted original file: ${inputFile.name}")
        }
    } else {
        Logger.logError("Processing failed for $source: ${result.error}")
    }
}

/**
 * Handles metadata update for a single EPUB file.
 * Can work with just an EPUB file (extracts source URL from metadata) or with both EPUB and source URL.
 */
private suspend fun processMetadataUpdate(
    epubPath: String,
    sourceUrlOverride: String?,
    cliArgs: CliArguments,
    scraperService: ScraperService,
    processorService: ProcessorService
) {
    val epubFile = File(epubPath)

    if (!epubFile.exists()) {
        Logger.logError("EPUB file not found: $epubPath")
        return
    }

    // Determine the source URL to use
    val sourceUrl = sourceUrlOverride ?: run {
        Logger.logInfo("No source URL provided, attempting to extract from EPUB metadata...")
        val extractedUrl = extractSourceUrlFromEpub(epubFile)
        if (extractedUrl == null) {
            Logger.logError("Could not extract source URL from EPUB metadata.")
            Logger.logError("Please provide a source URL: manga-combiner --source \"$epubPath\" --source \"https://example.com/series\" --update-metadata")
            return
        } else {
            Logger.logInfo("Found source URL in EPUB metadata: $extractedUrl")
            extractedUrl
        }
    }

    updateEpubMetadata(epubFile, sourceUrl, cliArgs, scraperService, processorService)
}

/**
 * Extracts the source URL from existing EPUB metadata.
 * Returns null if no source URL is found in the metadata.
 */
private fun extractSourceUrlFromEpub(epubFile: File): String? {
    return try {
        Logger.logDebug { "Extracting source URL from EPUB: ${epubFile.name}" }

        val tempDir = File.createTempFile("epub-source-extract-", "-temp").apply {
            delete()
            mkdirs()
        }

        try {
            // Use ZipFile instead of ZipInputStream for more robust extraction
            val zipFile = java.util.zip.ZipFile(epubFile)

            try {
                // Extract specific files we need
                val entriesToExtract = zipFile.entries().asSequence().filter { entry ->
                    !entry.isDirectory && (
                            entry.name.endsWith(".opf") ||
                                    entry.name.contains("container.xml") ||
                                    entry.name.contains("META-INF")
                            )
                }

                for (entry in entriesToExtract) {
                    val filePath = File(tempDir, entry.name)
                    filePath.parentFile?.mkdirs()

                    zipFile.getInputStream(entry).use { input ->
                        FileOutputStream(filePath).use { output ->
                            input.copyTo(output)
                        }
                    }
                }

            } finally {
                zipFile.close()
            }

            // Find and parse the OPF file
            val opfFile = findEpubOpfFileInTemp(tempDir)
            if (opfFile != null && opfFile.exists()) {
                val opfContent = opfFile.readText()
                val document = org.jsoup.Jsoup.parse(opfContent, "", org.jsoup.parser.Parser.xmlParser())

                // Look for source URL in various metadata locations
                val sourceUrl = document.selectFirst("metadata source, metadata dc\\:source")?.text()?.trim()
                    ?: document.selectFirst("metadata identifier[scheme=URI], metadata dc\\:identifier[scheme=URI]")?.text()?.trim()
                    ?: document.selectFirst("metadata description, metadata dc\\:description")?.text()
                        ?.lines()?.find { it.trim().startsWith("Source:") }
                        ?.substringAfter("Source:")?.trim()

                if (!sourceUrl.isNullOrBlank() && sourceUrl.startsWith("http", ignoreCase = true)) {
                    Logger.logDebug { "Successfully extracted source URL: $sourceUrl" }
                    Logger.logInfo("Found embedded series URL in ${epubFile.name}: $sourceUrl")
                    return sourceUrl
                }
            }

            Logger.logDebug { "No source URL found in EPUB metadata" }
            null

        } finally {
            tempDir.deleteRecursively()
        }

    } catch (e: Exception) {
        Logger.logError("Error extracting source URL from EPUB: ${e.message}", e)
        null
    }
}

/**
 * Helper function to find OPF file in temporary extraction directory.
 */
private fun findEpubOpfFileInTemp(tempDir: File): File? {
    // Check container.xml first
    val containerFile = File(tempDir, "META-INF/container.xml")
    if (containerFile.exists()) {
        try {
            val containerContent = containerFile.readText()
            val containerDoc = org.jsoup.Jsoup.parse(containerContent, "", org.jsoup.parser.Parser.xmlParser())
            val rootfileElement = containerDoc.selectFirst("rootfile")
            val fullPath = rootfileElement?.attr("full-path")
            if (!fullPath.isNullOrBlank()) {
                val opfFile = File(tempDir, fullPath)
                if (opfFile.exists()) {
                    return opfFile
                }
            }
        } catch (e: Exception) {
            Logger.logDebug { "Could not parse container.xml: ${e.message}" }
        }
    }

    // Fall back to finding .opf files
    return tempDir.walkTopDown()
        .filter { it.isFile && it.extension == "opf" }
        .firstOrNull()
}

// Data class to hold parsed CLI arguments
data class CliArguments(
    val source: List<String>,
    val search: Boolean,
    val scrape: Boolean,
    val refreshCache: Boolean,
    val downloadAll: Boolean,
    val cleanCache: Boolean,
    val deleteCache: Boolean,
    val ignoreCache: Boolean,
    val keep: List<String>,
    val remove: List<String>,
    val skipExisting: Boolean,
    val update: Boolean,
    val updateMetadata: Boolean, // New field for metadata updates
    val format: String,
    val title: String?,
    val outputPath: String,
    val force: Boolean,
    val deleteOriginal: Boolean,
    val debug: Boolean,
    val dryRun: Boolean,
    val exclude: List<String>,
    val workers: Int,
    val userAgentName: String,
    val proxy: String?,
    val perWorkerUserAgent: Boolean,
    val batchWorkers: Int,
    val optimize: Boolean,
    val maxWidth: Int?,
    val jpegQuality: Int?,
    val sortBy: String,
    val cacheDir: String?
)

private fun createDownloadOptions(
    sourceUrl: String, chapters: Map<String, String>, title: String?,
    cliArgs: CliArguments, platformProvider: PlatformProvider
): DownloadOptions {
    return DownloadOptions(
        seriesUrl = sourceUrl, chaptersToDownload = chapters, cliTitle = title,
        getWorkers = { cliArgs.workers }, exclude = cliArgs.exclude, format = cliArgs.format,
        tempDir = File(platformProvider.getTmpDir()),
        getUserAgents = {
            when {
                cliArgs.perWorkerUserAgent -> List(cliArgs.workers) { UserAgent.browsers.values.random(Random) }
                cliArgs.userAgentName == "Random" -> listOf(UserAgent.browsers.values.random(Random))
                else -> listOf(UserAgent.browsers[cliArgs.userAgentName] ?: UserAgent.browsers.values.first())
            }
        },
        outputPath = cliArgs.outputPath.ifBlank { platformProvider.getUserDownloadsDir() ?: "" },
        isPaused = { false },
        dryRun = cliArgs.dryRun,
        onProgressUpdate = { progress, status ->
            Logger.logInfo("[${sourceUrl.toSlug()}] $status (${(progress * 100).toInt()}%)")
        },
        onChapterCompleted = {}
    )
}

private suspend fun CoroutineScope.runDownloadsAndPackaging(
    sources: List<String>, cliArgs: CliArguments, downloadService: DownloadService,
    processorService: ProcessorService, scraperService: ScraperService,
    platformProvider: PlatformProvider,
    cacheService: CacheService
) {
    val downloadSemaphore = Semaphore(cliArgs.batchWorkers)
    val packagingChannel = Channel<DownloadResultForPackaging>(Channel.UNLIMITED)

    val packagingCollector = launch {
        val packagingJobs = mutableListOf<Job>()
        for (result in packagingChannel) {
            packagingJobs.add(launch {
                packageSeries(result, cliArgs, processorService)
            })
        }
        packagingJobs.joinAll()
    }

    val downloadJobs = sources.map { src ->
        launch {
            downloadSemaphore.withPermit {
                val downloadResult = downloadSeriesToCache(src, cliArgs, downloadService, scraperService, platformProvider, cacheService)
                if (downloadResult != null) {
                    packagingChannel.send(downloadResult)
                }
            }
        }
    }

    downloadJobs.joinAll()
    packagingChannel.close()
    packagingCollector.join()
}

/**
 * Sorts a list of SearchResult objects based on the provided sort key.
 */
private fun sortSeries(series: List<SearchResult>, sortBy: String): List<SearchResult> {
    Logger.logInfo("Sorting ${series.size} series by '$sortBy'...")
    return when (sortBy) {
        "chapters-asc" -> series.sortedBy { it.chapterCount ?: 0 }
        "chapters-desc" -> series.sortedByDescending { it.chapterCount ?: 0 }
        "alpha-asc" -> series.sortedBy { it.title }
        "alpha-desc" -> series.sortedByDescending { it.title }
        else -> series // "default"
    }
}

// Custom help printer function
fun printCustomHelp() {
    val helpText = """
manga-combiner-cli v${AppVersion.NAME}

USAGE:
  manga-combiner-cli [OPTIONS] --source <URL|FILE|QUERY>...

EXAMPLES:
  # Download a single series
  manga-combiner-cli --source https://example.com/manga/one-piece

  # Search for manga and download all results
  manga-combiner-cli --source "attack on titan" --search --download-all

  # Batch download from a genre page
  manga-combiner-cli --source https://example.com/genre/action --scrape
  
  # Update a local EPUB file with the latest chapters from its source URL
  manga-combiner-cli --source my-manga.epub --update

  # Update metadata of an existing EPUB file
  manga-combiner-cli --source my-manga.epub --update-metadata --source https://example.com/manga/series-url

  # Use a preset for optimized small files
  manga-combiner-cli --source https://example.com/manga/series --preset small-size

INPUT OPTIONS:
  -s, --source <URL|FILE|QUERY>   Source URL, local EPUB file, or search query (can be used multiple times)

DISCOVERY & SEARCH:
  --search                      Search for manga by name and display results
  --scrape                      Batch download all series from a list/genre page
  --download-all                Download all search results (use with --search)

OUTPUT OPTIONS:
  --format <epub>               Output format (default: epub)
  -t, --title <NAME>            Custom title for output file
  -o, --output <DIR>            Output directory (default: Downloads)

DOWNLOAD BEHAVIOR:
  -f, --force                   Force overwrite existing files
  --redownload-existing         Alias for --force
  --skip-existing               Skip if output file exists (good for batch)
  --update                      Update an existing EPUB with new chapters
  --update-metadata             Update metadata of existing EPUB using scraped series data
  -e, --exclude <SLUG>          Exclude chapters by slug (e.g., 'chapter-4.5'). Can be used multiple times.
  --delete-original             Delete source file after successful conversion (local files only)

CACHE MANAGEMENT:
  --ignore-cache                Force re-download all chapters
  --clean-cache                 Delete temp files after successful download to save disk space
  --refresh-cache               Force refresh scraped series list (with --scrape)
  --delete-cache                Delete cached downloads and exit. Use with --keep or --remove for selective deletion.
  --keep <PATTERN>              Keep matching series when deleting cache
  --remove <PATTERN>            Remove matching series when deleting cache
  --cache-dir <DIR>             Custom cache directory

IMAGE OPTIMIZATION:
  --optimize                    Enable image optimization (slower but smaller files)
  --max-image-width <PIXELS>    Resize images to max width
  --jpeg-quality <1-100>        JPEG compression quality
  --preset <NAME>               Use preset: fast, quality, small-size

PERFORMANCE:
  -w, --workers <N>             Concurrent image downloads per series (default: 4)
  -bw, --batch-workers <N>      Concurrent series downloads (default: 1)

NETWORK:
  -ua, --user-agent <NAME>      Browser to impersonate (see --list-user-agents)
  --per-worker-ua               Random user agent per worker
  --proxy <URL>                 HTTP proxy (e.g., http://localhost:8080)

SORTING:
  --sort-by <METHOD>            Sort order for batch downloads (see --list-sort-options)

UTILITY:
  --dry-run                     Preview actions without downloading
  --debug                       Enable verbose logging
  --list-user-agents            Show available user agents
  --list-sort-options           Show available sort methods
  -v, --version                 Show version information and exit
  --help                        Show this help message

METADATA UPDATE USAGE:
  # Simple update (uses source URL from EPUB metadata)
  manga-combiner-cli --source my-series.epub --update-metadata
  
  # Override source URL (uses provided URL instead of EPUB metadata)
  manga-combiner-cli --source my-series.epub --source https://example.com/manga/series --update-metadata
  
  # The EPUB file should be created by manga-combiner (contains source URL in metadata)
  # If no source URL found in EPUB, you'll need to provide it manually
  # This will update the EPUB with cover image, title, authors, genres, etc.
    """.trimIndent()

    println(helpText)
}

// Function to apply preset configurations
fun applyPreset(preset: String, args: CliArguments): CliArguments {
    return when (preset.lowercase()) {
        "fast" -> args.copy(
            workers = 8,
            batchWorkers = 3,
            optimize = false
        )
        "quality" -> args.copy(
            workers = 4,
            batchWorkers = 1,
            optimize = false,
            maxWidth = null,
            jpegQuality = null
        )
        "small-size" -> args.copy(
            workers = 2,
            batchWorkers = 1,
            optimize = true,
            maxWidth = 1000,
            jpegQuality = 75
        )
        else -> args
    }
}

fun main(args: Array<String>) {
    // Check for help flag first
    if (args.contains("--help") || args.contains("-h")) {
        printCustomHelp()
        return
    }

    // Check for version flag
    if (args.contains("--version") || args.contains("-v")) {
        println("manga-combiner-cli v${AppVersion.NAME}")
        return
    }

    val parser = ArgParser("manga-combiner-cli v${AppVersion.NAME}", useDefaultHelpShortName = false)

    // ===== INPUT/SOURCE OPTIONS =====
    val source by parser.option(
        ArgType.String, "source", "s",
        "Source URL, local file, or search query. Can be specified multiple times."
    ).multiple()

    // ===== SEARCH AND DISCOVERY =====
    val search by parser.option(
        ArgType.Boolean, "search",
        description = "Search for manga series by name and display results."
    ).default(false)

    val scrape by parser.option(
        ArgType.Boolean, "scrape",
        description = "Scrape and batch download all series from a list/genre page URL."
    ).default(false)

    val downloadAll by parser.option(
        ArgType.Boolean, "download-all",
        description = "Automatically download all results from a search. Must be used with --search."
    ).default(false)

    // ===== OUTPUT OPTIONS =====
    val format by parser.option(
        ArgType.Choice(listOf("epub"), { it }),
        "format",
        description = "Output format for downloaded manga (default: epub)."
    ).default("epub")

    val title by parser.option(
        ArgType.String, "title", "t",
        "Custom title for the output file (defaults to series name)."
    )

    val outputPath by parser.option(
        ArgType.String, "output", "o",
        "Directory to save downloaded files (defaults to Downloads folder)."
    ).default("")

    // ===== DOWNLOAD BEHAVIOR =====
    val force by parser.option(
        ArgType.Boolean, "force", "f",
        "Force overwrite existing files without prompting."
    ).default(false)

    val redownloadExisting by parser.option(
        ArgType.Boolean, "redownload-existing",
        description = "Alias for --force. Force redownload even if file exists."
    ).default(false)

    val skipExisting by parser.option(
        ArgType.Boolean, "skip-existing",
        description = "Skip series if output file already exists (useful for batch downloads)."
    ).default(false)

    val update by parser.option(
        ArgType.Boolean, "update",
        description = "Update an existing EPUB with new chapters."
    ).default(false)

    // NEW: Metadata update flag
    val updateMetadata by parser.option(
        ArgType.Boolean, "update-metadata",
        description = "Update metadata of existing EPUB using scraped series data. Requires EPUB file path and source URL."
    ).default(false)

    val exclude by parser.option(
        ArgType.String, "exclude", "e",
        "Exclude chapters by URL slug (e.g., 'chapter-4.5'). Can be used multiple times."
    ).multiple()

    val deleteOriginal by parser.option(
        ArgType.Boolean, "delete-original",
        description = "Delete source file after successful conversion (local files only)."
    ).default(false)

    // ===== CACHE MANAGEMENT =====
    val ignoreCache by parser.option(
        ArgType.Boolean, "ignore-cache",
        description = "Force re-download all chapters, ignoring cached files."
    ).default(false)

    val cleanCache by parser.option(
        ArgType.Boolean, "clean-cache",
        description = "Delete temporary files after successful download to save disk space."
    ).default(false)

    val refreshCache by parser.option(
        ArgType.Boolean, "refresh-cache",
        description = "Force refresh of scraped series list. Use with --scrape."
    ).default(false)

    val deleteCache by parser.option(
        ArgType.Boolean, "delete-cache",
        description = "Delete cached downloads and exit. Use with --keep or --remove for selective deletion."
    ).default(false)

    val keep by parser.option(
        ArgType.String, "keep",
        description = "Pattern to keep when deleting cache (e.g., 'One Piece'). Can be used multiple times."
    ).multiple()

    val remove by parser.option(
        ArgType.String, "remove",
        description = "Pattern to remove when deleting cache (e.g., 'Naruto'). Can be used multiple times."
    ).multiple()

    val cacheDir by parser.option(
        ArgType.String, "cache-dir",
        description = "Custom directory for cache and temporary files."
    )

    // ===== IMAGE PROCESSING =====
    val optimize by parser.option(
        ArgType.Boolean, "optimize",
        description = "Enable image optimization (reduces file size but increases processing time).\n" +
                "\tSets: --max-image-width 1200 --jpeg-quality 85"
    ).default(false)

    val maxWidth by parser.option(
        ArgType.Int, "max-image-width",
        description = "Resize images wider than this value (in pixels)."
    )

    val jpegQuality by parser.option(
        ArgType.Int, "jpeg-quality",
        description = "JPEG compression quality (1-100, higher = better quality/larger size)."
    )

    val preset by parser.option(
        ArgType.String, "preset",
        description = "Use preset configuration: fast, quality, small-size"
    )

    // ===== PERFORMANCE/CONCURRENCY =====
    val workers by parser.option(
        ArgType.Int, "workers", "w",
        "Number of concurrent image download workers per series (default: 4)."
    ).default(4)

    val batchWorkers by parser.option(
        ArgType.Int, "batch-workers", "bw",
        "Number of series to download concurrently (default: 1)."
    ).default(1)

    // ===== NETWORK CONFIGURATION =====
    val userAgentName by parser.option(
        ArgType.String, "user-agent", "ua",
        "Browser profile to impersonate. Use --list-user-agents to see available options."
    ).default("Chrome (Windows)")

    val perWorkerUserAgent by parser.option(
        ArgType.Boolean, "per-worker-ua",
        description = "Use a different random user agent for each download worker."
    ).default(false)

    val proxy by parser.option(
        ArgType.String, "proxy",
        description = "HTTP proxy URL (e.g., http://localhost:8080)"
    )

    // ===== SORTING AND FILTERING =====
    val sortBy by parser.option(
        ArgType.String, "sort-by",
        description = "Sort order for batch downloads. Use --list-sort-options to see choices."
    ).default("default")

    // ===== UTILITY/INFORMATION =====
    val listUserAgents by parser.option(
        ArgType.Boolean, "list-user-agents", "list-uas",
        "List all available user-agent profiles and exit."
    ).default(false)

    val listSortOptions by parser.option(
        ArgType.Boolean, "list-sort-options",
        description = "List all available sort options and exit."
    ).default(false)

    val dryRun by parser.option(
        ArgType.Boolean, "dry-run",
        description = "Preview what would be downloaded without actually downloading."
    ).default(false)

    val version by parser.option(
        ArgType.Boolean, "version", "v",
        description = "Show version information and exit."
    ).default(false)

    // ===== DEBUGGING =====
    val debug by parser.option(
        ArgType.Boolean, "debug",
        description = "Enable verbose debug logging."
    ).default(false)

    try {
        parser.parse(args)
    } catch (e: Exception) {
        println("Error: ${e.message}")
        println("\nTry 'manga-combiner --help' for more information.")
        return
    }

    if (version) {
        println("manga-combiner-cli v${AppVersion.NAME}")
        return
    }

    if (listUserAgents) {
        println("Available user-agent profiles:")
        val userAgentOptions = listOf("Random") + UserAgent.browsers.keys.toList()
        userAgentOptions.forEach { println("- $it") }
        return
    }

    if (listSortOptions) {
        println("Available sort options for --sort-by:")
        val sortOptions = listOf("default", "chapters-asc", "chapters-desc", "alpha-asc", "alpha-desc")
        sortOptions.forEach { println("- $it") }
        return
    }

    // Validation
    if (source.isEmpty() && !deleteCache) {
        println("Error: At least one --source must be provided.")
        println("\nTry 'manga-combiner --help' for usage examples.")
        return
    }

    if (scrape && search) {
        println("Error: --scrape and --search are mutually exclusive.")
        println("\nTry 'manga-combiner --help' for usage examples.")
        return
    }

    if (downloadAll && !search) {
        println("Error: --download-all requires --search")
        println("\nTry 'manga-combiner --help' for usage examples.")
        return
    }

    // NEW: Validation for metadata update
    if (updateMetadata) {
        if (source.isEmpty()) {
            println("Error: --update-metadata requires at least one source (EPUB file path)")
            println("Usage:")
            println("  manga-combiner --source my-manga.epub --update-metadata")
            println("  manga-combiner --source my-manga.epub --source https://example.com/series --update-metadata")
            println("\nTry 'manga-combiner --help' for more information.")
            return
        }

        val epubFile = File(source[0])
        if (!epubFile.exists()) {
            println("Error: EPUB file does not exist: ${source[0]}")
            return
        }

        if (!epubFile.name.endsWith(".epub", ignoreCase = true)) {
            println("Error: First source must be an EPUB file: ${source[0]}")
            return
        }

        // If second source provided, validate it's a URL
        if (source.size > 1 && !source[1].startsWith("http", ignoreCase = true)) {
            println("Error: Second source must be a valid HTTP/HTTPS URL: ${source[1]}")
            return
        }

        // Limit to maximum 2 sources for metadata update
        if (source.size > 2) {
            println("Error: --update-metadata accepts maximum 2 sources (EPUB file and optional source URL)")
            println("Provided ${source.size} sources: ${source.joinToString(", ")}")
            return
        }
    }

    if (listOf(force || redownloadExisting, skipExisting, update, updateMetadata).count { it } > 1) {
        println("Error: --force/--redownload-existing, --skip-existing, --update, and --update-metadata are mutually exclusive.")
        println("\nTry 'manga-combiner --help' for more information.")
        return
    }

    if (keep.isNotEmpty() && remove.isNotEmpty()) {
        println("Error: --keep and --remove are mutually exclusive.")
        println("\nTry 'manga-combiner --help' for more information.")
        return
    }

    val userAgentOptions = listOf("Random") + UserAgent.browsers.keys.toList()
    if (userAgentName !in userAgentOptions) {
        println("Error: Invalid user-agent '$userAgentName'.")
        println("Use --list-user-agents to see all available options.")
        return
    }

    val sortOptions = listOf("default", "chapters-asc", "chapters-desc", "alpha-asc", "alpha-desc")
    if (sortBy !in sortOptions) {
        println("Error: Invalid sort option '$sortBy'.")
        println("Use --list-sort-options to see available choices.")
        return
    }

    val currentPreset = preset // Read the delegated property into a local variable
    val presetOptions = listOf("fast", "quality", "small-size")
    if (currentPreset != null && currentPreset.lowercase() !in presetOptions) {
        println("Error: Invalid preset '$currentPreset'.")
        println("Available presets: ${presetOptions.joinToString(", ")}")
        return
    }

    val finalMaxWidth = maxWidth ?: if (optimize) 1200 else null
    val finalJpegQuality = jpegQuality ?: if (optimize) 85 else null
    val finalForce = force || redownloadExisting

    var cliArgs = CliArguments(
        source, search, scrape, refreshCache, downloadAll, cleanCache, deleteCache,
        ignoreCache, keep, remove, skipExisting, update, updateMetadata, format, title,
        outputPath, finalForce, deleteOriginal, debug, dryRun, exclude, workers,
        userAgentName, proxy, perWorkerUserAgent, batchWorkers, optimize,
        finalMaxWidth, finalJpegQuality, sortBy, cacheDir
    )

    // Apply preset if specified
    if (currentPreset != null) {
        cliArgs = applyPreset(currentPreset, cliArgs)
        Logger.logInfo("Applied preset: $currentPreset")
    }

    Logger.isDebugEnabled = cliArgs.debug

    // Custom Koin setup for CLI to inject the custom cache directory
    stopKoin()
    startKoin {
        modules(
            appModule,
            module {
                factory<PlatformProvider> { DesktopPlatformProvider(cliArgs.cacheDir) }
                single { SettingsRepository() }
                factory { FileMover() }
            }
        )
    }

    val downloadService: DownloadService = get(DownloadService::class.java)
    val processorService: ProcessorService = get(ProcessorService::class.java)
    val scraperService: ScraperService = get(ScraperService::class.java)
    val platformProvider: PlatformProvider = get(PlatformProvider::class.java)
    val fileConverter: FileConverter = get(FileConverter::class.java)
    val cacheService: CacheService = get(CacheService::class.java)
    val scrapeCacheService: ScrapeCacheService = get(ScrapeCacheService::class.java)

    if (cliArgs.deleteCache) {
        val allSeries = cacheService.getCacheContents()
        val seriesToDelete = when {
            cliArgs.remove.isNotEmpty() -> allSeries.filter { s -> cliArgs.remove.any { p -> s.seriesName.contains(p, ignoreCase = true) } }
            cliArgs.keep.isNotEmpty() -> allSeries.filterNot { s -> cliArgs.keep.any { p -> s.seriesName.contains(p, ignoreCase = true) } }
            else -> allSeries
        }

        if (seriesToDelete.isEmpty()) {
            Logger.logInfo("No cache items match the criteria for deletion.")
            return
        }
        val totalSize = seriesToDelete.sumOf { it.totalSizeInBytes }
        Logger.logInfo("The following items will be deleted (Total: ${formatSize(totalSize)}):")
        seriesToDelete.forEach { println("- ${it.seriesName} (${it.totalSizeFormatted})") }

        if (cliArgs.dryRun) {
            Logger.logInfo("\nDRY RUN: Aborting before deletion.")
            return
        }
        val pathsToDelete = seriesToDelete.map { it.path }
        cacheService.deleteCacheItems(pathsToDelete)
        return
    }

    runBlocking {
        // Handle metadata update operation
        if (cliArgs.updateMetadata) {
            Logger.logInfo("--- Starting metadata update operation ---")
            val sourceUrlOverride = if (cliArgs.source.size > 1) cliArgs.source[1] else null
            processMetadataUpdate(
                epubPath = cliArgs.source[0],
                sourceUrlOverride = sourceUrlOverride,
                cliArgs = cliArgs,
                scraperService = scraperService,
                processorService = processorService
            )
            Logger.logInfo("--- Metadata update operation complete ---")
            return@runBlocking
        }

        if (cliArgs.scrape) {
            val userAgent = UserAgent.browsers[cliArgs.userAgentName] ?: UserAgent.browsers.values.first()
            val client = createHttpClient(cliArgs.proxy)
            try {
                val startUrl = cliArgs.source.firstOrNull()
                if (startUrl == null) {
                    Logger.logError("A source URL must be provided with --scrape.")
                    return@runBlocking
                }

                var allSeries: List<SearchResult>
                val cachedData = if (!cliArgs.refreshCache) scrapeCacheService.loadCache() else null

                if (cachedData != null) {
                    val ageInMillis = System.currentTimeMillis() - cachedData.lastUpdated
                    val ageInHours = TimeUnit.MILLISECONDS.toHours(ageInMillis)
                    Logger.logInfo("Using cached scrape data from $ageInHours hours ago. Use --refresh-cache to force an update.")
                    allSeries = cachedData.series.map { SearchResult(it.title, it.url, "", chapterCount = it.chapterCount) }
                } else {
                    Logger.logInfo("Starting initial scrape from URL: $startUrl")
                    val results = scraperService.findAllSeriesUrls(client, startUrl, userAgent)
                    Logger.logInfo("Found ${results.size} series. Now fetching chapter counts for caching (this may take a while)...")

                    val processedSeries = mutableListOf<ScrapedSeries>()
                    for ((index, seriesResult) in results.withIndex()) {
                        try {
                            coroutineContext.ensureActive()
                            Logger.logInfo("Processing [${index + 1}/${results.size}]: ${seriesResult.title}")
                            val chapters = scraperService.findChapterUrlsAndTitles(client, seriesResult.url, userAgent)
                            processedSeries.add(ScrapedSeries(seriesResult.title, seriesResult.url, chapters.size))
                        } catch (e: Exception) {
                            Logger.logError("Failed to fetch chapters for ${seriesResult.title}: ${e.message}")
                            processedSeries.add(ScrapedSeries(seriesResult.title, seriesResult.url, chapterCount = null))
                        } finally {
                            // Save incrementally after each series
                            val cacheToSave = ScrapedSeriesCache(
                                lastUpdated = System.currentTimeMillis(),
                                series = processedSeries
                            )
                            scrapeCacheService.saveCache(cacheToSave)
                        }
                    }
                    allSeries = processedSeries.map { SearchResult(it.title, it.url, "", chapterCount = it.chapterCount) }
                }

                if (allSeries.isEmpty()) {
                    Logger.logInfo("No series found to download.")
                    return@runBlocking
                }

                val sortedSeries = sortSeries(allSeries, cliArgs.sortBy)
                var seriesToDownload = sortedSeries

                if (!cliArgs.force && !cliArgs.skipExisting && !cliArgs.update) {
                    val (skippable, processable) = sortedSeries.partition {
                        val mangaTitle = it.url.toSlug().replace('-', ' ').titlecase()
                        val finalOutputPath = cliArgs.outputPath.ifBlank { platformProvider.getUserDownloadsDir() ?: "" }
                        val finalFileName = "${FileUtils.sanitizeFilename(mangaTitle)}.${cliArgs.format}"
                        File(finalOutputPath, finalFileName).exists()
                    }
                    skippable.forEach { Logger.logError("[SKIP] Output file for '${it.title}' already exists. Use --force, --skip-existing, or --update.") }
                    seriesToDownload = processable
                }

                println()
                if (seriesToDownload.isNotEmpty()) {
                    Logger.logInfo("The following ${seriesToDownload.size} series will be downloaded:")
                    seriesToDownload.forEach { println("- ${it.title} (${it.chapterCount ?: 0} Chapters)") }
                    Logger.logInfo("--- Starting batch scrape and download ---")
                    runDownloadsAndPackaging(seriesToDownload.map { it.url }, cliArgs, downloadService, processorService, scraperService, platformProvider, cacheService)
                } else {
                    Logger.logInfo("No new series to download based on the pre-flight check.")
                }
            } catch (e: Exception) {
                Logger.logError("Scraping failed", e)
            } finally {
                client.close()
            }
            return@runBlocking
        }

        if (cliArgs.search) {
            val query = cliArgs.source.joinToString(" ")
            val userAgent = UserAgent.browsers[cliArgs.userAgentName] ?: UserAgent.browsers.values.first()
            Logger.logInfo("Searching for '$query'...")
            val client = createHttpClient(cliArgs.proxy)
            try {
                val initialResults = scraperService.search(client, query, userAgent)
                if (initialResults.isEmpty()) {
                    Logger.logInfo("No results found."); return@runBlocking
                }

                Logger.logInfo("Found ${initialResults.size} results. Fetching chapter counts...")
                val detailedResults = coroutineScope {
                    initialResults.map { async { scraperService.findChapterUrlsAndTitles(client, it.url, userAgent).let { ch -> it.copy(chapterCount = ch.size) } } }.awaitAll()
                }

                if (cliArgs.downloadAll) {
                    val sortedResults = sortSeries(detailedResults, cliArgs.sortBy)
                    var seriesToDownload = sortedResults
                    if (!cliArgs.force && !cliArgs.skipExisting && !cliArgs.update) {
                        val (skippable, processable) = sortedResults.partition {
                            val mangaTitle = it.url.toSlug().replace('-', ' ').titlecase()
                            val finalOutputPath = cliArgs.outputPath.ifBlank { platformProvider.getUserDownloadsDir() ?: "" }
                            val finalFileName = "${FileUtils.sanitizeFilename(mangaTitle)}.${cliArgs.format}"
                            File(finalOutputPath, finalFileName).exists()
                        }
                        skippable.forEach { Logger.logError("[SKIP] Output file for '${it.title}' already exists. Use --force, --skip-existing, or --update.") }
                        seriesToDownload = processable
                    }

                    println()
                    if (seriesToDownload.isNotEmpty()) {
                        Logger.logInfo("The following ${seriesToDownload.size} series will be downloaded:")
                        seriesToDownload.forEach { println("- ${it.title} (${it.chapterCount ?: "N/A"} Chapters)") }
                        Logger.logInfo("--- Starting batch download ---")
                        runDownloadsAndPackaging(seriesToDownload.map { it.url }, cliArgs, downloadService, processorService, scraperService, platformProvider, cacheService)
                    } else {
                        Logger.logInfo("No new series to download based on the pre-flight check.")
                    }
                } else {
                    println()
                    detailedResults.forEachIndexed { i, r -> println("[${i + 1}] ${r.title} (${r.chapterCount ?: "N/A"} Chapters)\n    URL: ${r.url}\n") }
                }
            } catch (e: Exception) {
                Logger.logError("Search failed", e)
            } finally {
                client.close()
            }
            return@runBlocking
        }

        val (localFiles, urls) = cliArgs.source.partition { File(it).exists() }
        if (localFiles.isNotEmpty()) {
            Logger.logInfo("--- Processing ${localFiles.size} local file(s) ---")
            for (file in localFiles) {
                processLocalFile(file, cliArgs, fileConverter, processorService, platformProvider)
            }
        }
        if (urls.isNotEmpty()) {
            runDownloadsAndPackaging(urls, cliArgs, downloadService, processorService, scraperService, platformProvider, cacheService)
        }

        Logger.logInfo("--- All operations complete. ---")
    }
}
