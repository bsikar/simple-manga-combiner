package com.mangacombiner.desktop

import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.di.appModule
import com.mangacombiner.model.AppSettings
import com.mangacombiner.model.IpInfo
import com.mangacombiner.model.ScrapedSeries
import com.mangacombiner.model.ScrapedSeriesCache
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
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private data class DownloadResultForPackaging(
    val mangaTitle: String,
    val downloadDir: File,
    val seriesSlug: String,
    val outputFile: File,
    val sourceUrl: String,
    val failedChapters: Map<String, List<String>>?,
    val allFoldersForPackaging: List<File>
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
        seriesMetadata = null,
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

private suspend fun downloadSeriesToCache(
    source: String,
    cliArgs: CliArguments,
    downloadService: DownloadService,
    scraperService: ScraperService,
    platformProvider: PlatformProvider,
    cacheService: CacheService
): DownloadResultForPackaging? {
    val tempDir = File(platformProvider.getTmpDir())
    val finalProxyUrl = buildProxyUrlFromCliArgs(cliArgs)
    val listClient = createHttpClient(finalProxyUrl)

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
        logOperationSettings(downloadOptions, chaptersToDownload.size, cliArgs.userAgentName, cliArgs.perWorkerUserAgent, proxy = finalProxyUrl, cacheCount = allOnlineChapters.size - chaptersToDownload.size, optimizeMode = cliArgs.optimize, cleanCache = cliArgs.cleanCache, skipExisting = cliArgs.skipExisting, updateExisting = cliArgs.update, force = cliArgs.force, maxWidth = cliArgs.maxWidth, jpegQuality = cliArgs.jpegQuality)

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
        val (seriesMetadata, _) = scraperService.fetchSeriesDetails(client, sourceUrl, userAgent)
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
        Logger.logInfo("No source URL provided, attempting to extract from EPUB metadata...")
        val extractedUrl = ZipUtils.getSourceUrlFromEpub(epubFile)
        if (extractedUrl == null) {
            Logger.logError("Could not extract source URL from EPUB metadata. Please provide a source URL with another --source flag.")
            return
        }
        extractedUrl
    }

    updateEpubMetadata(epubFile, sourceUrl, cliArgs, scraperService, processorService)
}

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

  # Update metadata of an existing EPUB file
  manga-combiner-cli --source my-manga.epub --update-metadata --source https://example.com/manga/series-url

  # Use a SOCKS5 proxy with authentication
  manga-combiner-cli --proxy-type socks5 --proxy-host 127.0.0.1 --proxy-port 1080 --proxy-user "user" --proxy-pass "pass" --source ...

  # Use a simple HTTP proxy via the combined URL format
  manga-combiner-cli --proxy http://127.0.0.1:8080 --source ...
 
  # Simple update (uses source URL from EPUB metadata)
  manga-combiner-cli --source my-series.epub --update-metadata
 
  # Override source URL (uses provided URL instead of EPUB metadata)
  manga-combiner-cli --source my-series.epub --source https://example.com/manga/series --update-metadata

INPUT OPTIONS:
  -s, --source <URL|FILE|QUERY>    Source URL, local EPUB file, or search query (can be used multiple times)

DISCOVERY & SEARCH:
  --search                         Search for manga by name and display results
  --scrape                         Batch download all series from a list/genre page URL
  --download-all                   Download all search results (use with --search)

OUTPUT OPTIONS:
  --format <epub>                  Output format (default: epub)
  -t, --title <NAME>               Custom title for output file
  -o, --output <DIR>               Output directory (default: Downloads)

DOWNLOAD BEHAVIOR:
  -f, --force                      Force overwrite existing files
  --skip-existing                  Skip if output file exists (good for batch)
  --update                         Update an existing EPUB with new chapters
  --update-metadata                Update metadata of existing EPUB using scraped series data
  -e, --exclude <SLUG>             Exclude chapters by slug (e.g., 'chapter-4.5'). Can be used multiple times.
  --delete-original                Delete source file after successful conversion (local files only)

