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

            when (inputFormat to finalOutputFormat) {
                "cbz" to "epub" -> processCbzToEpub(options, mangaTitle, outputFile, processor)
                "epub" to "cbz" -> processEpubToCbz(options, mangaTitle, outputFile, processor)
                "cbz" to "cbz" -> reprocessCbz(options, mangaTitle, outputFile, processor)
                "epub" to "epub" -> reprocessEpub(options, mangaTitle, outputFile, processor)
                else -> {
                    val message = "Conversion from .$inputFormat to .$finalOutputFormat is not supported."
                    Logger.logError(message)
                    ProcessResult(false, error = message)
                }
            }
        } catch (e: IOException) {
            val msg = "ERROR: A file failure occurred while processing ${options.inputFile.name}: ${e.message}"
            Logger.logError(msg, e)
            ProcessResult(false, error = msg)
        }
    }

    private suspend fun reprocessCbz(
        options: LocalFileOptions,
        mangaTitle: String,
        outputFile: File,
        processor: ProcessorService
    ): ProcessResult {
        Logger.logInfo("Re-processing to fix structure...")
        val tempDir = File(options.tempDirectory, "cbz-reprocess-${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            ZipUtils.extractZip(options.inputFile, tempDir)
            val chapterFolders = tempDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
            if (chapterFolders.isNotEmpty()) {
                processor.createCbzFromFolders(mangaTitle, chapterFolders, outputFile)
                ProcessResult(true, outputFile)
            } else {
                ProcessResult(false, error = "No chapter folders found in CBZ to reprocess.")
            }
        } finally {
            tempDir.deleteRecursively()
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

            processor.createEpubFromFolders(mangaTitle, chapterFolders, outputFile)
            ProcessResult(outputFile.exists() && outputFile.length() > 0, outputFile)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun processCbzToEpub(
        options: LocalFileOptions,
        mangaTitle: String,
        outputFile: File,
        processor: ProcessorService
    ): ProcessResult {
        Logger.logInfo("Converting ${options.inputFile.name} to EPUB format...")
        val tempDir = File(options.tempDirectory, "cbz-to-epub-${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            ZipUtils.extractZip(options.inputFile, tempDir)
            val chapterFolders = tempDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
            if (chapterFolders.isNotEmpty()) {
                processor.createEpubFromFolders(mangaTitle, chapterFolders, outputFile)
                ProcessResult(outputFile.exists() && outputFile.length() > 0, outputFile)
            } else {
                ProcessResult(false, error = "No chapter folders found in CBZ for conversion.")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun processEpubToCbz(
        options: LocalFileOptions,
        mangaTitle: String,
        outputFile: File,
        processor: ProcessorService
    ): ProcessResult {
        Logger.logInfo("Converting ${options.inputFile.name} to CBZ format...")
        val tempDir = File(options.tempDirectory, "epub-to-cbz-${UUID.randomUUID()}").apply { mkdirs() }
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

            processor.createCbzFromFolders(mangaTitle, chapterFolders, outputFile)
            ProcessResult(outputFile.exists() && outputFile.length() > 0, outputFile)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
