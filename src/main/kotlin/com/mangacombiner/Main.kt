package com.mangacombiner

import com.mangacombiner.core.downloadAndCreate
import com.mangacombiner.core.processLocalFile
import com.mangacombiner.core.syncCbzWithSource
import com.mangacombiner.util.expandGlobPath
import com.mangacombiner.util.isDebugEnabled
import kotlinx.cli.*
import kotlinx.coroutines.*
import java.io.File

object MainKt {
    fun String.titlecase(): String = this.split(' ').joinToString(" ") { word ->
        if (word.isEmpty()) {
            word
        } else {
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun getConfirmation(prompt: String): Boolean {
        print("$prompt (yes/no): ")
        return readlnOrNull()?.trim()?.equals("yes", ignoreCase = true) ?: false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val parser = ArgParser(programName = "MangaCombiner")

        val source by parser.argument(
            ArgType.String,
            fullName = "source",
            description = "The source URL, a local file (.cbz or .epub), or a glob pattern."
        )

        val update by parser.option(ArgType.String, fullName = "update", description = "Path to a local CBZ file to update with missing chapters from the source URL.")
        val title by parser.option(ArgType.String, shortName = "t", fullName = "title", description = "Custom title for the output.")
        val format by parser.option(ArgType.String, fullName = "format", description = "The output format for new files (cbz or epub).").default("cbz")
        val exclude by parser.option(ArgType.String, shortName = "e", fullName = "exclude", description = "Space-separated list of chapter URL slugs to exclude.")
        val force by parser.option(ArgType.Boolean, shortName = "f", fullName = "force", description = "Force overwrite of existing ComicInfo.xml in metadata-only mode.").default(false)

        val deleteOriginal by parser.option(ArgType.Boolean, fullName = "delete-original", description = "Delete the source file(s) after a successful 'copy-then-delete' conversion.").default(false)
        val lowStorageMode by parser.option(ArgType.Boolean, fullName = "low-storage-mode", description = "Uses a 'copy-then-delete' streaming conversion. Deletes original on success.").default(false)
        val ultraLowStorageMode by parser.option(ArgType.Boolean, fullName = "ultra-low-storage-mode", description = "Uses a minimal memory 'copy-then-delete' conversion. Deletes original. Sets workers to 1.").default(false)
        val trueDangerousMode by parser.option(ArgType.Boolean, fullName = "true-dangerous-mode", description = "DANGEROUS: Moves images one by one. Interruption WILL corrupt your source file.").default(false)

        val workers by parser.option(ArgType.Int, shortName = "w", fullName = "workers", description = "Concurrent image downloads per chapter.").default(10)
        val chapterWorkers by parser.option(ArgType.Int, fullName = "chapter-workers", description = "Concurrent chapters to download during an update.").default(4)
        val batchWorkers by parser.option(ArgType.Int, fullName = "batch-workers", description = "Concurrent local files to process in batch mode.").default(4)
        val debug by parser.option(ArgType.Boolean, fullName = "debug", description = "Enable detailed debug logging.").default(false)

        parser.parse(args)
        isDebugEnabled = debug

        val finalBatchWorkers = if (ultraLowStorageMode) 1 else batchWorkers
        val finalDeleteOriginal = deleteOriginal || lowStorageMode || ultraLowStorageMode || trueDangerousMode

        if (trueDangerousMode) {
            println("---")
            println("--- EXTREME DANGER: '--true-dangerous-mode' is enabled! ---")
            println("--- This mode moves images one-by-one, modifying the source file as it runs.")
            println("--- PRESSING CTRL+C WILL CORRUPT YOUR ORIGINAL FILE BEYOND RECOVERY.")
            println("---")
            if (!getConfirmation("You have been warned. Do you understand the risk and wish to continue?")) {
                println("Operation cancelled. Good choice."); return
            }
            println("\n--- FINAL CONFIRMATION ---")
            if (!getConfirmation("This is irreversible. Are you absolutely certain you want to risk your source file?")) {
                println("Operation cancelled."); return
            }
            println("\nConfirmation received. Proceeding with dangerous operation...")
        } else if (finalDeleteOriginal) {
            val mode = when {
                ultraLowStorageMode -> "ultra-low-storage"
                lowStorageMode -> "low-storage"
                else -> "delete-original"
            }
            println("---")
            println("--- WARNING: '$mode' mode is enabled. ---")
            println("--- This is a safe 'copy-then-delete' operation. The original file will be deleted only after a successful conversion.")
            println("---")
            if (!getConfirmation("Are you sure you want to delete the original file upon success?")) {
                println("Operation cancelled."); return
            }
            println("\nConfirmation received. Proceeding...")
        }

        val useStreamingConversion = lowStorageMode || ultraLowStorageMode
        val useTrueStreaming = ultraLowStorageMode
        val excludeSet = exclude?.split(' ')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

        runBlocking {
            when {
                update != null && source.lowercase().startsWith("http") -> syncCbzWithSource(File(update!!), source, excludeSet, chapterWorkers)
                source.lowercase().startsWith("http") -> downloadAndCreate(source, title, excludeSet, format, chapterWorkers)

                "*" in source || "?" in source -> {
                    val files = expandGlobPath(source).filter { it.extension.equals("cbz", true) || it.extension.equals("epub", true) }
                    if (files.isEmpty()) { println("No files found matching pattern '$source'."); return@runBlocking }

                    val workerInfo = if (ultraLowStorageMode) {
                        "$finalBatchWorkers parallel worker (Ultra-Low-Storage Mode)."
                    } else {
                        "$finalBatchWorkers parallel workers."
                    }
                    println("Found ${files.size} files to process using up to $workerInfo")

                    val dispatcher = Dispatchers.IO.limitedParallelism(finalBatchWorkers)
                    coroutineScope {
                        files.forEach { file ->
                            launch(dispatcher) {
                                processLocalFile(file, null, force, format, finalDeleteOriginal, useStreamingConversion, useTrueStreaming, trueDangerousMode)
                            }
                        }
                    }
                    println("\nBatch processing complete.")
                }

                File(source).exists() -> processLocalFile(File(source), title, force, format, finalDeleteOriginal, useStreamingConversion, useTrueStreaming, trueDangerousMode)
                else -> println("Error: Source '$source' is not a valid URL, an existing file path, or a recognized file pattern.")
            }
        }
    }
}
