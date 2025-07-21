package com.mangacombiner.service

import com.mangacombiner.util.Logger
import com.mangacombiner.util.ZipUtils
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

class FileConverter {
    suspend fun process(
        options: LocalFileOptions,
        mangaTitle: String,
        outputFile: File,
        processor: ProcessorService
    ): ProcessResult {
        return try {
            val inputFormat = options.inputFile.extension.lowercase()
            val finalOutputFormat = options.outputFormat.lowercase()
            Logger.logDebug { "Processing file conversion from .$inputFormat to .$finalOutputFormat" }
            when (inputFormat to finalOutputFormat) {
                "epub" to "epub" -> reprocessEpub(options, mangaTitle, outputFile, processor)
                else -> {
                    val message = "Conversion from .$inputFormat to .$finalOutputFormat is not supported."
                    Logger.logError(message)
                    ProcessResult(false, error = message)
                }
            }
        } catch (e: IOException) {
            val msg = "ERROR: A file failure occurred while processing ${options.inputFile.name}"
            Logger.logError(msg, e)
            ProcessResult(false, error = msg)
        }
    }

    private suspend fun reprocessEpub(
        options: LocalFileOptions,
        mangaTitle: String,
        outputFile: File,
        processor: ProcessorService
    ): ProcessResult {
        Logger.logInfo("Re-processing EPUB to apply changes...")
        val tempDir = File(options.tempDirectory, "epub-reprocess-${UUID.randomUUID()}").apply { mkdirs() }
        Logger.logDebug { "Created temp directory for EPUB reprocessing: ${tempDir.absolutePath}" }
        return try {
            ZipUtils.extractZip(options.inputFile, tempDir)

            val imageFiles = tempDir.walk()
                .filter { it.isFile && it.extension.lowercase() in ProcessorService.IMAGE_EXTENSIONS }
                .toList()

            if (imageFiles.isEmpty()) {
                return ProcessResult(false, error = "No images found in the source EPUB.")
            }

            val consolidatedChapterDir = File(tempDir, "Chapter-01-Combined").apply { mkdirs() }
            imageFiles.sorted().forEachIndexed { index, file ->
                val newName = "page_${String.format(Locale.ROOT, "%04d", index + 1)}.${file.extension}"
                file.copyTo(File(consolidatedChapterDir, newName))
            }

            val chapterFolders = listOf(consolidatedChapterDir)

            // Since this is a reprocess, we don't have new metadata to apply.
            // A more advanced implementation might re-fetch it.
            processor.createEpubFromFolders(
                mangaTitle = mangaTitle,
                chapterFolders = chapterFolders,
                outputFile = outputFile,
                seriesUrl = ZipUtils.getSourceUrlFromEpub(options.inputFile),
                seriesMetadata = null
            )
            ProcessResult(outputFile.exists() && outputFile.length() > 0, outputFile)
        } finally {
            Logger.logDebug { "Deleting temp directory: ${tempDir.absolutePath}" }
            tempDir.deleteRecursively()
        }
    }
}
