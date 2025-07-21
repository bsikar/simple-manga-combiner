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
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.get
import java.io.File
import java.util.concurrent.TimeUnit
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
    if (cliArgs.format == "cbz") {
        processorService.createCbzFromFolders(result.mangaTitle, result.allFoldersForPackaging, result.outputFile, result.sourceUrl, result.failedChapters, cliArgs.maxWidth, cliArgs.jpegQuality)
    } else {
        processorService.createEpubFromFolders(result.mangaTitle, result.allFoldersForPackaging, result.outputFile, result.sourceUrl, result.failedChapters, cliArgs.maxWidth, cliArgs.jpegQuality)
    }
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
                cliArgs.updateExisting -> {
                    Logger.logInfo("Update logic is not yet fully implemented in this CLI version. Use --force to redownload.")
                    return null
                }
                !cliArgs.force && !cliArgs.dryRun -> {
                    Logger.logError("Output file exists for $source: ${outputFile.absolutePath}. Use --force, --skip-existing, or --update-existing.")
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
        logOperationSettings(downloadOptions, chaptersToDownload.size, cliArgs.userAgentName, cliArgs.perWorkerUserAgent, cacheCount = allOnlineChapters.size - chaptersToDownload.size, optimizeMode = cliArgs.optimize, cleanCache = cliArgs.cleanCache, skipExisting = cliArgs.skipExisting, updateExisting = cliArgs.updateExisting, force = cliArgs.force, maxWidth = cliArgs.maxWidth, jpegQuality = cliArgs.jpegQuality)

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
    val updateExisting: Boolean,
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

fun main(args: Array<String>) {
    val parser = ArgParser("manga-combiner-cli v${AppVersion.NAME}", useDefaultHelpShortName = false)
    val source by parser.option(ArgType.String, "source", "s", "Source URL, local file, or search query.").multiple()
    val search by parser.option(ArgType.Boolean, "search", description = "Search for a series and print results.").default(false)
    val scrape by parser.option(ArgType.Boolean, "scrape", description = "Scrape and download all series from the provided source URL (e.g., a list/genre page).").default(false)
    val refreshCache by parser.option(ArgType.Boolean, "refresh-cache", description = "Force a new scrape, overwriting the existing cache. Use with --scrape.").default(false)
    val downloadAll by parser.option(ArgType.Boolean, "download-all", description = "Download all results from a search. Must be used with --search.").default(false)
    val ignoreCache by parser.option(ArgType.Boolean, "ignore-cache", description = "Force re-download of all chapters, ignoring existing cache.").default(false)
    val cleanCache by parser.option(ArgType.Boolean, "clean-cache", description = "Delete a series' temporary folders after its download succeeds.").default(false)
    val deleteCache by parser.option(ArgType.Boolean, "delete-cache", description = "Delete cached downloads and exit.").default(false)
    val keep by parser.option(ArgType.String, "keep", description = "Pattern to keep when deleting cache (e.g., --keep 'One Piece'). Can be used multiple times.").multiple()
    val remove by parser.option(ArgType.String, "remove", description = "Pattern to remove when deleting cache (e.g., --remove 'Jujutsu'). Can be used multiple times.").multiple()
    val skipExisting by parser.option(ArgType.Boolean, "skip-existing", description = "Skip download if the output file already exists.").default(false)
    val updateExisting by parser.option(ArgType.Boolean, "update-existing", description = "Update existing file with new chapters from the source URL.").default(false)
    val redownloadExisting by parser.option(ArgType.Boolean, "redownload-existing", description = "Alias for --force.").default(false)
    val force by parser.option(ArgType.Boolean, "force", "f", "Force overwrite/redownload of an existing file.").default(false)
    val optimize by parser.option(ArgType.Boolean, "optimize", description = "Enable image optimizations to reduce file size (sets --max-image-width 1200, --jpeg-quality 85).\n\tWARNING: This will significantly increase processing time.").default(false)
    val maxWidth by parser.option(ArgType.Int, "max-image-width", description = "Resize images to this maximum width in pixels.")
    val jpegQuality by parser.option(ArgType.Int, "jpeg-quality", description = "Set JPEG compression quality (1-100).")
    val format by parser.option(
        ArgType.Choice(listOf("cbz", "epub"), { it }),
        "format",
        description = "Output format for downloaded files."
    ).default("epub")
    val title by parser.option(ArgType.String, "title", "t", "Custom output file title.")
    val outputPath by parser.option(ArgType.String, "output", "o", "Directory to save the final file.").default("")
    val deleteOriginal by parser.option(ArgType.Boolean, "delete-original", description = "Delete source on success.").default(false)
    val debug by parser.option(ArgType.Boolean, "debug", description = "Enable debug logging.").default(false)
    val dryRun by parser.option(ArgType.Boolean, "dry-run", description = "Simulate operations without creating final files.").default(false)
    val exclude by parser.option(ArgType.String, "exclude", "e", "Chapter URL slug to exclude.").multiple()
    val workers by parser.option(ArgType.Int, "workers", "w", "Number of concurrent image download workers.").default(4)
    val batchWorkers by parser.option(ArgType.Int, "batch-workers", "bw", "Number of concurrent series to process.").default(1)
    val listUserAgents by parser.option(ArgType.Boolean, "list-user-agents", "list-uas", "List all available user-agent profiles and exit.").default(false)
    val userAgentName by parser.option(ArgType.String, "user-agent", "ua", "Browser profile to impersonate. Use --list-user-agents for choices.").default("Chrome (Windows)")
    val proxy by parser.option(ArgType.String, "proxy", description = "Proxy URL (e.g., http://host:port)")
    val perWorkerUserAgent by parser.option(ArgType.Boolean, "per-worker-ua", description = "Use a different random user agent for each worker.").default(false)
    val cacheDir by parser.option(ArgType.String, "cache-dir", description = "Specify a custom directory for cache and temporary files.")
    val listSortOptions by parser.option(ArgType.Boolean, "list-sort-options", description = "List all available sort options and exit.").default(false)
    val sortBy by parser.option(ArgType.String, "sort-by", description = "Sort series before batch downloading. Use --list-sort-options.").default("default")

    try {
        parser.parse(args)
    } catch (e: Exception) {
        println("Error parsing arguments: ${e.message}")
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

    val userAgentOptions = listOf("Random") + UserAgent.browsers.keys.toList()
    if (userAgentName !in userAgentOptions) {
        println("Error: Invalid user-agent '$userAgentName'.")
        println("Use --list-user-agents to see all available options.")
        return
    }

    val sortOptions = listOf("default", "chapters-asc", "chapters-desc", "alpha-asc", "alpha-desc")
    if (sortBy !in sortOptions) {
        println("Error: Invalid sort option '$sortBy'. Use --list-sort-options to see available choices.")
        return
    }

    val finalMaxWidth = maxWidth ?: if (optimize) 1200 else null
    val finalJpegQuality = jpegQuality ?: if (optimize) 85 else null
    val finalForce = force || redownloadExisting

    val cliArgs = CliArguments(source, search, scrape, refreshCache, downloadAll, cleanCache, deleteCache, ignoreCache, keep, remove, skipExisting, updateExisting, format, title, outputPath, finalForce, deleteOriginal, debug, dryRun, exclude, workers, userAgentName, proxy, perWorkerUserAgent, batchWorkers, optimize, finalMaxWidth, finalJpegQuality, sortBy, cacheDir)
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

    if (cliArgs.scrape && cliArgs.search) {
        println("Error: --scrape and --search are mutually exclusive.")
        return
    }
    if (listOf(cliArgs.force, cliArgs.skipExisting, cliArgs.updateExisting).count { it } > 1) {
        println("Error: --force/--redownload-existing, --skip-existing, and --update-existing are mutually exclusive.")
        return
    }
    if (cliArgs.keep.isNotEmpty() && cliArgs.remove.isNotEmpty()) {
        println("Error: --keep and --remove are mutually exclusive.")
        return
    }

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

    if (cliArgs.source.isEmpty()) {
        println("Error: At least one --source must be provided.")
        return
    }

    runBlocking {
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

                if (!cliArgs.force && !cliArgs.skipExisting && !cliArgs.updateExisting) {
                    val (skippable, processable) = sortedSeries.partition {
                        val mangaTitle = it.url.toSlug().replace('-', ' ').titlecase()
                        val finalOutputPath = cliArgs.outputPath.ifBlank { platformProvider.getUserDownloadsDir() ?: "" }
                        val finalFileName = "${FileUtils.sanitizeFilename(mangaTitle)}.${cliArgs.format}"
                        File(finalOutputPath, finalFileName).exists()
                    }
                    skippable.forEach { Logger.logError("[SKIP] Output file for '${it.title}' already exists. Use --force, --skip-existing, or --update-existing.") }
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
                    if (!cliArgs.force && !cliArgs.skipExisting && !cliArgs.updateExisting) {
                        val (skippable, processable) = sortedResults.partition {
                            val mangaTitle = it.url.toSlug().replace('-', ' ').titlecase()
                            val finalOutputPath = cliArgs.outputPath.ifBlank { platformProvider.getUserDownloadsDir() ?: "" }
                            val finalFileName = "${FileUtils.sanitizeFilename(mangaTitle)}.${cliArgs.format}"
                            File(finalOutputPath, finalFileName).exists()
                        }
                        skippable.forEach { Logger.logError("[SKIP] Output file for '${it.title}' already exists. Use --force, --skip-existing, or --update-existing.") }
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
