package com.mangacombiner.runner

import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.LocalFileOptions
import com.mangacombiner.service.ProcessorService
import com.mangacombiner.service.SyncOptions
import com.mangacombiner.util.expandGlobPath
import com.mangacombiner.util.isDebugEnabled
import com.mangacombiner.util.logDebug
import com.mangacombiner.util.logError
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
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

private data class JobOptions(
    val source: String,
    val format: String,
    val tempDirectory: File,
    val storageMode: String,
    val userAgent: String,
    val workers: Int,
    val batchWorkers: Int,
    val force: Boolean,
    val deleteOriginal: Boolean,
    val skip: Boolean,
    val debug: Boolean
)

private class CliArgs(parser: ArgParser) {
    val source by parser.argument(ArgType.String, "source", "Source URL, local file, or glob pattern.")
    val update by parser.option(ArgType.String, "update", description = "Path to local CBZ to update.")
    val exclude by parser.option(ArgType.String, "exclude", "e", "Chapter URL slug to exclude.").multiple()
    val workers by parser.option(ArgType.Int, "workers", "w", "Concurrent image download threads.")
        .default(DEFAULT_IMAGE_WORKERS)
    val title by parser.option(ArgType.String, "title", "t", "Custom output file title.")
    val format by parser.option(ArgType.String, "format", description = "Output format ('cbz' or 'epub').")
        .default(DEFAULT_FORMAT)
    val force by parser.option(ArgType.Boolean, "force", "f", "Force overwrite of output file.")
        .default(false)
    val deleteOriginal by parser.option(ArgType.Boolean, "delete-original", "Delete source on success.")
        .default(false)
    val lowStorageMode by parser.option(ArgType.Boolean, "low-storage-mode", "Low RAM usage mode.")
        .default(false)
    val ultraLowStorageMode by parser.option(ArgType.Boolean, "ultra-low-storage-mode", "Aggressive low memory.")
        .default(false)
    val trueDangerousMode by parser.option(ArgType.Boolean, "true-dangerous-mode", "DANGER! Modifies source.")
        .default(false)
    val batchWorkers by parser.option(ArgType.Int, "batch-workers", "Concurrent local file processing.")
        .default(DEFAULT_BATCH_WORKERS)
    val debug by parser.option(ArgType.Boolean, "debug", description = "Enable debug logging.")
        .default(false)
    val skipIfTargetExists by parser.option(ArgType.Boolean, "skip-if-target-exists", "Skip if target exists.")
        .default(false)
    val updateMissing by parser.option(ArgType.Boolean, "update-missing", "Thoroughly check chapters.")
        .default(false)
    val tempIsDestination by parser.option(ArgType.Boolean, "temp-is-destination", "Use output dir for temp.")
        .default(false)
    val impersonateBrowser by parser.option(ArgType.Boolean, "impersonate-browser", "Use browser User-Agent.")
        .default(false)
    val generateInfoPage by parser.option(
        ArgType.Boolean,
        "generate-info-page",
        "g",
        "Generate an informational first page."
    )
        .default(false)

    companion object {
        const val DEFAULT_IMAGE_WORKERS = 2
        const val DEFAULT_BATCH_WORKERS = 4
        const val DEFAULT_FORMAT = "cbz"
    }
}

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
            logDebug { "Caught expected exception for unsupported format: ${e.message}" }
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
            debug = isDebugEnabled
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
            logError("A network error occurred: ${e.response.status}", e)
        } catch (e: IOException) {
            logError("A file system error occurred", e)
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
                    client = client
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
                    generateInfoPage = cliArgs.generateInfoPage
                )
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun handleGlobSource(jobOptions: JobOptions, cliArgs: CliArgs) {
        val files = expandGlobPath(jobOptions.source)
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
            generateInfoPage = cliArgs.generateInfoPage
        )
    }

    @Suppress("TooGenericExceptionCaught")
    override fun run(vararg args: String?) {
        val parser = ArgParser(programName = "MangaCombiner")
        val cliArgs = CliArgs(parser)

        try {
            parser.parse(args.filterNotNull().toTypedArray())
        } catch (e: Exception) {
            // FIX: Catch a generic Exception and check the type to work around the resolution issue.
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

        isDebugEnabled = cliArgs.debug || "true".equals(System.getenv("DEBUG"), ignoreCase = true)
        val jobOptions = createJobOptions(cliArgs) ?: return

        printJobSummary(jobOptions)

        if (!handleOperationConfirmation(cliArgs)) {
            return
        }

        try {
            executeJob(jobOptions, cliArgs)
        } catch (e: Exception) {
            logError("A fatal, unexpected error occurred", e)
        }
    }
}
