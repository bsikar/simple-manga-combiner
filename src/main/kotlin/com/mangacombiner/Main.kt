package com.mangacombiner

import com.mangacombiner.combiner.handleCombination
import com.mangacombiner.scanner.scanForManga
import com.mangacombiner.util.expandGlobPath
import com.mangacombiner.util.getOutputPath
import com.mangacombiner.util.isDebugEnabled
import com.mangacombiner.util.logDebug
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
fun main(args: Array<String>) {
    val parser = ArgParser("CbzMangaTool")

    val directories by parser.option(
        ArgType.String,
        shortName = "d",
        fullName = "directory",
        description = "Directory to scan. Can be used multiple times.",
    ).multiple()

    val useGlob by parser.option(
        ArgType.Boolean,
        fullName = "use-glob",
        description = "Treat directory paths as glob patterns to find multiple directories."
    ).default(false)

    val list by parser.option(ArgType.Boolean, shortName = "l", fullName = "list", description = "List all manga series found").default(false)
    val checkFiles by parser.option(ArgType.Boolean, fullName = "check-files", description = "Check if combined files are up-to-date with sources.").default(false)
    val combine by parser.option(ArgType.String, shortName = "c", fullName = "combine", description = "Name of the manga series to combine")
    val combineAll by parser.option(ArgType.Boolean, fullName = "combine-all", description = "Combine all found manga series into their own files").default(false)
    val outputDir by parser.option(ArgType.String, shortName = "o", fullName = "output", description = "Output directory for combined file")
    val dryRun by parser.option(ArgType.Boolean, fullName = "dry-run", description = "Simulate combining without creating a file").default(false)
    val overwrite by parser.option(ArgType.Boolean, fullName = "overwrite", description = "Forcefully overwrite existing combined files.").default(false)
    val fix by parser.option(ArgType.Boolean, fullName = "fix", description = "Update combined files only if source files are newer.").default(false)
    val deepScan by parser.option(ArgType.Boolean, fullName = "deep-scan", description = "Scan inside archives to sort by internal chapter/page numbers.").default(false)
    val outputFormat by parser.option(ArgType.String, fullName = "output-format", description = "Output file format (only cbz is supported for writing)").default("cbz")
    val debug by parser.option(ArgType.Boolean, fullName = "debug", description = "Enable detailed debug logging.").default(false)

    try {
        parser.parse(args)
    } catch (e: Exception) {
        println(e.message)
        return
    }

    isDebugEnabled = debug
    logDebug { "Debug mode enabled." }
    logDebug { "Raw directory arguments: $directories" }
    logDebug { "Glob mode enabled: $useGlob" }

    if (directories.isEmpty()) {
        println("Error: You must provide at least one directory using the -d or --directory flag.")
        println("Example: ./gradlew run --args='-d /path/to/manga'")
        exitProcess(1)
    }

    val targetDirectories = if (useGlob) {
        logDebug { "Glob mode is ON. Expanding paths as patterns." }
        directories.flatMap { path -> expandGlobPath(path) }
    } else {
        logDebug { "Glob mode is OFF. Treating paths as literal." }
        directories.map { path -> File(path) }.filter { file ->
            val isValid = file.isDirectory
            if (!isValid) {
                logDebug { "Path '${file.absolutePath}' is not a valid directory. Ignoring." }
            }
            isValid
        }
    }.distinct().sorted()


    if (targetDirectories.isEmpty()) {
        println("Error: The specified path(s) or pattern(s) did not match any directories.")
        return
    }

    logDebug { "Input paths resolved to ${targetDirectories.size} unique director(y/ies): ${targetDirectories.map { it.name }}" }

    // Process each matched directory
    for (dir in targetDirectories) {
        println("\n=================================================")
        println("Processing Directory: ${dir.name}")
        println("=================================================")
        logDebug { "Starting main logic for directory: ${dir.absolutePath}" }

        val mangaCollection = scanForManga(dir)
        if (mangaCollection.isEmpty()) {
            println("No CBZ, CBR, or EPUB manga files found in: ${dir.name}")
            continue // Skip to the next matched directory
        }

        if (list) {
            println("Found the following manga series in ${dir.name}:")
            mangaCollection.keys.sorted().forEach { title ->
                println("- $title (${mangaCollection[title]?.size} files)")
            }
        }

        if (checkFiles) {
            println("--- Checking status of combined files in ${dir.name} ---")
            mangaCollection.entries.sortedBy { it.key }.forEach { (title, chapters) ->
                val outputPath = getOutputPath(title, outputDir)
                print("[$title]: ")
                if (Files.exists(outputPath)) {
                    val combinedFileModTime = Files.getLastModifiedTime(outputPath).toMillis()
                    val needsUpdate = chapters.any { it.file.lastModified() > combinedFileModTime }
                    if (needsUpdate) {
                        println("Needs update (source files are newer).")
                    } else {
                        println("Up-to-date.")
                    }
                } else {
                    println("Not combined yet.")
                }
            }
        }

        if (combine != null) {
            val mangaToCombine = combine!!
            if (mangaToCombine.startsWith("-")) {
                println("Error: The --combine flag requires a manga title. You provided '$mangaToCombine'.")
                println("Usage: --combine \"Manga Title\"")
                return
            }
            val chapters = mangaCollection[mangaToCombine]
            if ( chapters == null) {
                println("Error: Manga series '$mangaToCombine' not found in ${dir.name}.")
                val closestMatches = mangaCollection.keys.filter { it.contains(mangaToCombine, ignoreCase = true) }
                if (closestMatches.isNotEmpty()) {
                    println("\nDid you mean one of these?")
                    closestMatches.forEach { println("- $it") }
                }
                continue
            }
            handleCombination(mangaToCombine, chapters, outputDir, overwrite, fix, dryRun, deepScan)
        }

        if (combineAll) {
            println("--- Combining all found manga series in ${dir.name} ---")
            if (dryRun) {
                println("--- Performing DRY RUN sequentially for clean output ---")
                mangaCollection.entries.sortedBy { it.key }.forEach { (title, chapters) ->
                    handleCombination(title, chapters, outputDir, overwrite, fix, true, deepScan)
                }
            } else {
                runBlocking {
                    val jobs = mangaCollection.entries.sortedBy { it.key }.map { (title, chapters) ->
                        async(Dispatchers.IO) {
                            handleCombination(title, chapters, outputDir, overwrite, fix, false, deepScan)
                        }
                    }
                    jobs.awaitAll()
                }
            }
        }
    }
    println("\nAll matched directories processed.")
}
