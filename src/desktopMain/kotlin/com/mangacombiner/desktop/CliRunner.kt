package com.mangacombiner.desktop

import com.mangacombiner.di.appModule
import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.FileConverter
import com.mangacombiner.service.LocalFileOptions
import com.mangacombiner.service.ProcessorService
import com.mangacombiner.service.ScraperService
import com.mangacombiner.util.FileUtils
import com.mangacombiner.util.Logger
import com.mangacombiner.util.PlatformProvider
import com.mangacombiner.util.UserAgent
import com.mangacombiner.util.createHttpClient
import com.mangacombiner.util.logOperationSettings
import com.mangacombiner.util.naturalSortComparator
import com.mangacombiner.util.titlecase
import com.mangacombiner.util.toSlug
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get
import java.io.File
import kotlin.random.Random

fun main(args: Array<String>) {
    startKoin {
        modules(appModule)
    }

    val parser = ArgParser("manga-combiner-cli")
    val source by parser.argument(ArgType.String, "source", "Source URL or local file path")
    val format by parser.option(ArgType.String, "format", description = "Output format ('cbz' or 'epub')").default("epub")
    val title by parser.option(ArgType.String, "title", "t", "Custom output file title.")
    val outputPath by parser.option(ArgType.String, "output", "o", "Directory to save the final file.").default("")
    val force by parser.option(ArgType.Boolean, "force", "f", "Force overwrite of output file.").default(false)
    val deleteOriginal by parser.option(ArgType.Boolean, "delete-original", "Delete source on success.").default(false)
    val debug by parser.option(ArgType.Boolean, "debug", description = "Enable debug logging.").default(false)
    val dryRun by parser.option(ArgType.Boolean, "dry-run", description = "Simulate the operation without creating files.").default(false)
    val exclude by parser.option(ArgType.String, "exclude", "e", "Chapter URL slug to exclude.").multiple()
    val workers by parser.option(ArgType.Int, "workers", "w", "Number of concurrent download workers.").default(4)
    val userAgentName by parser.option(
        ArgType.String,
        "user-agent",
        "ua",
        "Browser profile to impersonate."
    ).default("Chrome (Windows)")
    val perWorkerUserAgent by parser.option(
        ArgType.Boolean,
        "per-worker-ua",
        description = "Use a different random user agent for each worker."
    ).default(false)

    try {
        parser.parse(args)
    } catch (e: Exception) {
        println("Error parsing arguments: ${e.message}")
        return
    }

    val allowedUserAgents = UserAgent.browsers.keys + "Random"
    if (userAgentName !in allowedUserAgents) {
        println("Error: '$userAgentName' is not a valid user agent. Please choose from: ${allowedUserAgents.joinToString(", ")}")
        return
    }

    Logger.isDebugEnabled = debug

    val downloadService: DownloadService = get(DownloadService::class.java)
    val processorService: ProcessorService = get(ProcessorService::class.java)
    val scraperService: ScraperService = get(ScraperService::class.java)
    val platformProvider: PlatformProvider = get(PlatformProvider::class.java)
    val fileConverter: FileConverter = get(FileConverter::class.java)

    runBlocking {
        val defaultUserAgent = UserAgent.browsers["Chrome (Windows)"]!!
        val listClient = createHttpClient("")
        val tempDir = File(platformProvider.getTmpDir())

        try {
            when {
                source.startsWith("http", ignoreCase = true) -> {
                    Logger.logInfo("Fetching chapter list from URL...")
                    val listScraperAgent = UserAgent.browsers[userAgentName] ?: defaultUserAgent
                    var chapters = scraperService.findChapterUrlsAndTitles(listClient, source, listScraperAgent)
                    if (chapters.isEmpty()) {
                        Logger.logError("No chapters found at the provided URL. Aborting.")
                        return@runBlocking
                    }

                    if (exclude.isNotEmpty()) {
                        chapters = chapters.filter { (url, _) ->
                            url.trimEnd('/').substringAfterLast('/') !in exclude
                        }
                    }
                    val seriesSlug = source.toSlug()
                    val downloadDir = File(tempDir, "manga-dl-$seriesSlug").apply { mkdirs() }

                    val downloadOptions = DownloadOptions(
                        seriesUrl = source,
                        chaptersToDownload = chapters.toMap(),
                        cliTitle = title,
                        getWorkers = { workers },
                        exclude = exclude,
                        format = format,
                        tempDir = tempDir,
                        getUserAgents = {
                            when {
                                perWorkerUserAgent -> List(workers) { UserAgent.browsers.values.random(Random) }
                                userAgentName == "Random" -> listOf(UserAgent.browsers.values.random(Random))
                                else -> listOf(UserAgent.browsers[userAgentName] ?: defaultUserAgent)
                            }
                        },
                        outputPath = outputPath.ifBlank { platformProvider.getUserDownloadsDir() ?: "" },
                        isPaused = { false },
                        dryRun = dryRun,
                        onProgressUpdate = { progress, status ->
                            val percentage = (progress * 100).toInt()
                            val barWidth = 20
                            val doneWidth = (barWidth * progress).toInt()
                            val bar = "[${"#".repeat(doneWidth)}${"-".repeat(barWidth - doneWidth)}]"
                            print("\r$bar $percentage% - $status      ")
                        },
                        onChapterCompleted = {}
                    )

                    logOperationSettings(downloadOptions, chapters.size, userAgentName, perWorkerUserAgent)

                    if (dryRun) {
                        Logger.logInfo("\nDRY RUN: Would download ${chapters.size} chapters. No files will be created.")
                        return@runBlocking
                    }

                    val downloadResult = downloadService.downloadChapters(downloadOptions, downloadDir)
                    println() // New line after progress bar

                    if (downloadResult != null && downloadResult.successfulFolders.isNotEmpty()) {
                        val mangaTitle = title?.ifBlank { null } ?: source.toSlug().replace('-', ' ').titlecase()
                        val finalOutputPath = downloadOptions.outputPath
                        val finalFileName = "${FileUtils.sanitizeFilename(mangaTitle)}.$format"
                        val outputFile = File(finalOutputPath, finalFileName)

                        if (outputFile.exists() && !force) {
                            Logger.logError("Output file already exists: ${outputFile.absolutePath}. Use --force to overwrite.")
                            return@runBlocking
                        }

                        Logger.logInfo("Creating ${format.uppercase()} archive: ${outputFile.name}...")

                        if (format == "cbz") {
                            processorService.createCbzFromFolders(
                                mangaTitle,
                                downloadResult.successfulFolders,
                                outputFile,
                                source,
                                downloadResult.failedChapters
                            )
                        } else {
                            processorService.createEpubFromFolders(
                                mangaTitle,
                                downloadResult.successfulFolders,
                                outputFile,
                                source,
                                downloadResult.failedChapters
                            )
                        }

                        if (outputFile.exists() && outputFile.length() > 0) {
                            Logger.logInfo("Successfully created: ${outputFile.absolutePath}")

                            if (downloadResult.failedChapters.isNotEmpty()) {
                                Logger.logInfo("Note: ${downloadResult.failedChapters.size} chapters had download failures and are embedded in the archive metadata.")
                            }
                        } else {
                            Logger.logError("Failed to create output file.")
                        }
                    } else {
                        Logger.logError("Download failed or no chapters were successfully downloaded.")
                    }
                }

                File(source).exists() -> {
                    val inputFile = File(source)
                    val mangaTitle = title?.ifBlank { null } ?: inputFile.nameWithoutExtension
                    val finalOutputPath = outputPath.ifBlank { inputFile.parent }
                    val finalFileName = "${FileUtils.sanitizeFilename(mangaTitle)}.$format"
                    val outputFile = File(finalOutputPath, finalFileName)

                    if (outputFile.exists() && !force) {
                        Logger.logError("Output file already exists: ${outputFile.absolutePath}. Use --force to overwrite.")
                        return@runBlocking
                    }

                    if (dryRun) {
                        Logger.logInfo("DRY RUN: Would process ${inputFile.name} -> ${outputFile.name}")
                        return@runBlocking
                    }

                    Logger.logInfo("Processing local file: ${inputFile.name}")

                    val localFileOptions = LocalFileOptions(
                        inputFile = inputFile,
                        customTitle = mangaTitle,
                        outputFormat = format,
                        forceOverwrite = force,
                        deleteOriginal = deleteOriginal,
                        useStreamingConversion = false,
                        useTrueStreaming = false,
                        useTrueDangerousMode = false,
                        skipIfTargetExists = !force,
                        tempDirectory = platformProvider.getTmpDir(),
                        dryRun = dryRun
                    )

                    val result = fileConverter.process(localFileOptions, mangaTitle, outputFile, processorService)

                    if (result.success) {
                        Logger.logInfo("Successfully processed: ${result.outputFile?.absolutePath}")
                        if (deleteOriginal && inputFile.delete()) {
                            Logger.logInfo("Deleted original file: ${inputFile.name}")
                        }
                    } else {
                        Logger.logError("Processing failed: ${result.error}")
                    }
                }

                else -> Logger.logError("Source is not a valid URL or existing file path.")
            }
        } catch (e: Exception) {
            Logger.logError("A fatal error occurred", e)
        } finally {
            listClient.close()
        }
    }
}
