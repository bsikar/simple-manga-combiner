package com.mangacombiner.desktop

import com.mangacombiner.di.appModule
import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.LocalFileOptions
import com.mangacombiner.service.ProcessorService
import com.mangacombiner.service.ScraperService
import com.mangacombiner.util.Logger
import com.mangacombiner.util.UserAgent
import com.mangacombiner.util.createHttpClient
import com.mangacombiner.util.getTmpDir
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get
import java.io.File

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


    try {
        parser.parse(args)
    } catch (e: Exception) {
        println("Error parsing arguments: ${e.message}")
        return
    }

    Logger.isDebugEnabled = debug

    val downloadService: DownloadService = get(DownloadService::class.java)
    val processorService: ProcessorService = get(ProcessorService::class.java)
    val scraperService: ScraperService = get(ScraperService::class.java)

    runBlocking {
        // For CLI, we use a single client with a default user agent for fetching the initial chapter list
        val defaultUserAgent = UserAgent.browsers["Chrome (Windows)"]!!
        val listClient = createHttpClient(defaultUserAgent)
        val tempDir = File(getTmpDir())
        try {
            when {
                source.startsWith("http", ignoreCase = true) -> {
                    Logger.logInfo("Fetching chapter list from URL...")
                    var chapters = scraperService.findChapterUrlsAndTitles(listClient, source)
                    if (chapters.isEmpty()) {
                        Logger.logError("No chapters found at the provided URL. Aborting.")
                        return@runBlocking
                    }

                    if (exclude.isNotEmpty()) {
                        chapters = chapters.filter { (url, _) ->
                            url.trimEnd('/').substringAfterLast('/') !in exclude
                        }
                    }

                    downloadService.downloadNewSeries(
                        DownloadOptions(
                            seriesUrl = source,
                            chaptersToDownload = chapters.toMap(),
                            cliTitle = title,
                            imageWorkers = workers,
                            exclude = exclude,
                            format = format,
                            tempDir = tempDir,
                            userAgents = listOf(defaultUserAgent), // CLI uses a single agent
                            outputPath = outputPath,
                            dryRun = dryRun
                        )
                    )
                }
                File(source).exists() -> {
                    processorService.processLocalFile(
                        LocalFileOptions(
                            inputFile = File(source),
                            customTitle = title,
                            outputFormat = format,
                            forceOverwrite = force,
                            deleteOriginal = deleteOriginal,
                            useStreamingConversion = false,
                            useTrueStreaming = false,
                            useTrueDangerousMode = false,
                            skipIfTargetExists = !force,
                            tempDirectory = getTmpDir(),
                            dryRun = dryRun
                        )
                    )
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
