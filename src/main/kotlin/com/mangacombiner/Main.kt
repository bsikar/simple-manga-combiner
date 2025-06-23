// File: src/main/kotlin/com/mangacombiner/Main.kt
package com.mangacombiner

import com.mangacombiner.core.Processor.downloadAndCreate
import com.mangacombiner.core.Processor.processLocalFile
import com.mangacombiner.core.Processor.syncCbzWithSource
import com.mangacombiner.util.expandGlobPath
import com.mangacombiner.util.isDebugEnabled
import com.mangacombiner.util.logDebug
import kotlinx.cli.*
import kotlinx.coroutines.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.File

/**
 * Main entry point for the Manga Combiner application.
 * Handles command-line argument parsing and orchestrates the main operations.
 */
object MainKt {
    // Configuration constants
    private const val DEFAULT_WORKERS = 10
    private const val DEFAULT_CHAPTER_WORKERS = 4
    private const val DEFAULT_BATCH_WORKERS = 4
    private const val DEFAULT_FORMAT = "cbz"

    // Supported file formats
    private val SUPPORTED_FORMATS = setOf("cbz", "epub")

    /**
     * Extension function to convert a string to title case.
     * Capitalizes the first letter of each word.
     */
    private fun String.titlecase(): String = this.split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase() else char.toString()
        }
    }

    /**
     * Prompts the user for a yes/no confirmation.
     * @param prompt The question to ask the user
     * @return true if user responds with "yes" (case-insensitive), false otherwise
     */
    private fun getConfirmation(prompt: String): Boolean {
        print("$prompt (yes/no): ")
        return readlnOrNull()?.trim()?.equals("yes", ignoreCase = true) ?: false
    }

    /**
     * Validates the output format.
     * @param format The format string to validate
     * @return The validated format in lowercase
     * @throws IllegalArgumentException if format is not supported
     */
    private fun validateFormat(format: String): String {
        val lowerFormat = format.lowercase()
        require(lowerFormat in SUPPORTED_FORMATS) {
            "Unsupported format: $format. Supported formats are: ${SUPPORTED_FORMATS.joinToString()}"
        }
        return lowerFormat
    }

    /**
     * Handles user confirmation for dangerous operations.
     * @param trueDangerousMode whether --true-dangerous-mode is set
     * @param finalDeleteOriginal whether delete-original/low-storage modes are in use
     * @param ultraLowStorageMode whether ultra-low-storage mode is in use
     * @param lowStorageMode whether low-storage mode is in use
     * @return true if user confirms, false if cancelled
     */
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
                    lowStorageMode       -> "low-storage"
                    else                 -> "delete-original"
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

    /**
     * Processes batch operations on multiple files.
     */
    private suspend fun processBatchFiles(
        files: List<File>,
        title: String?,
        force: Boolean,
        format: String,
        deleteOriginal: Boolean,
        useStreamingConversion: Boolean,
        useTrueStreaming: Boolean,
        trueDangerousMode: Boolean,
        batchWorkers: Int,
        skipIfTargetExists: Boolean
    ) {
        println("Found ${files.size} files to process using up to $batchWorkers parallel workers.")
        val dispatcher = Dispatchers.IO.limitedParallelism(batchWorkers)
        coroutineScope {
            files.forEach { file ->
                launch(dispatcher) {
                    try {
                        processLocalFile(
                            file, title, force, format, deleteOriginal,
                            useStreamingConversion, useTrueStreaming, trueDangerousMode,
                            skipIfTargetExists
                        )
                    } catch (e: Exception) {
                        println("Error processing ${file.name}: ${e.message}")
                        logDebug { "Stack trace: ${e.stackTraceToString()}" }
                    }
                }
            }
        }
        println("\nBatch processing complete.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val parser = ArgParser(programName = "MangaCombiner")

        val source by parser.argument(ArgType.String, fullName = "source")

        val update by parser.option(ArgType.String, fullName = "update")
        val title by parser.option(ArgType.String, shortName = "t", fullName = "title")
        val format by parser.option(ArgType.String, fullName = "format").default(DEFAULT_FORMAT)
        val exclude by parser.option(ArgType.String, shortName = "e", fullName = "exclude")
        val force by parser.option(ArgType.Boolean, shortName = "f", fullName = "force").default(false)
        val deleteOriginal by parser.option(ArgType.Boolean, fullName = "delete-original").default(false)
        val lowStorageMode by parser.option(ArgType.Boolean, fullName = "low-storage-mode").default(false)
        val ultraLowStorageMode by parser.option(ArgType.Boolean, fullName = "ultra-low-storage-mode").default(false)
        val trueDangerousMode by parser.option(ArgType.Boolean, fullName = "true-dangerous-mode").default(false)
        val workers by parser.option(ArgType.Int, shortName = "w", fullName = "workers").default(DEFAULT_WORKERS)
        val chapterWorkers by parser.option(ArgType.Int, fullName = "chapter-workers").default(DEFAULT_CHAPTER_WORKERS)
        val batchWorkers by parser.option(ArgType.Int, fullName = "batch-workers").default(DEFAULT_BATCH_WORKERS)
        val debug by parser.option(ArgType.Boolean, fullName = "debug").default(false)

        val skipIfTargetExists by parser.option(
            ArgType.Boolean,
            fullName = "skip-if-target-exists",
            description = "Skip conversion if the target .epub or .cbz already exists."
        ).default(false)

        try {
            parser.parse(args)
        } catch (e: Exception) {
            println("Error parsing arguments: ${e.message}")
            return
        }

        // Set debug mode
        isDebugEnabled = debug

        // Validate format
        val validatedFormat = try {
            validateFormat(format)
        } catch (e: IllegalArgumentException) {
            println(e.message)
            return
        }

        // Configure operation parameters
        val finalBatchWorkers = if (ultraLowStorageMode) 1 else batchWorkers
        val finalDeleteOriginal = deleteOriginal || lowStorageMode || ultraLowStorageMode || trueDangerousMode
        val useStreamingConversion = lowStorageMode || ultraLowStorageMode
        val useTrueStreaming = ultraLowStorageMode

        // Handle operation confirmations
        if (!handleOperationConfirmation(trueDangerousMode, finalDeleteOriginal, ultraLowStorageMode, lowStorageMode)) {
            return
        }

        // Parse exclude list
        val excludeSet = exclude?.split(' ')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet() ?: emptySet()

        // Execute main operation
        runBlocking {
            try {
                when {
                    // Sync operation: Update existing CBZ with new chapters
                    update != null && source.lowercase().startsWith("http") -> {
                        val updateFile = File(update!!)
                        if (!updateFile.exists()) {
                            println("Error: Update file not found: ${updateFile.absolutePath}")
                            return@runBlocking
                        }
                        syncCbzWithSource(updateFile, source, excludeSet, chapterWorkers)
                    }
                    // Download operation: Download new manga from URL
                    source.lowercase().startsWith("http") -> {
                        downloadAndCreate(source, title, excludeSet, validatedFormat, chapterWorkers)
                    }
                    // Batch operation: Process multiple files using glob pattern
                    "*" in source || "?" in source -> {
                        val files = expandGlobPath(source).filter {
                            it.extension.equals("cbz", true) || it.extension.equals("epub", true)
                        }
                        if (files.isEmpty()) {
                            println("No files found matching pattern '$source'.")
                            return@runBlocking
                        }
                        processBatchFiles(
                            files, title, force, validatedFormat, finalDeleteOriginal,
                            useStreamingConversion, useTrueStreaming, trueDangerousMode,
                            finalBatchWorkers, skipIfTargetExists
                        )
                    }
                    // Single file operation: Process a single local file
                    File(source).exists() -> {
                        processLocalFile(
                            File(source), title, force, validatedFormat, finalDeleteOriginal,
                            useStreamingConversion, useTrueStreaming, trueDangerousMode,
                            skipIfTargetExists
                        )
                    }
                    // Invalid source
                    else -> {
                        println("Error: Source '$source' is not a valid URL, an existing file path, or a recognized file pattern.")
                    }
                }
            } catch (e: Exception) {
                println("Fatal error: ${e.message}")
                logDebug { "Stack trace: ${e.stackTraceToString()}" }
            }
        }
    }
}
