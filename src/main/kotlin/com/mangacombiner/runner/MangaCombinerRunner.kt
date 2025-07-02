package com.mangacombiner.runner

import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.ProcessorService
import com.mangacombiner.util.expandGlobPath
import com.mangacombiner.util.isDebugEnabled
import com.mangacombiner.util.logError
import kotlinx.cli.*
import kotlinx.coroutines.runBlocking
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import java.io.File

@Component
class MangaCombinerRunner(
    private val downloadService: DownloadService,
    private val processorService: ProcessorService
) : CommandLineRunner {

    private companion object {
        const val DEFAULT_IMAGE_WORKERS = 2
        const val DEFAULT_FORMAT = "cbz"
    }

    private fun validateFormat(format: String): String {
        val lowerFormat = format.lowercase()
        require(lowerFormat in setOf("cbz", "epub")) {
            "Unsupported format: $format. Supported formats are: cbz, epub"
        }
        return lowerFormat
    }

    override fun run(vararg args: String?) {
        val parser = ArgParser(programName = "MangaCombiner", strictSubcommandOptionsOrder = true)
        val source by parser.argument(ArgType.String, "source", "Source URL or local file path/glob pattern.")
        val update by parser.option(ArgType.String, "update", "u", "Path to a local file to update.")
        val updateMissing by parser.option(ArgType.Boolean, "update-missing", "Thoroughly check for incomplete chapters.").default(false)
        val fixPaths by parser.option(ArgType.String, "fix-paths", "Re-process local files. Accepts globs.")
        val tempIsDestination by parser.option(ArgType.Boolean, "temp-is-destination", "Use output directory for temp files.").default(false)
        val exclude by parser.option(ArgType.String, "exclude", "e", "Chapter URL slug to exclude.").multiple()
        val workers by parser.option(ArgType.Int, "workers", "w", "Concurrent image download threads.").default(DEFAULT_IMAGE_WORKERS)
        val format by parser.option(ArgType.String, "format", "f", "Output format ('cbz' or 'epub').").default(DEFAULT_FORMAT)
        val title by parser.option(ArgType.String, "title", "t", "Set a custom title for the output file.")

        try {
            // FIX: Convert the nullable Array<String?> to a non-nullable Array<String>
            parser.parse(args.filterNotNull().toTypedArray())
        } catch (e: Exception) {
            // ArgParser throws exceptions on --help, which is normal.
            if (!args.contains("--help")) {
                println("Error parsing arguments: ${e.message}")
            }
            return
        }

        isDebugEnabled = "true".equals(System.getenv("DEBUG"), ignoreCase = true)
        val validatedFormat = try {
            validateFormat(format)
        } catch (e: Exception) {
            println(e.message); return
        }

        val systemTemp = File(System.getProperty("java.io.tmpdir"))
        val tempDirectory = if (tempIsDestination) File(".").apply { mkdirs() } else systemTemp

        try {
            runBlocking {
                val isUrlSource = source.startsWith("http", true)
                when {
                    fixPaths != null -> {
                        println("Fixing paths for pattern: $fixPaths")
                        val filesToFix = expandGlobPath(fixPaths!!)
                        if (filesToFix.isEmpty()) {
                            println("No files found matching the pattern.")
                        } else {
                            println("Found ${filesToFix.size} files to fix.")
                            filesToFix.forEach { file ->
                                processorService.processLocalFile(file, null, file.extension, tempDirectory)
                            }
                        }
                    }
                    isUrlSource && update != null -> {
                        downloadService.syncLocalSource(update!!, source, workers, exclude, updateMissing, tempDirectory)
                    }
                    isUrlSource -> {
                        downloadService.downloadNewSeries(source, title, workers, exclude, validatedFormat, tempDirectory)
                    }
                    else -> {
                        val filesToProcess = expandGlobPath(source)
                        if (filesToProcess.isEmpty()) {
                            println("Error: Source '$source' is not a valid URL or an existing file/pattern.")
                        } else {
                            filesToProcess.forEach { file ->
                                processorService.processLocalFile(file, title, validatedFormat, tempDirectory)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("A fatal error occurred.")
            logError(e.message ?: "Unknown error", e)
        } finally {
            if (!tempIsDestination && tempDirectory != systemTemp && tempDirectory.exists()) {
                tempDirectory.deleteRecursively()
            }
        }
    }
}
