package com.mangacombiner.service

import com.mangacombiner.util.logError
import org.springframework.stereotype.Component
import java.io.File
import java.io.IOException
import java.nio.file.Files

@Component
class FileConverter {
    fun process(
        options: LocalFileOptions,
        mangaTitle: String,
        outputFile: File,
        processor: ProcessorService,
        infoPageFile: File? = null
    ): ProcessResult {
        return try {
            val inputFormat = options.inputFile.extension.lowercase()
            val finalOutputFormat = options.outputFormat.lowercase()

            when (inputFormat to finalOutputFormat) {
                "cbz" to "epub" -> processCbzToEpub(options, mangaTitle, outputFile, processor, infoPageFile)
                "epub" to "cbz" -> processEpubToCbz(options)
                "cbz" to "cbz" -> reprocessCbz(options, mangaTitle, outputFile, processor, infoPageFile)
                "epub" to "epub" -> {
                    // Simple rename/move if names differ
                    if (options.inputFile.canonicalPath != outputFile.canonicalPath) {
                        Files.move(options.inputFile.toPath(), outputFile.toPath())
                    }
                    ProcessResult(true, outputFile)
                }
                else -> {
                    val message = "Conversion from .$inputFormat to .$finalOutputFormat is not supported."
                    println(message)
                    ProcessResult(false, error = message)
                }
            }
        } catch (e: IOException) {
            val msg = "ERROR: A file failure occurred while processing ${options.inputFile.name}: ${e.message}"
            logError(msg, e)
            ProcessResult(false, error = msg)
        }
    }

    private fun reprocessCbz(
        options: LocalFileOptions,
        mangaTitle: String,
        outputFile: File,
        processor: ProcessorService,
        infoPageFile: File?
    ): ProcessResult {
        println("Re-processing to fix structure...")
        val tempDir = Files.createTempDirectory(options.tempDirectory.toPath(), "cbz-reprocess-").toFile()
        return try {
            processor.extractZip(options.inputFile, tempDir)
            val chapterFolders = tempDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
            if (chapterFolders.isNotEmpty() || infoPageFile != null) {
                processor.createCbzFromFolders(mangaTitle, chapterFolders, outputFile, infoPageFile)
                ProcessResult(true, outputFile)
            } else {
                ProcessResult(false, error = "No chapter folders found in CBZ to reprocess.")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun processCbzToEpub(
        options: LocalFileOptions,
        mangaTitle: String,
        outputFile: File,
        processor: ProcessorService,
        infoPageFile: File?
    ): ProcessResult {
        println("Converting ${options.inputFile.name} to EPUB format...")
        val tempDir = Files.createTempDirectory(options.tempDirectory.toPath(), "cbz-to-epub-").toFile()
        return try {
            processor.extractZip(options.inputFile, tempDir)
            val chapterFolders = tempDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
            if (chapterFolders.isNotEmpty() || infoPageFile != null) {
                processor.createEpubFromFolders(mangaTitle, chapterFolders, outputFile, infoPageFile)
                ProcessResult(outputFile.exists() && outputFile.length() > 0, outputFile)
            } else {
                ProcessResult(false, error = "No chapter folders found in CBZ for conversion.")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun processEpubToCbz(
        options: LocalFileOptions,
    ): ProcessResult {
        println("Converting ${options.inputFile.name} to CBZ format...")
        // This logic would need to be implemented, e.g., by extracting EPUB images
        // and using processor.createCbzFromFolders. For now, returning not supported.
        return ProcessResult(false, error = "EPUB to CBZ conversion is not yet implemented.")
    }
}
