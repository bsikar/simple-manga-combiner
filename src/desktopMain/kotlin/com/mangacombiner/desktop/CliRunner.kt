package com.mangacombiner.desktop

import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.di.appModule
import com.mangacombiner.model.AppSettings
import com.mangacombiner.model.IpInfo
import com.mangacombiner.model.ScrapedSeries
import com.mangacombiner.model.ScrapedSeriesCache
import com.mangacombiner.model.ScrapedWebsiteCache
import com.mangacombiner.model.SearchResult
import com.mangacombiner.service.*
import com.mangacombiner.util.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
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
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private data class DownloadResultForPackaging(
    val mangaTitle: String,
    val downloadDir: File,
    val seriesSlug: String,
    val outputFile: File,
    val sourceUrl: String,
    val failedChapters: Map<String, List<String>>?,
    val allFoldersForPackaging: List<File>,
    val seriesMetadata: SeriesMetadata?
)

private suspend fun packageSeries(
    result: DownloadResultForPackaging,
    cliArgs: CliArguments,
    processorService: ProcessorService
) {
    Logger.logInfo("Creating ${cliArgs.format.uppercase()} for ${result.mangaTitle} from ${result.allFoldersForPackaging.size} total chapters...")
    processorService.createEpubFromFolders(
        mangaTitle = result.mangaTitle,
        chapterFolders = result.allFoldersForPackaging,
        outputFile = result.outputFile,
        seriesUrl = result.sourceUrl,
        failedChapters = result.failedChapters,
        seriesMetadata = result.seriesMetadata,
        maxWidth = cliArgs.maxWidth,
        jpegQuality = cliArgs.jpegQuality
    )

    if (result.outputFile.exists()) {
        Logger.logInfo("Successfully created: ${result.outputFile.absolutePath}")
        if (cliArgs.cleanCache) {
            val size = result.downloadDir.walk().sumOf { it.length() }
            Logger.logInfo("Cleaning cache for ${result.seriesSlug}... Reclaimed ${formatSize(size)}")
            result.downloadDir.deleteRecursively()
        }
    }
}

private suspend fun runUpdateProcess(
    cliArgs: CliArguments,
    outputFile: File,
    allOnlineChapters: List<Pair<String, String>>,
    seriesMetadata: SeriesMetadata,
    platformProvider: PlatformProvider,
    processorService: ProcessorService,
    downloadService: DownloadService
) {
    Logger.logInfo("--- Updating existing file: ${outputFile.name} ---")

    val (localChapterSlugs, _, _) = processorService.getChaptersAndInfoFromFile(outputFile)
    if (localChapterSlugs.isEmpty()) {
        Logger.logError("Could not read any chapters from the existing file. Please use --force to redownload.")
        return
    }

    val newChapterPairs = allOnlineChapters.filter { it.second.toSlug() !in localChapterSlugs }

    if (newChapterPairs.isEmpty()) {
        Logger.logInfo("File is already up-to-date. No new chapters found.")
        // Even if no new chapters, update metadata if requested
        if (cliArgs.updateMetadata) {
            Logger.logInfo("Updating metadata as requested...")
            updateEpubMetadata(outputFile, allOnlineChapters.first().first.substringBeforeLast("/"), cliArgs, get(ScraperService::class.java), processorService)
        }
        return
    }

    Logger.logInfo("Found ${newChapterPairs.size} new chapters to download.")
    val tempDir = File(platformProvider.getTmpDir(), "manga-update-${UUID.randomUUID()}").apply { mkdirs() }
    val backupFile = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}.backup.epub")

    try {
        // 1. Extract old chapters
        Logger.logInfo("Extracting ${localChapterSlugs.size} existing chapters...")
        val oldChapterFolders = processorService.extractChaptersToDirectory(outputFile, localChapterSlugs, tempDir)

        // 2. Download new chapters
        val mangaTitle = cliArgs.title?.ifBlank { null } ?: seriesMetadata.title
        val sourceUrl = allOnlineChapters.first().first.substringBeforeLast("/")
        val downloadOptions = createDownloadOptions(sourceUrl, newChapterPairs.toMap(), mangaTitle, cliArgs, platformProvider)
        val downloadResult = downloadService.downloadChapters(downloadOptions, tempDir)
        val newChapterFolders = downloadResult?.successfulFolders ?: emptyList()

        if (newChapterFolders.isEmpty()) {
            Logger.logError("Failed to download any of the new chapters. Aborting update.")
            return
        }

        // 3. Combine and re-package
        val allFoldersForPackaging = (oldChapterFolders + newChapterFolders).sortedBy { it.name }
        Logger.logInfo("Combining ${oldChapterFolders.size} old and ${newChapterFolders.size} new chapters...")

        // Backup original file
        outputFile.copyTo(backupFile, overwrite = true)
        Logger.logInfo("Created backup: ${backupFile.name}")

        processorService.createEpubFromFolders(
            mangaTitle = mangaTitle,
            chapterFolders = allFoldersForPackaging,
            outputFile = outputFile,
            seriesUrl = sourceUrl,
            failedChapters = downloadResult?.failedChapters,
            seriesMetadata = seriesMetadata,
            maxWidth = cliArgs.maxWidth,
            jpegQuality = cliArgs.jpegQuality
        )

        if (outputFile.exists() && outputFile.length() > backupFile.length()) {
            Logger.logInfo("Successfully updated file: ${outputFile.absolutePath}")
            backupFile.delete()
        } else {
            Logger.logError("Update failed. Restoring from backup.")
            backupFile.copyTo(outputFile, overwrite = true)
            backupFile.delete()
        }

    } finally {
        tempDir.deleteRecursively()
    }
}