CACHE MANAGEMENT:
  --ignore-cache                   Force re-download all chapters, ignoring cached files
  --clean-cache                    Delete temp files after successful download to save disk space
  --refresh-cache                  Force refresh of scraped series list. Use with --scrape.
  --delete-cache                   Delete cached downloads and exit. Use with --keep or --remove for selective deletion.
  --keep <PATTERN>                 Keep matching series when deleting cache
  --remove <PATTERN>               Remove matching series when deleting cache
  --cache-dir <DIR>                Custom cache directory

NETWORK & PROXY:
  --proxy <URL>                    Legacy proxy URL (e.g., socks5://user:pass@host:1080)
  --proxy-type <http|socks5>       Proxy type. Use with --proxy-host and --proxy-port.
  --proxy-host <HOST>              Proxy host or IP address.
  --proxy-port <PORT>              Proxy port number.
  --proxy-user <USER>              Proxy username (optional).
  --proxy-pass <PASS>              Proxy password (optional).
  -ua, --user-agent <NAME>         Browser to impersonate (see --list-user-agents)
  --per-worker-ua                  Use a different random user agent for each download worker.
  --ip-lookup-url <URL>            Custom URL for IP lookup (e.g., http://ip-api.com/json)

PERFORMANCE:
  -w, --workers <N>                Concurrent image downloads per series (default: 4)
  -bw, --batch-workers <N>         Concurrent series downloads (default: 1)

UTILITY:
  --check-ip                       Check public IP address through the configured proxy and exit.
  --dry-run                        Preview actions without downloading
  --debug                          Enable verbose logging
  --list-user-agents               Show available user agents
  --list-sort-options              Show available sort methods
  -v, --version                    Show version information and exit
  --help                           Show this help message
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
    val scrape by parser.option(ArgType.Boolean, "scrape").default(false)
    val refreshCache by parser.option(ArgType.Boolean, "refresh-cache").default(false)
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
    val redownloadExisting by parser.option(ArgType.Boolean, "redownload-existing").default(false)
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

    if (source.isEmpty() && !deleteCache && !checkIp) {
        println("Error: At least one --source must be provided, or use a standalone command like --delete-cache or --check-ip.\nTry 'manga-combiner-cli --help' for usage examples."); return
    }
    if (scrape && search) {
        println("Error: --scrape and --search are mutually exclusive.\nTry 'manga-combiner-cli --help' for usage examples."); return
    }
    if (downloadAll && !search) {
        println("Error: --download-all requires --search.\nTry 'manga-combiner-cli --help' for usage examples."); return
    }
    if (listOf(force || redownloadExisting, skipExisting, update, updateMetadata).count { it } > 1) {
        println("Error: --force/--redownload-existing, --skip-existing, --update, and --update-metadata are mutually exclusive.\nTry 'manga-combiner-cli --help' for more information."); return
    }
    if (keep.isNotEmpty() && remove.isNotEmpty()) {
        println("Error: --keep and --remove are mutually exclusive.\nTry 'manga-combiner-cli --help' for more information."); return
    }

    var cliArgs = CliArguments(
        source, search, scrape, refreshCache, downloadAll, cleanCache, deleteCache,
        ignoreCache, keep, remove, skipExisting, update, updateMetadata, format, title,
        outputPath, force || redownloadExisting, deleteOriginal, debug, dryRun, exclude, workers,
        userAgentName, proxy, proxyType, proxyHost, proxyPort, proxyUser, proxyPass,
        checkIp, ipLookupUrl, perWorkerUserAgent, batchWorkers, optimize,
        maxWidth ?: if (optimize) 1200 else null,
        jpegQuality ?: if (optimize) 85 else null,
        sortBy, cacheDir
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
        if (cliArgs.checkIp) {
            val finalProxyUrl = buildProxyUrlFromCliArgs(cliArgs)
            val lookupServiceUrl = cliArgs.ipLookupUrl ?: AppSettings.Defaults.IP_LOOKUP_URL

            if (finalProxyUrl != null) {
                Logger.logInfo("Running comprehensive proxy test...")
                val testResult = ProxyTestUtility.runComprehensiveProxyTest(finalProxyUrl, lookupServiceUrl)

                if (testResult.success) {
                    println("✅ COMPREHENSIVE PROXY TEST PASSED")
                    println("📍 Direct Connection:")
                    println("    IP: ${testResult.directIp}")
                    println("    Location: ${testResult.directLocation}")
                    println("📍 Proxy Connection:")
                    println("    IP: ${testResult.proxyIp}")
                    println("    Location: ${testResult.proxyLocation}")
                    println("🔒 Security:")
                    println("    IP Changed: ${if (testResult.ipChanged) "✅ Yes" else "❌ No"}")
                    println("    Kill Switch: ${if (testResult.killSwitchWorking) "✅ Active" else "❌ Failed"}")

                    if (!testResult.killSwitchWorking) {
                        println("\n⚠️  WARNING: Kill switch not working! Traffic may leak through direct connection.")
                        println("    This is a security risk - your real IP could be exposed if proxy fails.")
                    }

                } else {
                    println("❌ PROXY TEST FAILED")
                    testResult.error?.let { println("Error: $it") }
                    println("\nProblems detected:")
                    if (!testResult.ipChanged) {
                        println("  - IP address did not change (proxy may not be working)")
                    }
                    if (!testResult.killSwitchWorking) {
                        println("  - Kill switch not working (traffic may leak on proxy failure)")
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
                        println("    IP: ${ipInfo.ip ?: "N/A"}")
                        println("    Location: ${listOfNotNull(ipInfo.city, ipInfo.region, ipInfo.country).joinToString(", ")}")
                        println("    ISP: ${ipInfo.org ?: "N/A"}")
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

        if (cliArgs.updateMetadata) {
            val sourceUrlOverride = if (cliArgs.source.size > 1) cliArgs.source[1] else null
            processMetadataUpdate(cliArgs.source[0], sourceUrlOverride, cliArgs, scraperService, processorService)
            return@runBlocking
        }

        val (localFiles, urls) = cliArgs.source.partition { File(it).exists() }
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
                    val startUrl = urls.firstOrNull() ?: run { Logger.logError("A source URL must be provided with --scrape."); return@runBlocking }
                    val cachedData = if (!cliArgs.refreshCache) scrapeCacheService.loadCache() else null
                    val allSeries = if (cachedData != null) {
                        val ageInHours = TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - cachedData.lastUpdated)
                        Logger.logInfo("Using cached scrape data from $ageInHours hours ago. Use --refresh-cache to force an update.")
                        cachedData.series.map { SearchResult(it.title, it.url, "", chapterCount = it.chapterCount) }
                    } else {
                        Logger.logInfo("Starting initial scrape from URL: $startUrl")
                        val results = scraperService.findAllSeriesUrls(client, startUrl, UserAgent.browsers[cliArgs.userAgentName] ?: "")
                        Logger.logInfo("Found ${results.size} series. Fetching chapter counts for caching...")
                        val processedSeries = results.mapIndexed { index, seriesResult ->
                            Logger.logInfo("Processing [${index + 1}/${results.size}]: ${seriesResult.title}")
                            val chapters = scraperService.findChapterUrlsAndTitles(client, seriesResult.url, UserAgent.browsers[cliArgs.userAgentName] ?: "")
                            ScrapedSeries(seriesResult.title, seriesResult.url, chapters.size)
                        }
                        scrapeCacheService.saveCache(ScrapedSeriesCache(System.currentTimeMillis(), processedSeries))
                        processedSeries.map { SearchResult(it.title, it.url, "", chapterCount = it.chapterCount) }
                    }
                    val sortedSeries = sortSeries(allSeries, cliArgs.sortBy)
                    sortedSeries.map { it.url }
                } finally {
                    client.close()
                }
            }
            cliArgs.search -> {
                val query = cliArgs.source.joinToString(" ")
                val client = createHttpClient(buildProxyUrlFromCliArgs(cliArgs))
                try {
                    val results = scraperService.search(client, query, UserAgent.browsers[cliArgs.userAgentName] ?: "")
                    if (results.isEmpty()) { Logger.logInfo("No results found."); emptyList() }
                    else if (cliArgs.downloadAll) {
                        results.map { it.url }
                    } else {
                        results.forEachIndexed { i, r -> println("[${i + 1}] ${r.title}\n    URL: ${r.url}\n") }; emptyList()
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
