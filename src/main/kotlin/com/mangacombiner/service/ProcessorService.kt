package com.mangacombiner.service

import com.mangacombiner.util.getChapterPageCountsFromEpub
import com.mangacombiner.util.getChapterPageCountsFromZip
import com.mangacombiner.util.logDebug
import com.mangacombiner.util.logError
import com.mangacombiner.util.parseChapterSlugsForSorting
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import nl.adaptivity.xmlutil.serialization.XML
import org.springframework.stereotype.Service
import java.io.File
import kotlin.io.path.nameWithoutExtension

@Service
class ProcessorService(
    private val fileConverter: FileConverter,
    private val infoPageGeneratorService: InfoPageGeneratorService
) {
    internal companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
        const val COMIC_INFO_FILE = "ComicInfo.xml"
    }

    private val xmlSerializer = XML { indentString = "  " }

    private fun String.isImageFile(): Boolean =
        substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS

    private val chapterComparator = Comparator<File> { f1, f2 ->
        val parts1 = parseChapterSlugsForSorting(f1.name)
        val parts2 = parseChapterSlugsForSorting(f2.name)
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

    private fun addChaptersToCbz(zipFile: ZipFile, sortedFolders: List<File>) {
        sortedFolders.forEach { folder ->
            folder.listFiles()?.filter { it.isFile && it.name.isImageFile() }?.sorted()?.forEach { imageFile ->
                addImageToCbz(zipFile, folder, imageFile)
            }
        }
    }

    private fun populateCbzFile(
        zipFile: ZipFile,
        mangaTitle: String,
        sortedFolders: List<File>,
        infoPage: File?
    ) {
        val cbzGenerator = CbzStructureGenerator(xmlSerializer)
        val (bookmarks, totalPageCount) = cbzGenerator.createBookmarks(sortedFolders)

        if (totalPageCount == 0 && infoPage == null) {
            println("Warning: No images found for $mangaTitle. Skipping CBZ creation.")
            return
        }
        val comicInfoXml = cbzGenerator.generateComicInfoXml(mangaTitle, bookmarks, totalPageCount)

        val params = ZipParameters().apply { fileNameInZip = COMIC_INFO_FILE }
        zipFile.addStream(comicInfoXml.byteInputStream(), params)

        infoPage?.let {
            val infoParams = ZipParameters().apply { fileNameInZip = "0000_info_page.${it.extension}" }
            zipFile.addFile(it, infoParams)
        }

        addChaptersToCbz(zipFile, sortedFolders)
    }

    private fun buildCbz(
        outputFile: File,
        mangaTitle: String,
        sortedFolders: List<File>,
        infoPage: File?
    ) {
        try {
            ZipFile(outputFile).use { zipFile ->
                populateCbzFile(zipFile, mangaTitle, sortedFolders, infoPage)
            }
            println("Successfully created: ${outputFile.name}")
        } catch (e: ZipException) {
            logError("Failed to create CBZ file ${outputFile.name}", e)
        }
    }

    fun createCbzFromFolders(
        mangaTitle: String,
        chapterFolders: List<File>,
        outputFile: File,
        infoPage: File? = null
    ) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val sortedFolders = sortChapterFolders(chapterFolders)
        println("Creating CBZ archive: ${outputFile.name}...")

        buildCbz(outputFile, mangaTitle, sortedFolders, infoPage)
    }

    private fun addChaptersToEpub(
        epubZip: ZipFile,
        sortedFolders: List<File>,
        epubGenerator: EpubStructureGenerator,
        metadata: EpubStructureGenerator.EpubMetadata
    ) {
        sortedFolders.forEach { folder ->
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
        infoPage: File? = null
    ) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()
        println("Creating EPUB archive: ${outputFile.name}...")

        val epubGenerator = EpubStructureGenerator()
        val metadata = EpubStructureGenerator.EpubMetadata()
        val sortedFolders = sortChapterFolders(chapterFolders)

        try {
            ZipFile(outputFile).use { epubZip ->
                epubGenerator.addEpubCoreFiles(epubZip)
                infoPage?.let {
                    epubGenerator.addInfoPageToEpub(it, metadata, epubZip)
                }
                addChaptersToEpub(epubZip, sortedFolders, epubGenerator, metadata)
                epubGenerator.addEpubMetadataFiles(epubZip, mangaTitle, metadata)
            }
            println("Successfully created: ${outputFile.name}")
        } catch (e: ZipException) {
            logError("Failed to create EPUB file ${outputFile.name}", e)
        }
    }

    fun extractZip(zipFile: File, destination: File): Boolean {
        return try {
            logDebug { "Extracting ${zipFile.name} to ${destination.absolutePath}" }
            ZipFile(zipFile).extractAll(destination.absolutePath)
            true
        } catch (e: ZipException) {
            logError("Failed to extract zip file ${zipFile.name}", e)
            false
        }
    }

    fun processLocalFile(options: LocalFileOptions) {
        println("\nProcessing local file: ${options.inputFile.name}")

        val mangaTitle = options.customTitle ?: options.inputFile.nameWithoutExtension
        val outputFile = File(options.inputFile.parent, "$mangaTitle.${options.outputFormat}")

        if (shouldSkipProcessing(options, outputFile)) {
            return
        }

        var infoPageFile: File? = null
        if (options.generateInfoPage) {
            val chapterData = if (options.inputFile.extension.equals("epub", true)) {
                getChapterPageCountsFromEpub(options.inputFile)
            } else {
                getChapterPageCountsFromZip(options.inputFile)
            }
            val pageCount = chapterData.values.sum()

            infoPageFile = infoPageGeneratorService.create(
                InfoPageGeneratorService.InfoPageData(
                    title = mangaTitle,
                    sourceUrl = "Local File: ${options.inputFile.name}",
                    lastUpdated = null,
                    chapterCount = chapterData.size,
                    pageCount = pageCount,
                    tempDir = options.tempDirectory
                )
            )
        }

        val result = fileConverter.process(options, mangaTitle, outputFile, this, infoPageFile)
        infoPageFile?.delete()

        handlePostProcessing(options.inputFile, result, options.deleteOriginal, options.useTrueDangerousMode)
    }

    private fun shouldSkipProcessing(options: LocalFileOptions, outputFile: File): Boolean {
        var shouldSkip = false
        when {
            options.inputFile.extension.lowercase() !in setOf("cbz", "epub") -> {
                println("Error: Input file must be a .cbz or .epub file.")
                shouldSkip = true
            }
            options.inputFile.canonicalPath == outputFile.canonicalPath &&
                options.inputFile.extension.lowercase() == options.outputFormat.lowercase() -> {
                println("Re-processing file in place: ${outputFile.name}")
                // Do not skip, allow reprocessing
            }
            outputFile.exists() -> {
                if (options.skipIfTargetExists) {
                    println("Skipping ${options.inputFile.name}: Target ${outputFile.name} already exists.")
                    shouldSkip = true
                } else if (!options.forceOverwrite) {
                    val message = "Error: Output file ${outputFile.name} already exists. Use --force to overwrite."
                    println(message)
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
                println("Operation successful. Deleting original file: ${inputFile.name}")
                if (!inputFile.delete()) {
                    println("Warning: Failed to delete original file: ${inputFile.name}")
                }
            }
            !result.success && useTrueDangerousMode -> {
                println(
                    "DANGEROUS OPERATION FAILED. Source file ${inputFile.name} may be CORRUPT."
                )
            }
            result.error != null -> {
                println(result.error)
            }
        }
    }
}