private suspend fun downloadSeriesToCache(
    source: String,
    cliArgs: CliArguments,
    downloadService: DownloadService,
    scraperService: ScraperService,
    platformProvider: PlatformProvider,
    cacheService: CacheService,
    processorService: ProcessorService
): DownloadResultForPackaging? {
    val tempDir = File(platformProvider.getTmpDir())
    val finalProxyUrl = buildProxyUrlFromCliArgs(cliArgs)
    val listClient = createHttpClient(finalProxyUrl)

    try {
        if (!source.startsWith("http", ignoreCase = true)) {
            Logger.logError("Cannot download from local file source '$source'. Skipping.")
            return null
        }

        Logger.logInfo("--- Processing URL: $source ---")
        val listScraperAgent = UserAgent.browsers[cliArgs.userAgentName] ?: UserAgent.browsers.values.first()
        val (seriesMetadata, allOnlineChapters) = scraperService.fetchSeriesDetails(listClient, source, listScraperAgent, cliArgs.allowNsfw)

        if (seriesMetadata == null) {
            Logger.logInfo("Series at $source was filtered out (likely NSFW content and --allow-nsfw is off). Skipping.")
            return null
        }
        if (allOnlineChapters.isEmpty()) {
            Logger.logError("No chapters found at $source. Skipping."); return null
        }

        val mangaTitle = cliArgs.title?.ifBlank { null } ?: seriesMetadata.title
        val finalOutputPath = cliArgs.outputPath.ifBlank { platformProvider.getUserDownloadsDir() ?: "" }
        val finalFileName = "${FileUtils.sanitizeFilename(mangaTitle)}.${cliArgs.format}"
        val outputFile = File(finalOutputPath, finalFileName)

        if (outputFile.exists()) {
            when {
                cliArgs.skipExisting -> { Logger.logInfo("Output file ${outputFile.name} already exists. Skipping."); return null }
                cliArgs.update -> {
                    runUpdateProcess(cliArgs, outputFile, allOnlineChapters, seriesMetadata, platformProvider, processorService, downloadService)
                    return null // Update process is self-contained
                }
                !cliArgs.force && !cliArgs.dryRun -> {
                    Logger.logError("Output file exists for $source: ${outputFile.absolutePath}. Use --force, --skip-existing, or --update.")
                    return null
                }
            }
        }

        val chaptersToProcess = if (cliArgs.exclude.isNotEmpty()) {
            allOnlineChapters.filter { (url, _) -> url.trimEnd('/').substringAfterLast('/') !in cliArgs.exclude }
        } else {
            allOnlineChapters
        }

        val seriesSlug = source.toSlug()
        var chaptersToDownload = chaptersToProcess

        if (!cliArgs.ignoreCache) {
            val cachedChapterStatus = cacheService.getCachedChapterStatus(seriesSlug)
            val (cached, toDownload) = chaptersToProcess.partition {
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
                return DownloadResultForPackaging(mangaTitle, downloadDir, seriesSlug, outputFile, source, null, allFoldersForPackaging, seriesMetadata)
            } else {
                Logger.logError("Cache is inconsistent; files not found. Please try again with --ignore-cache.")
                return null
            }
        }

        val downloadOptions = createDownloadOptions(source, chaptersToDownload.toMap(), mangaTitle, cliArgs, platformProvider)
        logOperationSettings(downloadOptions, chaptersToDownload.size, cliArgs.userAgentName, cliArgs.perWorkerUserAgent, proxy = finalProxyUrl, cacheCount = allOnlineChapters.size - chaptersToDownload.size, optimizeMode = cliArgs.optimize, cleanCache = cliArgs.cleanCache, skipExisting = cliArgs.skipExisting, updateExisting = cliArgs.update, force = cliArgs.force, maxWidth = cliArgs.maxWidth, jpegQuality = cliArgs.jpegQuality)

        if (cliArgs.dryRun) {
            Logger.logInfo("DRY RUN: Would download ${chaptersToDownload.size} new chapters for $source.")
            return null
        }

        val downloadResult = downloadService.downloadChapters(downloadOptions, downloadDir)
        return if (downloadResult != null && downloadResult.successfulFolders.isNotEmpty()) {
            val allFoldersForPackaging = (downloadDir.listFiles { file -> file.isDirectory }?.toList() ?: emptyList())
            DownloadResultForPackaging(mangaTitle, downloadDir, seriesSlug, outputFile, source, downloadResult.failedChapters, allFoldersForPackaging, seriesMetadata)
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

private suspend fun updateEpubMetadata(
    epubFile: File,
    sourceUrl: String,
    cliArgs: CliArguments,
    scraperService: ScraperService,
    processorService: ProcessorService
) {
    Logger.logInfo("--- Updating metadata for: ${epubFile.name} ---")
    if (!epubFile.exists() || !epubFile.name.endsWith(".epub", ignoreCase = true)) {
        Logger.logError("Provided file is not a valid EPUB: ${epubFile.absolutePath}")
        return
    }

    if (cliArgs.dryRun) {
        Logger.logInfo("DRY RUN: Would update metadata for ${epubFile.name} using source: $sourceUrl")
        return
    }

    val client = createHttpClient(buildProxyUrlFromCliArgs(cliArgs))
    val backupFile = File(epubFile.parentFile, "${epubFile.nameWithoutExtension}.backup.epub")
    try {
        val userAgent = UserAgent.browsers[cliArgs.userAgentName] ?: UserAgent.browsers.values.first()
        Logger.logInfo("Scraping metadata from source: $sourceUrl")
        val (seriesMetadata, _) = scraperService.fetchSeriesDetails(client, sourceUrl, userAgent, cliArgs.allowNsfw)
        if (seriesMetadata == null) {
            Logger.logError("Series at $sourceUrl was filtered out or no metadata found. Cannot update.")
            return
        }
        Logger.logInfo("Successfully scraped metadata for: ${seriesMetadata.title}")

        if (!backupFile.exists()) {
            Logger.logInfo("Creating backup: ${backupFile.name}")
            epubFile.copyTo(backupFile, overwrite = false)
        }

        val success = processorService.updateEpubMetadata(
            epubFile = epubFile,
            seriesMetadata = seriesMetadata,
            sourceUrl = sourceUrl
        )

        if (success) {
            Logger.logInfo("Successfully updated metadata for: ${epubFile.name}")
            backupFile.delete()
        } else {
            Logger.logError("Failed to update metadata. Restoring from backup...")
            backupFile.copyTo(epubFile, overwrite = true)
            backupFile.delete()
        }
    } catch (e: Exception) {
        Logger.logError("Error updating metadata for ${epubFile.name}: ${e.message}", e)
        if (backupFile.exists()) {
            Logger.logInfo("Restoring from backup due to error...")
            backupFile.copyTo(epubFile, overwrite = true)
            backupFile.delete()
        }
    } finally {
        client.close()
    }
}

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

    val sourceUrl = sourceUrlOverride ?: run {
        Logger.logInfo("No source URL provided for ${epubFile.name}, attempting to extract from EPUB metadata...")
        val extractedUrl = ZipUtils.getSourceUrlFromEpub(epubFile)
        if (extractedUrl == null) {
            Logger.logError("Could not extract source URL from ${epubFile.name}. Please provide a source URL with another --source flag.")
            return
        }
        extractedUrl
    }

    updateEpubMetadata(epubFile, sourceUrl, cliArgs, scraperService, processorService)
}

data class CliArguments(
    val source: List<String>,
    val search: Boolean,
    val searchSource: String,
    val scrape: Boolean,
    val refreshCache: Boolean,
    val updateScrapeCache: Boolean,
    val downloadAll: Boolean,
    val cleanCache: Boolean,
    val deleteCache: Boolean,
    val ignoreCache: Boolean,
    val keep: List<String>,
    val remove: List<String>,
    val skipExisting: Boolean,
    val update: Boolean,
    val updateMetadata: Boolean,
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
    val proxyType: String?,
    val proxyHost: String?,
    val proxyPort: Int?,
    val proxyUser: String?,
    val proxyPass: String?,
    val checkIp: Boolean,
    val ipLookupUrl: String?,
    val perWorkerUserAgent: Boolean,
    val batchWorkers: Int,
    val optimize: Boolean,
    val maxWidth: Int?,
    val jpegQuality: Int?,
    val sortBy: String,
    val cacheDir: String?,
    val allowNsfw: Boolean
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
                val downloadResult = downloadSeriesToCache(src, cliArgs, downloadService, scraperService, platformProvider, cacheService, processorService)
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

private fun sortSeries(series: List<SearchResult>, sortBy: String): List<SearchResult> {
    Logger.logInfo("Sorting ${series.size} series by '$sortBy'...")
    return when (sortBy) {
        "chapters-asc" -> series.sortedBy { it.chapterCount ?: 0 }
        "chapters-desc" -> series.sortedByDescending { it.chapterCount ?: 0 }
        "alpha-asc" -> series.sortedBy { it.title }
        "alpha-desc" -> series.sortedByDescending { it.title }
        else -> series
    }
}

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

            # Update metadata of a single EPUB file
            manga-combiner-cli --source my-manga.epub --update-metadata --source https://example.com/manga/series-url
            
            # Batch update metadata for all EPUBs in the current directory (extracts URL from each file)
            manga-combiner-cli --source *.epub --update-metadata

            # Use a SOCKS5 proxy with authentication
            manga-combiner-cli --proxy-type socks5 --proxy-host 127.0.0.1 --proxy-port 1080 --proxy-user "user" --proxy-pass "pass" --source ...

            # Use a simple HTTP proxy via the combined URL format
            manga-combiner-cli --proxy http://127.0.0.1:8080 --source ...

            # Use preset configurations for common scenarios
            manga-combiner-cli --preset fast --source https://example.com/manga/series
            manga-combiner-cli --preset quality --source https://example.com/manga/series
            manga-combiner-cli --preset small-size --source https://example.com/manga/series

            # Sort scraping results by chapter count or alphabetically
            manga-combiner-cli --scrape --sort-by chapters-desc --source https://example.com/manga-list

            INPUT OPTIONS:
            -s, --source <URL|FILE|QUERY>     Source URL, local EPUB file, or search query (can be used multiple times)

            DISCOVERY & SEARCH:
            --search                          Search for manga by name and display results
            --search-source <SOURCE>          Search source: mangaread.org or manhwaus.net (default: mangaread.org)
            --scrape                          Batch download all series from a list/genre page URL
            --download-all                    Download all search results (use with --search)
            --sort-by <METHOD>                Sort search/scrape results (use --list-sort-options to see the list)

            OUTPUT OPTIONS:
            --format <epub>                   Output format (default: epub)
            -t, --title <NAME>                Custom title for output file
            -o, --output <DIR>                Output directory (default: Downloads)

            DOWNLOAD BEHAVIOR:
            -f, --force                       Force overwrite existing files
            --skip-existing                   Skip if output file exists (good for batch)
            --update                          Update an existing EPUB with new chapters
            --update-metadata                 Update metadata of existing EPUB(s) using scraped series data
            -e, --exclude <SLUG>              Exclude chapters by slug (e.g., 'chapter-4.5'). Can be used multiple times.
            --delete-original                 Delete source file after successful conversion (local files only)
            --allow-nsfw                      Allow downloading of NSFW (Not Safe For Work) content.

            CACHE MANAGEMENT:
            --ignore-cache                    Force re-download all chapters, ignoring cached files
            --clean-cache                     Delete temp files after successful download to save disk space
            --refresh-cache                   Force refresh of scraped series list. Use with --scrape.
            --update-scrape-cache             Refresh the cached series list. If --source URLs are provided, only those
                                                sites are updated. Otherwise, all sites in the cache are refreshed.
            --delete-cache                    Delete cached downloads and exit. Use with --keep or --remove for selective deletion.
            --keep <PATTERN>                  Keep matching series when deleting cache
            --remove <PATTERN>                Remove matching series when deleting cache
            --cache-dir <DIR>                 Custom cache directory

            NETWORK & PROXY:
            --proxy <URL>                     Legacy proxy URL (e.g., socks5://user:pass@host:1080)
            --proxy-type <http|socks5>        Proxy type. Use with --proxy-host and --proxy-port.
            --proxy-host <HOST>               Proxy host or IP address.
            --proxy-port <PORT>               Proxy port number.
            --proxy-user <USER>               Proxy username (optional).
            --proxy-pass <PASS>               Proxy password (optional).
            -ua, --user-agent <NAME>          Browser to impersonate (see --list-user-agents)
            --per-worker-ua                   Use a different random user agent for each download worker.
            --ip-lookup-url <URL>             Custom URL for IP lookup (e.g., http://ip-api.com/json)

            PERFORMANCE & OPTIMIZATION:
            -w, --workers <N>                 Concurrent image downloads per series (default: 4)
            -bw, --batch-workers <N>          Concurrent series downloads (default: 1)
            --optimize                        Enable image optimization (reduces file size, SLOW)
            --max-image-width <PIXELS>        Maximum image width for optimization
            --jpeg-quality <1-100>            JPEG compression quality (lower = smaller file)
            --preset <NAME>                   Apply preset configuration (fast, quality, small-size)

            UTILITY:
            --check-ip                        Check public IP address through the configured proxy and exit.
            --dry-run                         Preview actions without downloading
            --debug                           Enable verbose logging
            --list-user-agents                Show available user agents
            --list-sort-options               Show available sort methods
            -v, --version                     Show version information and exit
            --help                            Show this help message

            PRESET CONFIGURATIONS:
            fast        - 8 workers, 3 batch workers, no optimization (fastest download)
            quality     - 4 workers, 1 batch worker, no optimization, original image quality
            small-size  - 2 workers, 1 batch worker, optimization enabled, max width 1000px, 75% JPEG quality
                """.trimIndent()
    println(helpText)
}

fun applyPreset(preset: String, args: CliArguments): CliArguments {
    return when (preset.lowercase()) {
        "fast" -> args.copy(workers = 8, batchWorkers = 3, optimize = false)
        "quality" -> args.copy(workers = 4, batchWorkers = 1, optimize = false, maxWidth = null, jpegQuality = null)
        "small-size" -> args.copy(workers = 2, batchWorkers = 1, optimize = true, maxWidth = 1000, jpegQuality = 75)
        else -> args
    }
}

private fun buildProxyUrlFromCliArgs(cliArgs: CliArguments): String? {
    if (cliArgs.proxyType != null && cliArgs.proxyHost != null && cliArgs.proxyPort != null) {
        val scheme = cliArgs.proxyType.lowercase()
        val auth = cliArgs.proxyUser?.let { user ->
            "${user.trim()}:${cliArgs.proxyPass?.trim() ?: ""}@"
        } ?: ""
        return "$scheme://$auth${cliArgs.proxyHost.trim()}:${cliArgs.proxyPort}"
    }
    return cliArgs.proxy
}

fun main(args: Array<String>) {
    if (args.contains("--help") || args.contains("-h")) {
        printCustomHelp(); return
    }
    if (args.contains("--version") || args.contains("-v")) {
        println("manga-combiner-cli v${AppVersion.NAME}"); return
    }

    val parser = ArgParser("manga-combiner-cli v${AppVersion.NAME}", useDefaultHelpShortName = false)
    val source by parser.option(ArgType.String, "source", "s").multiple()
    val search by parser.option(ArgType.Boolean, "search").default(false)
    val searchSource by parser.option(ArgType.Choice(listOf("mangaread.org", "manhwaus.net"), { it }), "search-source").default("mangaread.org")
    val scrape by parser.option(ArgType.Boolean, "scrape").default(false)
    val refreshCache by parser.option(ArgType.Boolean, "refresh-cache").default(false)
    val updateScrapeCache by parser.option(ArgType.Boolean, "update-scrape-cache").default(false)
    val downloadAll by parser.option(ArgType.Boolean, "download-all").default(false)
    val cleanCache by parser.option(ArgType.Boolean, "clean-cache").default(false)
    val deleteCache by parser.option(ArgType.Boolean, "delete-cache").default(false)
    val ignoreCache by parser.option(ArgType.Boolean, "ignore-cache").default(false)
    val keep by parser.option(ArgType.String, "keep").multiple()
    val remove by parser.option(ArgType.String, "remove").multiple()
    val skipExisting by parser.option(ArgType.Boolean, "skip-existing").default(false)
    val update by parser.option(ArgType.Boolean, "update").default(false)
    val updateMetadata by parser.option(ArgType.Boolean, "update-metadata").default(false)
    val format by parser.option(ArgType.Choice(listOf("epub"), { it }), "format").default("epub")
    val title by parser.option(ArgType.String, "title", "t")
    val outputPath by parser.option(ArgType.String, "output", "o").default("")
    val force by parser.option(ArgType.Boolean, "force", "f").default(false)
    val deleteOriginal by parser.option(ArgType.Boolean, "delete-original").default(false)
    val debug by parser.option(ArgType.Boolean, "debug").default(false)
    val dryRun by parser.option(ArgType.Boolean, "dry-run").default(false)
    val exclude by parser.option(ArgType.String, "exclude", "e").multiple()
    val workers by parser.option(ArgType.Int, "workers", "w").default(4)
    val userAgentName by parser.option(ArgType.String, "user-agent", "ua").default("Chrome (Windows)")
    val perWorkerUserAgent by parser.option(ArgType.Boolean, "per-worker-ua").default(false)
    val batchWorkers by parser.option(ArgType.Int, "batch-workers", "bw").default(1)
    val optimize by parser.option(ArgType.Boolean, "optimize").default(false)
    val maxWidth by parser.option(ArgType.Int, "max-image-width")
    val jpegQuality by parser.option(ArgType.Int, "jpeg-quality")
    val sortBy by parser.option(ArgType.String, "sort-by").default("default")
    val cacheDir by parser.option(ArgType.String, "cache-dir")
    val listUserAgents by parser.option(ArgType.Boolean, "list-user-agents", "list-uas").default(false)
    val listSortOptions by parser.option(ArgType.Boolean, "list-sort-options").default(false)
    val version by parser.option(ArgType.Boolean, "version", "v").default(false)
    val preset by parser.option(ArgType.String, "preset")
    val proxy by parser.option(ArgType.String, "proxy")
    val proxyType by parser.option(ArgType.Choice(listOf("http", "socks5"), { it }), "proxy-type")
    val proxyHost by parser.option(ArgType.String, "proxy-host")
    val proxyPort by parser.option(ArgType.Int, "proxy-port")
    val proxyUser by parser.option(ArgType.String, "proxy-user")
    val proxyPass by parser.option(ArgType.String, "proxy-pass")
    val checkIp by parser.option(ArgType.Boolean, "check-ip").default(false)
    val ipLookupUrl by parser.option(ArgType.String, "ip-lookup-url", "ilu")
    val allowNsfw by parser.option(ArgType.Boolean, "allow-nsfw").default(false)

    try {
        parser.parse(args)
    } catch (e: Exception) {
        println("Error: ${e.message}\nTry 'manga-combiner-cli --help' for more information."); return
    }

    if (version) {
        println("manga-combiner-cli v${AppVersion.NAME}"); return
    }
    if (listUserAgents) {
        println("Available user-agent profiles:");
        (listOf("Random") + UserAgent.browsers.keys.toList()).forEach { println("- $it") }; return
    }
    if (listSortOptions) {
        println("Available sort options for --sort-by:");
        listOf("default", "chapters-asc", "chapters-desc", "alpha-asc", "alpha-desc").forEach { println("- $it") }; return
    }

    if (source.isEmpty() && !deleteCache && !checkIp && !updateScrapeCache) {
        println("Error: At least one --source must be provided, or use a standalone command like --delete-cache, --check-ip, or --update-scrape-cache.\nTry 'manga-combiner-cli --help' for usage examples."); return
    }
    if (scrape && search) {
        println("Error: --scrape and --search are mutually exclusive.\nTry 'manga-combiner-cli --help' for usage examples."); return
    }
    if (downloadAll && !search) {
        println("Error: --download-all requires --search.\nTry 'manga-combiner-cli --help' for usage examples."); return
    }
    if (listOf(force, skipExisting, update, updateMetadata).count { it } > 1) {
        println("Error: --force, --skip-existing, --update, and --update-metadata are mutually exclusive.\nTry 'manga-combiner-cli --help' for more information."); return
    }
    if (keep.isNotEmpty() && remove.isNotEmpty()) {
        println("Error: --keep and --remove are mutually exclusive.\nTry 'manga-combiner-cli --help' for more information."); return
    }

    var cliArgs = CliArguments(
        source, search, searchSource, scrape, refreshCache, updateScrapeCache, downloadAll, cleanCache, deleteCache,
        ignoreCache, keep, remove, skipExisting, update, updateMetadata, format, title,
        outputPath, force, deleteOriginal, debug, dryRun, exclude, workers,
        userAgentName, proxy, proxyType, proxyHost, proxyPort, proxyUser, proxyPass,
        checkIp, ipLookupUrl, perWorkerUserAgent, batchWorkers, optimize,
        maxWidth ?: if (optimize) 1200 else null,
        jpegQuality ?: if (optimize) 85 else null,
        sortBy, cacheDir, allowNsfw
    )
    preset?.let { cliArgs = applyPreset(it, cliArgs) }
    Logger.isDebugEnabled = cliArgs.debug
    stopKoin()
    startKoin {
        modules(appModule, module {
            factory<PlatformProvider> { DesktopPlatformProvider(cliArgs.cacheDir) }
            single { SettingsRepository() }
            factory { FileMover() }
        })
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
            Logger.logInfo("No cache items match the criteria for deletion."); return
        }
        val totalSize = seriesToDelete.sumOf { it.totalSizeInBytes }
        Logger.logInfo("The following items will be deleted (Total: ${formatSize(totalSize)}):")
        seriesToDelete.forEach { println("- ${it.seriesName} (${it.totalSizeFormatted})") }
        if (cliArgs.dryRun) {
            Logger.logInfo("\nDRY RUN: Aborting before deletion."); return
        }
        cacheService.deleteCacheItems(seriesToDelete.map { it.path })
        return
    }

    runBlocking {
        if (cliArgs.updateScrapeCache) {
            val client = createHttpClient(buildProxyUrlFromCliArgs(cliArgs))
            try {
                val urlsToUpdate = if (cliArgs.source.isNotEmpty()) {
                    cliArgs.source
                } else {
                    val allCachedSites = scrapeCacheService.loadCache()?.websites ?: emptyMap()
                    // Reconstruct default URLs from cached hostnames if no source is provided
                    allCachedSites.keys.map { hostname -> "https://$hostname/manga/" }
                }

                if (urlsToUpdate.isEmpty()) {
                    Logger.logInfo("No sites specified or found in cache to update.")
                    return@runBlocking
                }

                Logger.logInfo("--- Updating scrape cache for: ${urlsToUpdate.joinToString(", ")} ---")

                urlsToUpdate.map { startUrl ->
                    async {
                        val hostname = try { URI(startUrl).host.replace("www.", "") } catch (e: Exception) { null }
                        if (hostname == null) {
                            Logger.logError("Invalid URL provided for cache update: $startUrl")
                        } else {
                            Logger.logInfo("Scraping: $startUrl")
                            val results = scraperService.findAllSeriesUrls(client, startUrl, UserAgent.browsers[cliArgs.userAgentName] ?: "", cliArgs.allowNsfw)
                            val processedSeries = results.map { ScrapedSeries(it.title, it.url, it.chapterCount) }
                            scrapeCacheService.saveCacheForHost(hostname, ScrapedWebsiteCache(System.currentTimeMillis(), processedSeries))
                        }
                    }
                }.awaitAll()

                Logger.logInfo("--- Scrape cache update complete. ---")
            } finally {
                client.close()
            }
            return@runBlocking
        }

        if (cliArgs.checkIp) {
            val finalProxyUrl = buildProxyUrlFromCliArgs(cliArgs)
            val lookupServiceUrl = cliArgs.ipLookupUrl ?: AppSettings.Defaults.IP_LOOKUP_URL

            if (finalProxyUrl != null) {
                Logger.logInfo("Running comprehensive proxy test...")
                val testResult = ProxyTestUtility.runComprehensiveProxyTest(finalProxyUrl, lookupServiceUrl)

                if (testResult.success) {
                    println("✅ COMPREHENSIVE PROXY TEST PASSED")
                    println("📍 Direct Connection:")
                    println("   IP: ${testResult.directIp}")
                    println("   Location: ${testResult.directLocation}")
                    println("📍 Proxy Connection:")
                    println("   IP: ${testResult.proxyIp}")
                    println("   Location: ${testResult.proxyLocation}")
                    println("🔒 Security:")
                    println("   IP Changed: ${if (testResult.ipChanged) "✅ Yes" else "❌ No"}")
                    println("   Kill Switch: ${if (testResult.killSwitchWorking) "✅ Active" else "❌ Failed"}")

                    if (!testResult.killSwitchWorking) {
                        println("\n⚠️  WARNING: Kill switch not working! Traffic may leak through direct connection.")
                        println("   This is a security risk - your real IP could be exposed if proxy fails.")
                    }

                } else {
                    println("❌ PROXY TEST FAILED")
                    testResult.error?.let { println("Error: $it") }
                    println("\nProblems detected:")
                    if (!testResult.ipChanged) {
                        println("   - IP address did not change (proxy may not be working)")
                    }
                    if (!testResult.killSwitchWorking) {
                        println("   - Kill switch not working (traffic may leak on proxy failure)")
                    }
                }

            } else {
                Logger.logInfo("Checking public IP address without proxy...")
                val client = createHttpClient(null)
                try {
                    val response = client.get(lookupServiceUrl)
                    if (response.status.isSuccess()) {
                        val ipInfo = response.body<IpInfo>()
                        println("✅ Direct Connection IP Check:")
                        println("   IP: ${ipInfo.ip ?: "N/A"}")
                        println("   Location: ${listOfNotNull(ipInfo.city, ipInfo.region, ipInfo.country).joinToString(", ")}")
                        println("   ISP: ${ipInfo.org ?: "N/A"}")
                    } else {
                        Logger.logError("Failed: Server responded with status ${response.status}")
                    }
                } catch (e: Exception) {
                    Logger.logError("IP Check failed.", e)
                } finally {
                    client.close()
                }
            }
            return@runBlocking
        }

        // Expand any glob patterns in the source arguments first.
        val expandedSources = cliArgs.source.flatMap { source ->
            if (!source.startsWith("http") && source.any { it in setOf('*', '?', '[', '{') }) {
                FileUtils.expandGlobPath(source).map { it.absolutePath }
            } else {
                listOf(source)
            }
        }.distinct()

        if (cliArgs.updateMetadata) {
            val (epubFiles, potentialUrls) = expandedSources.partition {
                it.endsWith(".epub", ignoreCase = true) && File(it).exists()
            }
            val sourceUrlOverride = potentialUrls.lastOrNull { it.startsWith("http") }

            if (epubFiles.isEmpty()) {
                Logger.logError("No valid .epub files found for metadata update.")
                return@runBlocking
            }

            Logger.logInfo("--- Updating metadata for ${epubFiles.size} EPUB file(s) ---")
            if (sourceUrlOverride != null) {
                Logger.logInfo("Using override source URL for all files: $sourceUrlOverride")
            }

            for (epubPath in epubFiles) {
                processMetadataUpdate(epubPath, sourceUrlOverride, cliArgs, scraperService, processorService)
            }
            return@runBlocking
        }

        val (localFiles, urls) = expandedSources.partition { File(it).exists() }
        if (localFiles.isNotEmpty()) {
            Logger.logInfo("--- Processing ${localFiles.size} local file(s) ---")
            for (file in localFiles) {
                processLocalFile(file, cliArgs, fileConverter, processorService, platformProvider)
            }
        }

        val sourcesToDownload = when {
            cliArgs.scrape -> {
                val client = createHttpClient(buildProxyUrlFromCliArgs(cliArgs))
                try {
                    urls.map { startUrl ->
                        async {
                            val hostname = URI(startUrl).host.replace("www.", "")
                            val cachedData = if (!cliArgs.refreshCache) scrapeCacheService.loadCacheForHost(hostname) else null
                            val allSeries = if (cachedData != null) {
                                val ageInHours = TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - cachedData.lastUpdated)
                                Logger.logInfo("Using cached scrape data for $hostname from $ageInHours hours ago. Use --refresh-cache to force an update.")
                                cachedData.series.map { SearchResult(it.title, it.url, "", chapterCount = it.chapterCount) }
                            } else {
                                Logger.logInfo("Starting initial scrape from URL: $startUrl")
                                val results = scraperService.findAllSeriesUrls(client, startUrl, UserAgent.browsers[cliArgs.userAgentName] ?: "", cliArgs.allowNsfw)
                                val processedSeries = results.map { ScrapedSeries(it.title, it.url, it.chapterCount) }
                                scrapeCacheService.saveCacheForHost(hostname, ScrapedWebsiteCache(System.currentTimeMillis(), processedSeries))
                                results
                            }
                            sortSeries(allSeries, cliArgs.sortBy).map { it.url }
                        }
                    }.awaitAll().flatten()
                } finally {
                    client.close()
                }
            }
            cliArgs.search -> {
                val query = urls.joinToString(" ")
                val client = createHttpClient(buildProxyUrlFromCliArgs(cliArgs))
                try {
                    val results = scraperService.search(client, query, UserAgent.browsers[cliArgs.userAgentName] ?: "", cliArgs.searchSource, cliArgs.allowNsfw)
                    if (results.isEmpty()) { Logger.logInfo("No results found."); emptyList() }
                    else if (cliArgs.downloadAll) {
                        results.map { it.url }
                    } else {
                        results.forEachIndexed { i, r -> println("[${i + 1}] ${r.title}\n   URL: ${r.url}\n") }; emptyList()
                    }
                } finally {
                    client.close()
                }
            }
            else -> urls
        }

        if (sourcesToDownload.isNotEmpty()) {
            runDownloadsAndPackaging(sourcesToDownload, cliArgs, downloadService, processorService, scraperService, platformProvider, cacheService)
        }
        Logger.logInfo("--- All operations complete. ---")
    }
}
