package com.mangacombiner.desktop

import com.mangacombiner.di.appModule
import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.LocalFileOptions
import com.mangacombiner.service.ProcessorService
import com.mangacombiner.util.Logger
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
    val force by parser.option(ArgType.Boolean, "force", "f", "Force overwrite of output file.").default(false)
    val deleteOriginal by parser.option(ArgType.Boolean, "delete-original", "Delete source on success.").default(false)
    val debug by parser.option(ArgType.Boolean, "debug", description = "Enable debug logging.").default(false)
    val exclude by parser.option(ArgType.String, "exclude", "e", "Chapter URL slug to exclude.").multiple()

    try {
        parser.parse(args)
    } catch (e: Exception) {
        println("Error parsing arguments: ${e.message}")
        return
    }

    Logger.isDebugEnabled = debug

    val downloadService: DownloadService = get(DownloadService::class.java)
    val processorService: ProcessorService = get(ProcessorService::class.java)

    runBlocking {
        val client = createHttpClient()
        val tempDir = File(getTmpDir())
        try {
            when {
                source.startsWith("http", ignoreCase = true) -> {
                    downloadService.downloadNewSeries(
                        DownloadOptions(
                            seriesUrl = source,
                            cliTitle = title,
                            imageWorkers = 2,
                            exclude = exclude,
                            format = format,
                            tempDir = tempDir,
                            client = client
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
                            tempDirectory = getTmpDir()
                        )
                    )
                }
                else -> Logger.logError("Source is not a valid URL or existing file path.")
            }
        } catch (e: Exception) {
            Logger.logError("A fatal error occurred", e)
        } finally {
            client.close()
        }
    }
}
