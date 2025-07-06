package com.mangacombiner.service

import com.mangacombiner.util.Logger
import org.springframework.stereotype.Component
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

@Component
class FileConverter(
    private val interactiveSelector: InteractiveChapterSelector
) {
    data class ChapterSelectionResult(
        val folders: List<File>?,
        val error: String? = null
    )

    fun process(
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
                "epub" to "cbz" -> processEpubToCbz(options)
                "cbz" to "cbz" -> reprocessCbz(options, mangaTitle, outputFile, processor)
                "epub" to "epub" -> reprocessEpub(options, mangaTitle, outputFile, processor)
                else -> {
                    val message = "Conversion from .$inputFormat to .$finalOutputFormat is not supported."
                    println(message)
                    ProcessResult(false, error = message)
                }
            }
        } catch (e: IOException) {
            val msg = "ERROR: A file failure occurred while processing ${options.inputFile.name}: ${e.message}"
            Logger.logError(msg, e)
            ProcessResult(false, error = msg)
        }
    }

    private fun handleInteractiveChapterSelection(
        options: LocalFileOptions,
        chapterFolders: List<File>,
        mangaTitle: String
    ): ChapterSelectionResult {
        if (!options.interactive || options.dryRun) {
            return ChapterSelectionResult(chapterFolders)
        }

        val selectableChapters = interactiveSelector.createSelectableChaptersFromFolders(chapterFolders)
        val selectedChapters = interactiveSelector.selectChapters(selectableChapters, mangaTitle)

        return when {
            selectedChapters == null -> ChapterSelectionResult(null, "Processing cancelled by user.")
            selectedChapters.isEmpty() -> ChapterSelectionResult(emptyList(), "No chapters selected for processing.")
            else -> {
                val selectedFolders = chapterFolders.filter { folder ->
                    selectedChapters.any { it.url == folder.absolutePath }
                }
                println("Proceeding with ${selectedFolders.size} selected chapters...")
                ChapterSelectionResult(selectedFolders)
            }
        }
    }

    private fun reprocessCbz(
        options: LocalFileOptions,
        mangaTitle: String,
        outputFile: File,
        processor: ProcessorService
    ): ProcessResult {
        println("Re-processing to fix structure...")
        val tempDir = Files.createTempDirectory(options.tempDirectory.toPath(), "cbz-reprocess-").toFile()
        return try {
            processor.extractZip(options.inputFile, tempDir)
            val initialChapterFolders = tempDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()

            if (initialChapterFolders.isEmpty()) {
                return ProcessResult(false, error = "No chapter folders found in CBZ to reprocess.")
            }

            val selectionResult = handleInteractiveChapterSelection(options, initialChapterFolders, mangaTitle)
            val chapterFolders = selectionResult.folders

            when {
                chapterFolders == null -> ProcessResult(false, error = selectionResult.error)
                chapterFolders.isEmpty() -> ProcessResult(false, error = selectionResult.error)
                else -> {
                    processor.createCbzFromFolders(mangaTitle, chapterFolders, outputFile)
                    ProcessResult(true, outputFile)
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun reprocessEpub(
        options: LocalFileOptions,
        mangaTitle: String,
        outputFile: File,
        processor: ProcessorService
    ): ProcessResult {
        println("Re-processing EPUB to apply changes...")
        val tempDir = Files.createTempDirectory(options.tempDirectory.toPath(), "epub-reprocess-").toFile()
        return try {
            processor.extractZip(options.inputFile, tempDir)

            // Find all image files recursively within the extracted EPUB contents
            val imageFiles = tempDir.walk()
                .filter { it.isFile && it.extension.lowercase() in ProcessorService.IMAGE_EXTENSIONS }
                .toList()

            if (imageFiles.isEmpty()) {
                return ProcessResult(false, error = "No images found in the source EPUB.")
            }

            // Create a single "chapter" folder to hold all the original images
            val consolidatedChapterDir = File(tempDir, "Chapter-01-Combined").apply { mkdirs() }

            // Copy all found images into the consolidated chapter, renaming them to ensure correct order
            imageFiles.sorted().forEachIndexed { index, file ->
                val newName = "page_${String.format(Locale.ROOT, "%04d", index + 1)}.${file.extension}"
                file.copyTo(File(consolidatedChapterDir, newName))
            }

            // The list of "chapters" to rebuild from is now our single, consolidated folder
            val chapterFolders = listOf(consolidatedChapterDir)

            processor.createEpubFromFolders(mangaTitle, chapterFolders, outputFile)
            ProcessResult(outputFile.exists() && outputFile.length() > 0, outputFile)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun processCbzToEpub(
        options: LocalFileOptions,
        mangaTitle: String,
        outputFile: File,
        processor: ProcessorService
    ): ProcessResult {
        println("Converting ${options.inputFile.name} to EPUB format...")
        val tempDir = Files.createTempDirectory(options.tempDirectory.toPath(), "cbz-to-epub-").toFile()
        return try {
            processor.extractZip(options.inputFile, tempDir)
            val initialChapterFolders = tempDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()

            if (initialChapterFolders.isEmpty()) {
                return ProcessResult(false, error = "No chapter folders found in CBZ for conversion.")
            }

            val selectionResult = handleInteractiveChapterSelection(options, initialChapterFolders, mangaTitle)
            val chapterFolders = selectionResult.folders

            when {
                chapterFolders == null -> ProcessResult(false, error = selectionResult.error)
                chapterFolders.isEmpty() -> ProcessResult(false, error = selectionResult.error)
                else -> {
                    println("Converting ${chapterFolders.size} selected chapters to EPUB...")
                    processor.createEpubFromFolders(mangaTitle, chapterFolders, outputFile)
                    ProcessResult(outputFile.exists() && outputFile.length() > 0, outputFile)
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun processEpubToCbz(options: LocalFileOptions): ProcessResult {
        println("Converting ${options.inputFile.name} to CBZ format...")
        // This logic would need to be implemented, e.g., by extracting EPUB images
        // and using processor.createCbzFromFolders. For now, returning not supported.
        return ProcessResult(false, error = "EPUB to CBZ conversion is not yet implemented.")
    }
}
