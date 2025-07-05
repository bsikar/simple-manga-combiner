package com.mangacombiner.runner

import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.ProcessorService
import com.mangacombiner.util.expandGlobPath
import com.mangacombiner.util.isDebugEnabled
import com.mangacombiner.util.logError
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import kotlinx.cli.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import java.io.File
import kotlin.system.exitProcess

@Component
class MangaCombinerRunner(
    private val downloadService: DownloadService,
    private val processorService: ProcessorService
) : CommandLineRunner {

    private companion object {
        const val DEFAULT_IMAGE_WORKERS = 2
        const val DEFAULT_BATCH_WORKERS = 4
        const val DEFAULT_FORMAT = "cbz"
        val SUPPORTED_FORMATS = setOf("cbz", "epub")

        val BROWSER_USER_AGENTS = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:127.0) Gecko/20100101 Firefox/127.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"
        )
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

    private fun printJobSummary(
        source: String,
        format: String,
        tempDirectory: File,
        storageMode: String,
        userAgent: String,
        workers: Int,
        batchWorkers: Int,
        force: Boolean,
        deleteOriginal: Boolean,
        skip: Boolean,
        debug: Boolean
    ) {
        val line = "-".repeat(50)
        println(line)
        println("--- Manga Combiner Job Summary ---")
        println(line)
        println("Source: ".padEnd(25) + source)
        println("Output Format: ".padEnd(25) + format)
        println("User-Agent: ".padEnd(25) + userAgent)
        println()
        println("--- Options ---")
        println("Image Workers: ".padEnd(25) + workers)
        println("Batch Workers: ".padEnd(25) + batchWorkers)
        println("Force Overwrite: ".padEnd(25) + force)
        println("Delete Original: ".padEnd(25) + deleteOriginal)
        println("Skip if Target Exists: ".padEnd(25) + skip)
        println("Debug Mode: ".padEnd(25) + debug)
        println()
        println("--- Storage & Temp Files ---")
        println("Mode: ".padEnd(25) + storageMode)
        println("Temp Location: ".padEnd(25) + tempDirectory.absolutePath)
        println(line)
        println()
    }

    override fun run(vararg args: String?) {
        val parser = ArgParser(programName = "MangaCombiner")
        val source by parser.argument(ArgType.String, "source", "Source URL, local file (.cbz or .epub), or a glob pattern (e.g., \"*.cbz\").")

        // Options
        val update by parser.option(ArgType.String, "update", description = "Path to a local CBZ file to update with missing chapters from the source URL.")
        val exclude by parser.option(ArgType.String, "exclude", "e", "A chapter URL slug to exclude. Can be used multiple times.").multiple()
        val workers by parser.option(ArgType.Int, "workers", "w", "Number of concurrent image download threads per chapter.").default(DEFAULT_IMAGE_WORKERS)
        val title by parser.option(ArgType.String, "title", "t", "Set a custom title for the output file, overriding the source filename.")
        val format by parser.option(ArgType.String, "format", description = "The desired output format ('cbz' or 'epub').").default(DEFAULT_FORMAT)
        val force by parser.option(ArgType.Boolean, "force", "f", "Force overwrite of the output file if it already exists.").default(false)
        val deleteOriginal by parser.option(ArgType.Boolean, "delete-original", description = "Delete the original source file(s) after a successful conversion.").default(false)
        val lowStorageMode by parser.option(ArgType.Boolean, "low-storage-mode", description = "Uses less RAM during conversion at the cost of speed. Implies --delete-original.").default(false)
        val ultraLowStorageMode by parser.option(ArgType.Boolean, "ultra-low-storage-mode", description = "More aggressive streaming for very low memory. Implies --delete-original.").default(false)
        val trueDangerousMode by parser.option(ArgType.Boolean, "true-dangerous-mode", description = "DANGER! Modifies the source file directly during conversion. Any interruption will corrupt it.").default(false)
        val batchWorkers by parser.option(ArgType.Int, "batch-workers", description = "Number of local files to process concurrently when using a glob pattern.").default(DEFAULT_BATCH_WORKERS)
        val debug by parser.option(ArgType.Boolean, "debug", description = "Enable detailed debug logging for troubleshooting.").default(false)
        val skipIfTargetExists by parser.option(ArgType.Boolean, "skip-if-target-exists", description = "In batch mode, skip conversion if the target file (e.g., the .epub) already exists.").default(false)
        val updateMissing by parser.option(ArgType.Boolean, "update-missing", "Thoroughly check for incomplete chapters.").default(false)
        val tempIsDestination by parser.option(ArgType.Boolean, "temp-is-destination", "Use output directory for temp files instead of system temp.").default(false)
        val impersonateBrowser by parser.option(ArgType.Boolean, "impersonate-browser", description = "Use a random common browser User-Agent instead of the default Ktor agent.").default(false)

        try {
            parser.parse(args.filterNotNull().toTypedArray())
        } catch (e: Exception) {
            if (!args.contains("--help")) {
                println("Error parsing arguments: ${e.message}")
            }
            exitProcess(1)
        }

        isDebugEnabled = debug || "true".equals(System.getenv("DEBUG"), ignoreCase = true)

        val validatedFormat = try {
            validateFormat(format)
        } catch (e: Exception) {
            println(e.message); return
        }

        // Combine flags for processing
        val finalBatchWorkers = if (ultraLowStorageMode) 1 else batchWorkers
        val finalDeleteOriginal = deleteOriginal || lowStorageMode || ultraLowStorageMode || trueDangerousMode
        val useStreamingConversion = lowStorageMode || ultraLowStorageMode
        val useTrueStreaming = ultraLowStorageMode

        val storageModeDescription = when {
            trueDangerousMode -> "Dangerous (In-Place Modification)"
            useTrueStreaming -> "Ultra Low Storage (Streaming)"
            useStreamingConversion -> "Low Storage (Streaming)"
            else -> "Standard (Temp Directory)"
        }

        val systemTemp = File(System.getProperty("java.io.tmpdir"))
        val tempDirectory = if (tempIsDestination) File(".").apply { mkdirs() } else systemTemp

        // Determine User-Agent
        val finalUserAgent = if (impersonateBrowser) {
            BROWSER_USER_AGENTS.random()
        } else {
            "Ktor/2.3.12" // Default Ktor agent
        }

        // Print the job summary before starting any operations
        printJobSummary(source, validatedFormat, tempDirectory, storageModeDescription, finalUserAgent, workers, finalBatchWorkers, force, finalDeleteOriginal, skipIfTargetExists, isDebugEnabled)

        if (!handleOperationConfirmation(trueDangerousMode, finalDeleteOriginal, ultraLowStorageMode, lowStorageMode)) {
            return
        }

        // Create the HttpClient dynamically with the chosen User-Agent
        val client = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 60000
                connectTimeoutMillis = 60000
                socketTimeoutMillis = 60000
            }
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                exponentialDelay()
            }
            install(UserAgent) {
                agent = finalUserAgent
            }
        }

        try {
            runBlocking {
                val isUrlSource = source.startsWith("http", true)
                when {
                    isUrlSource && update != null -> {
                        downloadService.syncLocalSource(update!!, source, workers, exclude, updateMissing, tempDirectory, client)
                    }
                    isUrlSource -> {
                        downloadService.downloadNewSeries(source, title, workers, exclude, validatedFormat, tempDirectory, client)
                    }
                    "*" in source || "?" in source -> {
                        val filesToProcess = expandGlobPath(source)
                        if (filesToProcess.isEmpty()) {
                            println("No files found matching pattern '$source'.")
                        } else {
                            println("Found ${filesToProcess.size} files to process using up to $finalBatchWorkers parallel workers.")
                            val dispatcher = Dispatchers.IO.limitedParallelism(finalBatchWorkers)
                            coroutineScope {
                                filesToProcess.forEach { file ->
                                    launch(dispatcher) {
                                        processorService.processLocalFile(
                                            file, title, validatedFormat, force, finalDeleteOriginal,
                                            useStreamingConversion, useTrueStreaming, trueDangerousMode,
                                            skipIfTargetExists, tempDirectory
                                        )
                                    }
                                }
                            }
                            println("\nBatch processing complete.")
                        }
                    }
                    File(source).exists() -> {
                        processorService.processLocalFile(
                            File(source), title, validatedFormat, force, finalDeleteOriginal,
                            useStreamingConversion, useTrueStreaming, trueDangerousMode,
                            skipIfTargetExists, tempDirectory
                        )
                    }
                    else -> {
                        println("Error: Source '$source' is not a valid URL or an existing file/pattern.")
                    }
                }
            }
        } catch (e: Exception) {
            println("A fatal error occurred.")
            logError(e.message ?: "Unknown error", e)
        } finally {
            client.close()
        }
    }
}
