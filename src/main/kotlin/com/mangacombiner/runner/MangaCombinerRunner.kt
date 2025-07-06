package com.mangacombiner.runner

import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.LocalFileOptions
import com.mangacombiner.service.ProcessorService
import com.mangacombiner.service.SyncOptions
import com.mangacombiner.util.FileUtils
import com.mangacombiner.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import kotlinx.cli.ArgParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

@Component
class MangaCombinerRunner(
    private val downloadService: DownloadService,
    private val processorService: ProcessorService
) : CommandLineRunner {

    private companion object {
        const val LINE_WIDTH = 50
        const val PADDING_WIDTH = 25
        const val REQUEST_TIMEOUT_MS = 60000L
        const val MAX_RETRIES = 3
        val SUPPORTED_FORMATS = setOf("cbz", "epub")
        val BROWSER_USER_AGENTS = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0",
        )
    }

    private fun getConfirmation(prompt: String): Boolean {
        print("$prompt (yes/no): ")
        return readlnOrNull()?.trim()?.equals("yes", ignoreCase = true) ?: false
    }

    private fun handleOperationConfirmation(cliArgs: CliArgs): Boolean {
        if (cliArgs.dryRun) {
            println("--- DRY RUN MODE: No confirmation needed. ---")
            return true
        }

        var confirmed = true
        if (cliArgs.trueDangerousMode) {
            println("---")
            println("--- EXTREME DANGER: '--true-dangerous-mode' is enabled! ---")
            println("--- This mode modifies the source file directly during conversion.")
            println("--- ANY INTERRUPTION WILL LIKELY CORRUPT THE ORIGINAL FILE.")
            println("---")
            if (!getConfirmation("You have been warned. Do you wish to continue?")) {
                println("Operation cancelled.")
                confirmed = false
            }
        } else if (cliArgs.deleteOriginal || cliArgs.lowStorageMode || cliArgs.ultraLowStorageMode) {
            val mode = when {
                cliArgs.ultraLowStorageMode -> "ultra-low-storage"
                cliArgs.lowStorageMode -> "low-storage"
                else -> "delete-original"
            }
            println("---")
            println("--- WARNING: '$mode' mode is enabled. ---")
            println("--- The original file will be deleted only after a successful conversion.")
            println("---")
            if (!getConfirmation("Are you sure you want to delete the original file upon success?")) {
                println("Operation cancelled.")
                confirmed = false
            }
        }
        return confirmed
    }

    private fun printJobSummary(options: JobOptions) {
        val line = "-".repeat(LINE_WIDTH)
        println(line)
        println("--- Manga Combiner Job Summary ---")
        println(line)
        println("Source: ".padEnd(PADDING_WIDTH) + options.source)
        println("Output Format: ".padEnd(PADDING_WIDTH) + options.format)
        println("User-Agent: ".padEnd(PADDING_WIDTH) + options.userAgent)
        println()
        println("--- Options ---")
        println("Dry Run: ".padEnd(PADDING_WIDTH) + options.dryRun)
        println("Image Workers: ".padEnd(PADDING_WIDTH) + options.workers)
        println("Batch Workers: ".padEnd(PADDING_WIDTH) + options.batchWorkers)
        println("Force Overwrite: ".padEnd(PADDING_WIDTH) + options.force)
        println("Delete Original: ".padEnd(PADDING_WIDTH) + options.deleteOriginal)
        println("Skip if Target Exists: ".padEnd(PADDING_WIDTH) + options.skip)
        println("Debug Mode: ".padEnd(PADDING_WIDTH) + options.debug)
        println()
        println("--- Storage & Temp Files ---")
        println("Mode: ".padEnd(PADDING_WIDTH) + options.storageMode)
        println("Temp Location: ".padEnd(PADDING_WIDTH) + options.tempDirectory.absolutePath)
        println(line)
        println()
    }

    private fun createJobOptions(cliArgs: CliArgs): JobOptions? {
        val format = try {
            val lowerFormat = cliArgs.format.lowercase()
            require(lowerFormat in SUPPORTED_FORMATS)
            lowerFormat
        } catch (e: IllegalArgumentException) {
            println("Unsupported format: ${cliArgs.format}. Supported: ${SUPPORTED_FORMATS.joinToString()}")
            Logger.logDebug { "Caught expected exception for unsupported format: ${e.message}" }
            return null
        }

        val useStreaming = cliArgs.lowStorageMode || cliArgs.ultraLowStorageMode
        return JobOptions(
            source = cliArgs.source,
            format = format,
            tempDirectory = if (cliArgs.tempIsDestination) File(".") else File(System.getProperty("java.io.tmpdir")),
            storageMode = when {
                cliArgs.trueDangerousMode -> "Dangerous (In-Place Modification)"
                cliArgs.ultraLowStorageMode -> "Ultra Low Storage (Streaming)"
                useStreaming -> "Low Storage (Streaming)"
                else -> "Standard (Temp Directory)"
            },
            userAgent = if (cliArgs.impersonateBrowser) BROWSER_USER_AGENTS.random() else "Ktor/2.3.12",
            workers = cliArgs.workers,
            batchWorkers = if (cliArgs.ultraLowStorageMode) 1 else cliArgs.batchWorkers,
            force = cliArgs.force,
            deleteOriginal = cliArgs.deleteOriginal || useStreaming || cliArgs.trueDangerousMode,
            skip = cliArgs.skipIfTargetExists,
            debug = Logger.isDebugEnabled,
            dryRun = cliArgs.dryRun
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun executeJob(jobOptions: JobOptions, cliArgs: CliArgs) {
        val client = HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = MAX_RETRIES)
                exponentialDelay()
            }
            install(UserAgent) { agent = jobOptions.userAgent }
        }

        try {
            runBlocking {
                when {
                    jobOptions.source.startsWith("http", true) -> handleUrlSource(jobOptions, client, cliArgs)
                    "*" in jobOptions.source || "?" in jobOptions.source -> handleGlobSource(jobOptions, cliArgs)
                    File(jobOptions.source).exists() -> handleLocalFileSource(jobOptions, cliArgs)
                    else -> println("Error: Source '${jobOptions.source}' is invalid.")
                }
            }
        } catch (e: ClientRequestException) {
            Logger.logError("A network error occurred: ${e.response.status}", e)
        } catch (e: IOException) {
            Logger.logError("A file system error occurred", e)
        } finally {
            client.close()
        }
    }

    private suspend fun handleUrlSource(jobOptions: JobOptions, client: HttpClient, cliArgs: CliArgs) {
        if (cliArgs.update != null) {
            downloadService.syncLocalSource(
                SyncOptions(
                    localPath = cliArgs.update!!,
                    seriesUrl = jobOptions.source,
                    imageWorkers = jobOptions.workers,
                    exclude = cliArgs.exclude,
                    checkPageCounts = cliArgs.updateMissing,
                    tempDir = jobOptions.tempDirectory,
                    client = client,
                    dryRun = jobOptions.dryRun
                )
            )
        } else {
            downloadService.downloadNewSeries(
                DownloadOptions(
                    seriesUrl = jobOptions.source,
                    cliTitle = cliArgs.title,
                    imageWorkers = jobOptions.workers,
                    exclude = cliArgs.exclude,
                    format = jobOptions.format,
                    tempDir = jobOptions.tempDirectory,
                    client = client,
                    dryRun = jobOptions.dryRun
                )
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun handleGlobSource(jobOptions: JobOptions, cliArgs: CliArgs) {
        val files = FileUtils.expandGlobPath(jobOptions.source)
        if (files.isEmpty()) {
            println("No files found matching pattern '${jobOptions.source}'.")
            return
        }

        println("Found ${files.size} files to process using up to ${jobOptions.batchWorkers} workers.")
        coroutineScope {
            files.forEach { file ->
                launch(Dispatchers.IO.limitedParallelism(jobOptions.batchWorkers)) {
                    processorService.processLocalFile(createLocalFileOptions(file, jobOptions, cliArgs))
                }
            }
        }
    }

    private fun handleLocalFileSource(jobOptions: JobOptions, cliArgs: CliArgs) {
        processorService.processLocalFile(createLocalFileOptions(File(jobOptions.source), jobOptions, cliArgs))
    }

    private fun createLocalFileOptions(file: File, jobOptions: JobOptions, cliArgs: CliArgs): LocalFileOptions {
        val lowStorage = cliArgs.lowStorageMode
        val ultraLowStorage = cliArgs.ultraLowStorageMode
        return LocalFileOptions(
            inputFile = file,
            customTitle = cliArgs.title,
            outputFormat = jobOptions.format,
            forceOverwrite = jobOptions.force,
            deleteOriginal = jobOptions.deleteOriginal,
            useStreamingConversion = lowStorage || ultraLowStorage,
            useTrueStreaming = ultraLowStorage,
            useTrueDangerousMode = cliArgs.trueDangerousMode,
            skipIfTargetExists = jobOptions.skip,
            tempDirectory = jobOptions.tempDirectory,
            dryRun = jobOptions.dryRun
        )
    }

    @Suppress("TooGenericExceptionCaught")
    override fun run(vararg args: String?) {
        val parser = ArgParser(programName = "MangaCombiner")
        val cliArgs = CliArgs(parser)

        try {
            parser.parse(args.filterNotNull().toTypedArray())
        } catch (e: Exception) {
            if (e::class.simpleName == "IllegalUsage") {
                if (!args.contains("--help")) {
                    println("Error parsing arguments: ${e.message}")
                }
                exitProcess(1)
            } else {
                // It's a different, unexpected exception, so rethrow it.
                throw e
            }
        }

        Logger.isDebugEnabled = cliArgs.debug || "true".equals(System.getenv("DEBUG"), ignoreCase = true)
        val jobOptions = createJobOptions(cliArgs) ?: return

        printJobSummary(jobOptions)

        if (jobOptions.dryRun) {
            println("--- DRY RUN ENABLED: No files will be created, modified, or deleted. ---")
            println()
        }

        if (!handleOperationConfirmation(cliArgs)) {
            return
        }

        try {
            executeJob(jobOptions, cliArgs)
        } catch (e: Exception) {
            Logger.logError("A fatal, unexpected error occurred", e)
        }
    }
}
