package com.mangacombiner.service

import com.mangacombiner.ui.viewmodel.OperationState
import com.mangacombiner.util.Logger
import com.mangacombiner.util.SlugUtils
import kotlinx.coroutines.flow.StateFlow
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import nl.adaptivity.xmlutil.serialization.XML
import java.io.File
import kotlin.io.path.nameWithoutExtension

class ProcessorService(
    private val fileConverter: FileConverter
) {
    internal companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
        const val COMIC_INFO_FILE = "ComicInfo.xml"
    }

    private val xmlSerializer = XML { indentString = "  " }

    private fun String.isImageFile(): Boolean =
        substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS

    private val chapterComparator = Comparator<File> { f1, f2 ->
        val parts1 = SlugUtils.parseChapterSlugsForSorting(f1.name)
        val parts2 = SlugUtils.parseChapterSlugsForSorting(f2.name)
        if (parts1.isEmpty() || parts2.isEmpty()) return@Comparator f1.name.compareTo(f2.name)
        val maxIndex = minOf(parts1.size, parts2.size)
        for (i in 0 until maxIndex) {
            val compare = parts1[i].compareTo(parts2[i])
            if (compare != 0) return@Comparator compare
        }
        return@Comparator parts1.size.compareTo(parts2.size)
    }

    private fun sortChapterFolders(folders: List<File>): List<File> = folders.sortedWith(chapterComparator)

    private fun addImageToCbz(zipFile: ZipFile, folder: File, imageFile: File) {
        val zipParams = ZipParameters().apply { fileNameInZip = "${folder.name}/${imageFile.name}" }
        zipFile.addFile(imageFile, zipParams)
    }

    private fun addChaptersToCbz(zipFile: ZipFile, sortedFolders: List<File>, operationState: StateFlow<OperationState>?) {
        for (folder in sortedFolders) {
            if (operationState?.value == OperationState.CANCELLING) return
            folder.listFiles()?.filter { it.isFile && it.name.isImageFile() }?.sorted()?.forEach { imageFile ->
                if (operationState?.value == OperationState.CANCELLING) return
                addImageToCbz(zipFile, folder, imageFile)
            }
        }
    }

    private fun populateCbzFile(
        zipFile: ZipFile,
        mangaTitle: String,
        sortedFolders: List<File>,
        operationState: StateFlow<OperationState>?
    ) {
        val cbzGenerator = CbzStructureGenerator(xmlSerializer)
        val (bookmarks, totalPageCount) = cbzGenerator.createBookmarks(sortedFolders)

        if (totalPageCount == 0) {
            Logger.logInfo("Warning: No images found for $mangaTitle. Skipping CBZ creation.")
            return
        }

        val comicInfoXml = cbzGenerator.generateComicInfoXml(mangaTitle, bookmarks, totalPageCount)
        val params = ZipParameters().apply { fileNameInZip = COMIC_INFO_FILE }
        zipFile.addStream(comicInfoXml.byteInputStream(), params)
        addChaptersToCbz(zipFile, sortedFolders, operationState)
    }

    private fun buildCbz(
        outputFile: File,
        mangaTitle: String,
        sortedFolders: List<File>,
        operationState: StateFlow<OperationState>?
    ) {
        try {
            ZipFile(outputFile).use { zipFile ->
                populateCbzFile(zipFile, mangaTitle, sortedFolders, operationState)
            }
            if (operationState?.value == OperationState.CANCELLING) {
                Logger.logInfo("CBZ creation cancelled. Deleting partial file.")
                outputFile.delete()
                return
            }
            Logger.logInfo("Successfully created: ${outputFile.name}")
        } catch (e: ZipException) {
            Logger.logError("Failed to create CBZ file ${outputFile.name}", e)
        }
    }

    fun createCbzFromFolders(
        mangaTitle: String,
        chapterFolders: List<File>,
        outputFile: File,
        operationState: StateFlow<OperationState>? = null
    ) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val sortedFolders = sortChapterFolders(chapterFolders)
        Logger.logInfo("Creating CBZ archive: ${outputFile.name}...")
        buildCbz(outputFile, mangaTitle, sortedFolders, operationState)
    }

    private fun addChaptersToEpub(
        epubZip: ZipFile,
        sortedFolders: List<File>,
        epubGenerator: EpubStructureGenerator,
        metadata: EpubStructureGenerator.EpubMetadata,
        operationState: StateFlow<OperationState>?
    ) {
        for (folder in sortedFolders) {
            if (operationState?.value == OperationState.CANCELLING) return
            val images = folder.listFiles()?.filter { it.isFile && it.name.isImageFile() }?.sorted()
            if (!images.isNullOrEmpty()) {
                epubGenerator.addChapterToEpub(images, folder.name, metadata, epubZip)
            }
        }
    }

    fun createEpubFromFolders(
        mangaTitle: String,
        chapterFolders: List<File>,
        outputFile: File,
        operationState: StateFlow<OperationState>? = null
    ) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()
        Logger.logInfo("Creating EPUB archive: ${outputFile.name}...")

        val epubGenerator = EpubStructureGenerator()
        val metadata = EpubStructureGenerator.EpubMetadata()
        val sortedFolders = sortChapterFolders(chapterFolders)

        try {
            ZipFile(outputFile).use { epubZip ->
                epubGenerator.addEpubCoreFiles(epubZip)
                addChaptersToEpub(epubZip, sortedFolders, epubGenerator, metadata, operationState)

                if (operationState?.value == OperationState.CANCELLING) {
                    Logger.logInfo("EPUB creation cancelled. Deleting partial file.")
                } else {
                    epubGenerator.addEpubMetadataFiles(epubZip, mangaTitle, metadata)
                }
            }
            if (operationState?.value == OperationState.CANCELLING) {
                outputFile.delete()
                return
            }
            Logger.logInfo("Successfully created: ${outputFile.name}")
        } catch (e: ZipException) {
            Logger.logError("Failed to create EPUB file ${outputFile.name}", e)
        }
    }

    fun extractZip(zipFile: File, destination: File): Boolean {
        return try {
            Logger.logDebug { "Extracting ${zipFile.name} to ${destination.absolutePath}" }
            ZipFile(zipFile).extractAll(destination.absolutePath)
            true
        } catch (e: ZipException) {
            Logger.logError("Failed to extract zip file ${zipFile.name}", e)
            false
        }
    }

    fun processLocalFile(options: LocalFileOptions) {
        Logger.logInfo("\nProcessing local file: ${options.inputFile.name}")

        val mangaTitle = options.customTitle ?: options.inputFile.toPath().nameWithoutExtension.toString()
        val outputFile = File(options.inputFile.parent, "$mangaTitle.${options.outputFormat}")

        if (shouldSkipProcessing(options, outputFile)) {
            return
        }

        if (options.dryRun) {
            Logger.logInfo("[DRY RUN] Would process ${options.inputFile.name} into ${outputFile.name}.")
            if (options.deleteOriginal) {
                Logger.logInfo("[DRY RUN] Would delete original file: ${options.inputFile.name} on success.")
            }
            return
        }

        val result = fileConverter.process(options, mangaTitle, outputFile, this)
        handlePostProcessing(options.inputFile, result, options.deleteOriginal, options.useTrueDangerousMode)
    }

    private fun shouldSkipProcessing(options: LocalFileOptions, outputFile: File): Boolean {
        var shouldSkip = false
        when {
            options.inputFile.extension.lowercase() !in setOf("cbz", "epub") -> {
                Logger.logError("Error: Input file must be a .cbz or .epub file.")
                shouldSkip = true
            }
            options.inputFile.canonicalPath == outputFile.canonicalPath &&
                    options.inputFile.extension.lowercase() == options.outputFormat.lowercase() -> {
                Logger.logInfo("Re-processing file in place: ${outputFile.name}")
            }
            outputFile.exists() -> {
                if (options.skipIfTargetExists) {
                    Logger.logInfo("Skipping ${options.inputFile.name}: Target ${outputFile.name} already exists.")
                    shouldSkip = true
                } else if (!options.forceOverwrite) {
                    val message = "Error: Output file ${outputFile.name} already exists. Use --force to overwrite."
                    Logger.logError(message)
                    shouldSkip = true
                }
            }
        }
        return shouldSkip
    }

    private fun handlePostProcessing(
        inputFile: File,
        result: ProcessResult,
        deleteOriginal: Boolean,
        useTrueDangerousMode: Boolean
    ) {
        val successfulDelete = result.success &&
                deleteOriginal &&
                result.outputFile != null &&
                inputFile.canonicalPath != result.outputFile.canonicalPath

        when {
            successfulDelete -> {
                Logger.logInfo("Operation successful. Deleting original file: ${inputFile.name}")
                if (!inputFile.delete()) {
                    Logger.logInfo("Warning: Failed to delete original file: ${inputFile.name}")
                }
            }
            !result.success && useTrueDangerousMode -> {
                Logger.logError(
                    "DANGEROUS OPERATION FAILED. Source file ${inputFile.name} may be CORRUPT."
                )
            }
            result.error != null -> {
                Logger.logError(result.error)
            }
        }
    }
}
