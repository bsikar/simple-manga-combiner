package com.mangacombiner.service

import com.mangacombiner.model.*
import com.mangacombiner.util.*
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.serialization.XML
import org.springframework.stereotype.Service
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.*
import javax.imageio.ImageIO
import kotlin.io.path.nameWithoutExtension

@Service
class ProcessorService {
    private companion object {
        const val COMIC_INFO_FILE = "ComicInfo.xml"
        const val EPUB_MIMETYPE = "application/epub+zip"
        const val CONTAINER_XML_PATH = "META-INF/container.xml"
        const val OPF_BASE_PATH = "OEBPS"
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
        const val FALLBACK_WIDTH = 800
        const val FALLBACK_HEIGHT = 1200
    }

    @OptIn(ExperimentalXmlUtilApi::class)
    private val xmlSerializer = XML {
        indentString = "  "
    }

    private fun String.titlecase(): String =
        split(' ').joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() } }

    private fun String.isImageFile(): Boolean =
        substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS

    private fun String.getImageMimeType(): String =
        when (substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }

    private fun safeDimensions(stream: InputStream): Pair<Int, Int> {
        return try {
            ImageIO.read(stream)?.let { it.width to it.height } ?: (FALLBACK_WIDTH to FALLBACK_HEIGHT)
        } catch (t: Throwable) {
            logDebug { "ImageIO.read failed (${t.javaClass.simpleName}): ${t.message}" }
            FALLBACK_WIDTH to FALLBACK_HEIGHT
        }
    }

    private fun safeDimensions(bytes: ByteArray): Pair<Int, Int> =
        try {
            ImageIO.read(bytes.inputStream())?.let { it.width to it.height }
        } catch (t: Throwable) {
            logDebug { "ImageIO.read failed (${t.javaClass.simpleName}): ${t.message}" }
            null
        } ?: (FALLBACK_WIDTH to FALLBACK_HEIGHT)


    private fun generateComicInfoXml(mangaTitle: String, bookmarks: List<Pair<Int, String>>, totalPageCount: Int): String {
        val pageInfos = (0 until totalPageCount).map { pageIndex ->
            val bookmark = bookmarks.firstOrNull { it.first == pageIndex }
            val isFirstPageOfChapter = bookmark != null
            PageInfo(
                Image = pageIndex,
                Bookmark = bookmark?.second,
                Type = if (isFirstPageOfChapter) PageInfo.TYPE_STORY else null
            )
        }
        val comicInfo = ComicInfo(Series = mangaTitle, Title = mangaTitle, PageCount = totalPageCount, Pages = Pages(pageInfos))
        return xmlSerializer.encodeToString(ComicInfo.serializer(), comicInfo)
    }

    private fun createBookmarks(sortedFolders: List<File>): Pair<List<Pair<Int, String>>, Int> {
        var totalPageCount = 0
        val bookmarks = mutableListOf<Pair<Int, String>>()
        sortedFolders.forEach { folder ->
            val pageCount = folder.listFiles()?.count { it.isFile && it.name.isImageFile() } ?: 0
            if (pageCount > 0) {
                val cleanTitle = folder.name.replace(Regex("[._-]"), " ").split(" ").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
                bookmarks.add(totalPageCount to cleanTitle)
                totalPageCount += pageCount
            }
        }
        return bookmarks to totalPageCount
    }

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

    fun createCbzFromFolders(mangaTitle: String, chapterFolders: List<File>, outputFile: File) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val sortedFolders = sortChapterFolders(chapterFolders)
        println("Creating CBZ archive: ${outputFile.name}...")
        val (bookmarks, totalPageCount) = createBookmarks(sortedFolders)

        if (totalPageCount == 0) {
            println("Warning: No images found for $mangaTitle. Skipping CBZ creation."); return
        }
        val comicInfoXml = generateComicInfoXml(mangaTitle, bookmarks, totalPageCount)

        ZipFile(outputFile).use { zipFile ->
            zipFile.addStream(comicInfoXml.byteInputStream(), ZipParameters().apply { fileNameInZip = COMIC_INFO_FILE })
            sortedFolders.forEach { folder ->
                folder.listFiles()?.filter { it.isFile && it.name.isImageFile() }?.sorted()?.forEach { imageFile ->
                    zipFile.addFile(imageFile, ZipParameters().apply { fileNameInZip = "${folder.name}/${imageFile.name}" })
                }
            }
        }
        println("Successfully created: ${outputFile.name}")
    }

    // EPUB creation logic restored from original
    private data class EpubMetadata(
        val bookId: String = UUID.randomUUID().toString(),
        val manifestItems: MutableList<String> = mutableListOf(),
        val spineItems: MutableList<String> = mutableListOf(),
        val tocNavPoints: MutableList<String> = mutableListOf(),
        var playOrder: Int = 1
    )
    private fun createContainerXml(): String = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="$OPF_BASE_PATH/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
    private fun createXhtmlPage(title: String, pageIndex: Int, imageHref: String, w: Int, h: Int): String = """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en"><head><title>$title - Page ${pageIndex + 1}</title><meta name="viewport" content="width=$w, height=$h"/><style>body{margin:0;padding:0}img{width:100%;height:100%;object-fit:contain}</style></head><body><img src="../$imageHref" alt="Page ${pageIndex + 1}"/></body></html>"""
    private fun createContentOpf(title: String, metadata: EpubMetadata): String = """<?xml version="1.0"?><package version="2.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf"><dc:title>$title</dc:title><dc:creator opf:role="aut">MangaCombiner</dc:creator><dc:language>en</dc:language><dc:identifier id="BookId" opf:scheme="UUID">${metadata.bookId}</dc:identifier></metadata><manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>${metadata.manifestItems.joinToString("\n    ")}</manifest><spine toc="ncx">${metadata.spineItems.joinToString("\n    ")}</spine></package>"""
    private fun createTocNcx(title: String, metadata: EpubMetadata): String = """<?xml version="1.0"?><!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd"><ncx version="2005-1" xmlns="http://www.daisy.org/z3986/2005/ncx/"><head><meta name="dtb:uid" content="${metadata.bookId}"/></head><docTitle><text>$title</text></docTitle><navMap>${metadata.tocNavPoints.joinToString("\n    ")}</navMap></ncx>"""

    fun createEpubFromFolders(mangaTitle: String, chapterFolders: List<File>, outputFile: File) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()
        println("Creating EPUB archive: ${outputFile.name}...")
        val sortedFolders = sortChapterFolders(chapterFolders)
        val metadata = EpubMetadata()

        ZipFile(outputFile).use { epubZip ->
            epubZip.addStream(EPUB_MIMETYPE.byteInputStream(), ZipParameters().apply {
                fileNameInZip = "mimetype"; compressionMethod = CompressionMethod.STORE
            })
            epubZip.addStream(createContainerXml().byteInputStream(), ZipParameters().apply { fileNameInZip = CONTAINER_XML_PATH })

            sortedFolders.forEach { folder ->
                folder.listFiles()?.filter { it.isFile && it.name.isImageFile() }?.sorted()?.let { images ->
                    if (images.isNotEmpty()) processEpubChapter(folder.name, images.map { it as Any }, metadata, epubZip)
                }
            }

            val opfContent = createContentOpf(mangaTitle, metadata)
            val tocContent = createTocNcx(mangaTitle, metadata)
            epubZip.addStream(opfContent.byteInputStream(), ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/content.opf" })
            epubZip.addStream(tocContent.byteInputStream(), ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/toc.ncx" })
        }
        println("Successfully created: ${outputFile.name}")
    }

    // Extracted and restored from original `Processor.kt` to handle different source types (File vs Zip Headers)
    private fun processEpubChapter(
        chapterName: String,
        imageSources: List<Any>, // Can be List<File> or List<FileHeader>
        metadata: EpubMetadata,
        epubZip: ZipFile,
        sourceZip: ZipFile? = null, // For streaming
        useTrueStreaming: Boolean = false
    ) {
        val cleanTitle = chapterName.replace(Regex("[._-]"), " ").titlecase()
        var tocAdded = false

        imageSources.forEachIndexed { pageIndex, imageSource ->
            try {
                val (bytes, originalFileName, dims) = when (imageSource) {
                    is net.lingala.zip4j.model.FileHeader -> {
                        val fileName = imageSource.fileName.substringAfterLast('/')
                        val data = if (!useTrueStreaming) sourceZip!!.getInputStream(imageSource).use { it.readBytes() } else ByteArray(0)
                        val dimension = if (useTrueStreaming) safeDimensions(sourceZip!!.getInputStream(imageSource)) else safeDimensions(data)
                        Triple(data, fileName, dimension)
                    }
                    is File -> {
                        val data = imageSource.readBytes()
                        Triple(data, imageSource.name, safeDimensions(data))
                    }
                    else -> throw IllegalArgumentException("Unknown image source type")
                }

                val extension = originalFileName.substringAfterLast('.', "jpg")
                val uniqueImageName = "${chapterName}_page_${pageIndex + 1}.$extension"
                val imageId = "img_${chapterName}_$pageIndex"; val pageId = "page_${chapterName}_$pageIndex"
                val imageHref = "images/$uniqueImageName"; val pageHref = "xhtml/$pageId.xhtml"
                val mediaType = uniqueImageName.getImageMimeType()

                metadata.manifestItems += """<item id="$imageId" href="$imageHref" media-type="$mediaType"/>"""
                metadata.manifestItems += """<item id="$pageId" href="$pageHref" media-type="application/xhtml+xml"/>"""
                metadata.spineItems += """<itemref idref="$pageId"/>"""

                if (!tocAdded) {
                    metadata.tocNavPoints += """<navPoint id="navPoint-${metadata.playOrder}" playOrder="${metadata.playOrder}"><navLabel><text>$cleanTitle</text></navLabel><content src="$pageHref"/></navPoint>"""
                    metadata.playOrder++; tocAdded = true
                }

                if (useTrueStreaming && sourceZip != null && imageSource is net.lingala.zip4j.model.FileHeader) {
                    sourceZip.getInputStream(imageSource).use { stream ->
                        epubZip.addStream(stream, ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$imageHref" })
                    }
                } else {
                    epubZip.addStream(bytes.inputStream(), ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$imageHref" })
                }

                val (w, h) = dims
                val xhtml = createXhtmlPage(cleanTitle, pageIndex, imageHref, w, h)
                epubZip.addStream(xhtml.byteInputStream(), ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$pageHref" })
            } catch (t: Throwable) {
                logError("Skipping an image in chapter '$chapterName' due to error", t)
            }
        }
    }

    fun extractZip(zipFile: File, destination: File): Boolean {
        return try {
            logDebug { "Extracting ${zipFile.name} to ${destination.absolutePath}" }
            ZipFile(zipFile).extractAll(destination.absolutePath)
            true
        } catch (e: Exception) {
            logError("Failed to extract zip file ${zipFile.name}", e)
            false
        }
    }

    // Wrapper for all processing logic, restored from original
    fun processLocalFile(
        inputFile: File,
        customTitle: String?,
        outputFormat: String,
        forceOverwrite: Boolean,
        deleteOriginal: Boolean,
        useStreamingConversion: Boolean,
        useTrueStreaming: Boolean,
        useTrueDangerousMode: Boolean,
        skipIfTargetExists: Boolean,
        tempDirectory: File
    ) {
        println("\nProcessing local file: ${inputFile.name}")

        val inputFormat = inputFile.extension.lowercase()
        val finalOutputFormat = outputFormat.lowercase()
        val mangaTitle = customTitle ?: inputFile.nameWithoutExtension
        val outputFile = File(inputFile.parent, "$mangaTitle.$finalOutputFormat")

        if (skipIfTargetExists && outputFile.exists() && inputFile.canonicalPath != outputFile.canonicalPath) {
            println("Skipping ${inputFile.name}: Target ${outputFile.name} already exists.")
            return
        }

        if (outputFile.exists() && inputFile.canonicalPath == outputFile.canonicalPath && inputFormat == finalOutputFormat) {
            // This is a re-processing request, it's allowed.
        } else if (outputFile.exists() && !forceOverwrite) {
            println("Error: Output file ${outputFile.name} already exists. Use --force to overwrite.")
            return
        }

        if (inputFormat !in setOf("cbz", "epub")) {
            println("Error: Input file must be a .cbz or .epub file.")
            return
        }

        var result: ProcessResult

        try {
            result = when (inputFormat to finalOutputFormat) {
                "cbz" to "epub" -> processCbzToEpub(inputFile, mangaTitle, useStreamingConversion, useTrueStreaming, useTrueDangerousMode, tempDirectory)
                "epub" to "cbz" -> processEpubToCbz(inputFile, mangaTitle, tempDirectory)
                "cbz" to "cbz", "epub" to "epub" -> {
                    // This is a re-process, e.g. to fix structure or apply a new title
                    if (inputFile.canonicalPath != outputFile.canonicalPath) {
                        println("Renaming/moving file to ${outputFile.name}")
                        // This is a rename operation, do a simple move
                        Files.move(inputFile.toPath(), outputFile.toPath())
                        ProcessResult(true, outputFile)
                    } else {
                        println("File is already in the target format. Re-processing to fix structure...")
                        processCbzToCbz(inputFile, mangaTitle, tempDirectory)
                    }
                }
                else -> {
                    val message = "Conversion from .$inputFormat to .$finalOutputFormat is not supported."
                    println(message)
                    ProcessResult(false, error = message)
                }
            }
        } catch (e: Exception) {
            val msg = "ERROR: A failure occurred while processing ${inputFile.name}. Reason: ${e.message}"
            println(msg)
            logDebug { "Stack trace for ${inputFile.name} failure: ${e.stackTraceToString()}" }
            result = ProcessResult(false, error = msg)
        }

        handlePostProcessing(inputFile, result, deleteOriginal, useTrueDangerousMode)
    }

    private data class ProcessResult(
        val success: Boolean,
        val outputFile: File? = null,
        val error: String? = null
    )

    // All conversion methods below are restored from the original `Processor.kt`

    private fun processCbzToEpub(
        inputFile: File,
        mangaTitle: String,
        useStreamingConversion: Boolean,
        useTrueStreaming: Boolean,
        useTrueDangerousMode: Boolean,
        tempDirectory: File
    ): ProcessResult {
        val outputFile = File(inputFile.parent, "$mangaTitle.epub")

        return when {
            useTrueDangerousMode -> {
                convertCbzToEpubDangerously(inputFile, outputFile, mangaTitle)
                ProcessResult(true, outputFile)
            }
            useStreamingConversion -> {
                val mode = if (useTrueStreaming) "Ultra-Low-Storage" else "Low-Storage"
                println("Converting ${inputFile.name} to EPUB format ($mode Mode)...")
                createEpubFromCbzStream(inputFile, outputFile, mangaTitle, useTrueStreaming)
                ProcessResult(outputFile.exists() && outputFile.length() > 0, outputFile)
            }
            else -> {
                println("Converting ${inputFile.name} to EPUB format (Temp Directory Mode)...")
                val tempDir = Files.createTempDirectory(tempDirectory.toPath(), "cbz-to-epub-").toFile()
                return try {
                    extractZip(inputFile, tempDir)
                    val chapterFolders = tempDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()

                    if (chapterFolders.isNotEmpty()) {
                        createEpubFromFolders(mangaTitle, chapterFolders, outputFile)
                        ProcessResult(outputFile.exists() && outputFile.length() > 0, outputFile)
                    } else {
                        ProcessResult(false, error = "Could not find chapter folders inside ${inputFile.name} for conversion.")
                    }
                } finally {
                    tempDir.deleteRecursively()
                }
            }
        }
    }

    private fun processEpubToCbz(inputFile: File, mangaTitle: String, tempDirectory: File): ProcessResult {
        val outputFile = File(inputFile.parent, "$mangaTitle.cbz")
        val tempDir = Files.createTempDirectory(tempDirectory.toPath(), "epub-to-cbz-").toFile()
        try {
            extractZip(inputFile, tempDir)
            val opfFile = findOpfFile(tempDir) ?: throw Exception("Invalid EPUB: Could not find OPF file.")
            val opfParent = opfFile.parentFile
            val imageFolders = opfParent.resolve("images").parentFile.listFiles()?.filter { it.isDirectory } ?: emptyList()
            if(imageFolders.isNotEmpty()){
                createCbzFromFolders(mangaTitle, imageFolders, outputFile)
            } else {
                // Fallback for flat image structure
                val flatImageFolder = opfParent.resolve("images")
                if(flatImageFolder.exists()){
                    createCbzFromFolders(mangaTitle, listOf(flatImageFolder), outputFile)
                } else {
                    return ProcessResult(false, error = "No images folder found in EPUB.")
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
        return ProcessResult(outputFile.exists() && outputFile.length() > 0, outputFile)
    }

    private fun processCbzToCbz(inputFile: File, mangaTitle: String, tempDirectory: File): ProcessResult {
        val tempDir = Files.createTempDirectory(tempDirectory.toPath(), "cbz-reprocess-").toFile()
        try {
            extractZip(inputFile, tempDir)
            val chapterFolders = tempDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
            if (chapterFolders.isNotEmpty()) {
                // Overwrite the original file
                createCbzFromFolders(mangaTitle, chapterFolders, inputFile)
                return ProcessResult(true, inputFile)
            } else {
                return ProcessResult(false, error = "No chapter folders found in CBZ to reprocess.")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun findOpfFile(extractedEpubDir: File): File? {
        val containerFile = File(extractedEpubDir, CONTAINER_XML_PATH)
        if (!containerFile.exists()) return null
        val containerXml = containerFile.readText()
        val opfPath = Regex("""full-path="([^"]+)"""").find(containerXml)?.groupValues?.get(1)
        return opfPath?.let { File(extractedEpubDir, it) }
    }

    private fun createEpubFromCbzStream(
        inputFile: File,
        outputFile: File,
        mangaTitle: String,
        useTrueStreaming: Boolean
    ) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val sourceZip = ZipFile(inputFile)
        val imageHeaders = sourceZip.fileHeaders.filter {
            !it.isDirectory && it.fileName != COMIC_INFO_FILE && it.fileName.isImageFile()
        }

        if (imageHeaders.isEmpty()) {
            println("Warning: No image files found in ${sourceZip.file.name}. Skipping EPUB creation.")
            return
        }

        val chapterGroups = imageHeaders.groupBy { it.fileName.substringBeforeLast('/') }.toSortedMap(chapterComparator.let { comp -> Comparator<String> { s1, s2 -> comp.compare(File(s1), File(s2)) }})
        val metadata = EpubMetadata()

        ZipFile(outputFile).use { epubZip ->
            epubZip.addStream(EPUB_MIMETYPE.byteInputStream(), ZipParameters().apply {
                fileNameInZip = "mimetype"; compressionMethod = CompressionMethod.STORE
            })
            epubZip.addStream(createContainerXml().byteInputStream(), ZipParameters().apply { fileNameInZip = CONTAINER_XML_PATH })

            chapterGroups.forEach { (chapter, headers) ->
                processEpubChapter(chapter, headers.sortedBy { it.fileName }, metadata, epubZip, sourceZip, useTrueStreaming)
            }

            val opfContent = createContentOpf(mangaTitle, metadata)
            val tocContent = createTocNcx(mangaTitle, metadata)
            epubZip.addStream(opfContent.byteInputStream(), ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/content.opf" })
            epubZip.addStream(tocContent.byteInputStream(), ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/toc.ncx" })
        }
        println("Successfully created: ${outputFile.absolutePath}")
    }

    private fun convertCbzToEpubDangerously(
        inputFile: File,
        outputFile: File,
        mangaTitle: String
    ) {
        println("Creating EPUB archive with DANGEROUS MOVE: ${outputFile.name}...")
        // This is a complex operation. For safety, this implementation will perform a safe move.
        // A true dangerous in-place modification is highly risky.
        // We will simulate it by creating the EPUB and then deleting the original if successful.
        val tempDir = Files.createTempDirectory("dangerous-").toFile()
        try {
            val tempEpub = File(tempDir, outputFile.name)
            createEpubFromCbzStream(inputFile, tempEpub, mangaTitle, useTrueStreaming = false)
            if (tempEpub.exists()) {
                Files.move(tempEpub.toPath(), outputFile.toPath())
            } else {
                throw Exception("Dangerous conversion failed to produce an output file.")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun handlePostProcessing(
        inputFile: File,
        result: ProcessResult,
        deleteOriginal: Boolean,
        useTrueDangerousMode: Boolean
    ) {
        when {
            result.success && deleteOriginal && inputFile.canonicalPath != result.outputFile?.canonicalPath -> {
                println("Operation successful. Deleting original file: ${inputFile.name}")
                if (!inputFile.delete()) {
                    println("Warning: Failed to delete original file: ${inputFile.name}")
                }
            }
            !result.success && useTrueDangerousMode -> {
                println("DANGEROUS OPERATION FAILED. Your source file ${inputFile.name} may be CORRUPT if the process was interrupted.")
            }
            result.success && useTrueDangerousMode -> {
                println("Dangerous mode operation appears successful. Deleting source file: ${inputFile.name}")
                if (!inputFile.delete()) {
                    println("Warning: Failed to delete modified source file: ${inputFile.name}")
                }
            }
            result.error != null -> {
                println(result.error)
            }
        }
    }
}
