package com.mangacombiner.core

import com.mangacombiner.model.*
import com.mangacombiner.util.logDebug
import kotlinx.coroutines.*
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.serialization.XmlConfig
import nl.adaptivity.xmlutil.serialization.XML
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import javax.imageio.ImageIO
import kotlin.io.path.nameWithoutExtension

/**
 * Core processor for manga file operations including conversion, creation, and synchronization.
 */
object Processor {

    private const val COMIC_INFO_FILE = "ComicInfo.xml"
    private const val EPUB_MIMETYPE = "application/epub+zip"
    private const val CONTAINER_XML_PATH = "META-INF/container.xml"
    private const val OPF_BASE_PATH = "OEBPS"
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

    private const val FALLBACK_WIDTH = 800
    private const val FALLBACK_HEIGHT = 1200

    @OptIn(ExperimentalXmlUtilApi::class)
    private val xmlSerializer = XML {
        indentString = "  "
        defaultPolicy {
            unknownChildHandler = XmlConfig.IGNORING_UNKNOWN_CHILD_HANDLER
        }
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

    private fun safeDimensions(bytes: ByteArray): Pair<Int, Int> =
        try {
            ImageIO.read(bytes.inputStream())?.let { it.width to it.height }
        } catch (t: Throwable) {
            logDebug { "ImageIO.read failed (${t.javaClass.simpleName}): ${t.message}" }
            null
        } ?: (FALLBACK_WIDTH to FALLBACK_HEIGHT)

    private fun safeDimensions(stream: InputStream): Pair<Int, Int> =
        try {
            ImageIO.read(stream)?.let { it.width to it.height }
        } catch (t: Throwable) {
            logDebug { "ImageIO.read failed (${t.javaClass.simpleName}): ${t.message}" }
            null
        } ?: (FALLBACK_WIDTH to FALLBACK_HEIGHT)

    private fun generateComicInfoXml(
        mangaTitle: String,
        bookmarks: List<Pair<Int, String>>,
        totalPageCount: Int
    ): String {
        val pageInfos = (0 until totalPageCount).map { pageIndex ->
            val bookmark = bookmarks.firstOrNull { it.first == pageIndex }
            val isFirstPage = pageIndex == bookmarks.firstOrNull()?.first

            PageInfo(
                Image = pageIndex,
                Bookmark = bookmark?.second,
                Type = when {
                    bookmark != null && isFirstPage -> PageInfo.TYPE_FRONT_COVER
                    bookmark != null -> PageInfo.TYPE_STORY
                    else -> null
                }
            )
        }

        val comicInfo = ComicInfo(
            Series = mangaTitle,
            Title = mangaTitle,
            PageCount = totalPageCount,
            Pages = Pages(pageInfos)
        )

        return xmlSerializer.encodeToString(ComicInfo.serializer(), comicInfo)
    }

    private fun createBookmarks(sortedFolders: List<File>): Pair<List<Pair<Int, String>>, Int> {
        var totalPageCount = 0
        val bookmarks = mutableListOf<Pair<Int, String>>()
        sortedFolders.forEach { folder ->
            val pageCount = folder.listFiles()?.count { it.isFile } ?: 0
            val cleanTitle = folder.name.replace('-', ' ').replace('_', ' ').titlecase()
            bookmarks.add(totalPageCount to cleanTitle)
            totalPageCount += pageCount
        }
        return bookmarks to totalPageCount
    }

    private fun sortChapterFolders(folders: List<File>): List<File> =
        folders.sortedBy { it.name.filter { ch -> ch.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }

    fun createCbzFromFolders(mangaTitle: String, chapterFolders: List<File>, outputFile: File) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        println("Creating CBZ archive: ${outputFile.name}...")

        val sortedFolders = sortChapterFolders(chapterFolders)
        val (bookmarks, totalPageCount) = createBookmarks(sortedFolders)

        if (totalPageCount == 0) {
            println("Warning: No images found for $mangaTitle. Skipping CBZ creation.")
            return
        }

        val comicInfoXml = generateComicInfoXml(mangaTitle, bookmarks, totalPageCount)

        ZipFile(outputFile).use { zipFile ->
            zipFile.addStream(
                comicInfoXml.byteInputStream(),
                ZipParameters().apply { fileNameInZip = COMIC_INFO_FILE }
            )

            sortedFolders.forEach { folder ->
                folder.listFiles()
                    ?.filter { it.isFile }
                    ?.sorted()
                    ?.forEach { imageFile ->
                        zipFile.addFile(
                            imageFile,
                            ZipParameters().apply { fileNameInZip = "${folder.name}/${imageFile.name}" }
                        )
                    }
            }
        }

        println("Successfully created: ${outputFile.absolutePath}")
    }

    private data class EpubMetadata(
        val bookId: String = UUID.randomUUID().toString(),
        val manifestItems: MutableList<String> = mutableListOf(),
        val spineItems: MutableList<String> = mutableListOf(),
        val tocNavPoints: MutableList<String> = mutableListOf(),
        var playOrder: Int = 1
    )

    private fun createContainerXml(): String = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="$OPF_BASE_PATH/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""".trimIndent()

    private fun createXhtmlPage(
        chapterTitle: String,
        pageIndex: Int,
        imageHref: String,
        width: Int,
        height: Int
    ): String = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en">
<head>
  <title>$chapterTitle - Page ${pageIndex + 1}</title>
  <meta name="viewport" content="width=$width, height=$height"/>
  <style>body{margin:0;padding:0}img{width:100%;height:100%;object-fit:contain}</style>
</head><body>
  <img src="../$imageHref" alt="Page ${pageIndex + 1}"/>
</body></html>""".trimIndent()

    private fun createContentOpf(mangaTitle: String, metadata: EpubMetadata): String = """<?xml version="1.0"?>
<package version="2.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>$mangaTitle</dc:title>
    <dc:creator opf:role="aut">MangaCombiner</dc:creator>
    <dc:language>en</dc:language>
    <dc:identifier id="BookId" opf:scheme="UUID">${metadata.bookId}</dc:identifier>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    ${metadata.manifestItems.joinToString("\n    ")}
  </manifest>
  <spine toc="ncx">
    ${metadata.spineItems.joinToString("\n    ")}
  </spine>
</package>""".trimIndent()

    private fun createTocNcx(mangaTitle: String, metadata: EpubMetadata): String = """<?xml version="1.0"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx version="2005-1" xmlns="http://www.daisy.org/z3986/2005/ncx/">
  <head>
    <meta name="dtb:uid" content="${metadata.bookId}"/>
  </head>
  <docTitle>
    <text>$mangaTitle</text>
  </docTitle>
  <navMap>
    ${metadata.tocNavPoints.joinToString("\n    ")}
  </navMap>
</ncx>""".trimIndent()

    private fun processEpubChapter(
        chapterName: String,
        imageHeaders: List<Any>,
        metadata: EpubMetadata,
        epubZip: ZipFile,
        sourceZip: ZipFile? = null,
        useTrueStreaming: Boolean = false
    ) {
        val cleanTitle = chapterName.replace('-', ' ').replace('_', ' ').titlecase()
        var tocAdded = false

        imageHeaders.forEachIndexed { pageIndex, imageSource ->
            try {
                val (bytes, originalFileName, dims) = when (imageSource) {
                    is net.lingala.zip4j.model.FileHeader -> {
                        val fileName = imageSource.fileName.substringAfterLast('/')
                        val dimension = if (useTrueStreaming)
                            safeDimensions(sourceZip!!.getInputStream(imageSource))
                        else {
                            val data = sourceZip!!.getInputStream(imageSource).use { it.readBytes() }
                            safeDimensions(data)
                        }
                        val data = if (useTrueStreaming) ByteArray(0)
                        else sourceZip!!.getInputStream(imageSource).use { it.readBytes() }
                        Triple(data, fileName, dimension)
                    }
                    is File -> {
                        val data = imageSource.readBytes()
                        Triple(data, imageSource.name, safeDimensions(data))
                    }
                    else -> throw IllegalArgumentException("Unknown image source type")
                }

                val imageId = "img_${chapterName}_$pageIndex"
                val pageId = "page_${chapterName}_$pageIndex"
                val imageHref = "images/$originalFileName"
                val pageHref = "xhtml/$pageId.xhtml"
                val mediaType = originalFileName.getImageMimeType()

                metadata.manifestItems += """<item id="$imageId" href="$imageHref" media-type="$mediaType"/>"""
                metadata.manifestItems += """<item id="$pageId" href="$pageHref" media-type="application/xhtml+xml"/>"""
                metadata.spineItems += """<itemref idref="$pageId"/>"""

                if (!tocAdded) {
                    metadata.tocNavPoints += """<navPoint id="navPoint-${metadata.playOrder}" playOrder="${metadata.playOrder}">
      <navLabel><text>$cleanTitle</text></navLabel>
      <content src="$pageHref"/>
    </navPoint>"""
                    metadata.playOrder++
                    tocAdded = true
                }

                if (useTrueStreaming && sourceZip != null && imageSource is net.lingala.zip4j.model.FileHeader) {
                    sourceZip.getInputStream(imageSource).use { stream ->
                        epubZip.addStream(
                            stream,
                            ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$imageHref" }
                        )
                    }
                } else {
                    epubZip.addStream(
                        bytes.inputStream(),
                        ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$imageHref" }
                    )
                }

                val (w, h) = dims
                val xhtml = createXhtmlPage(cleanTitle, pageIndex, imageHref, w, h)
                epubZip.addStream(
                    xhtml.byteInputStream(),
                    ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$pageHref" }
                )
            } catch (t: Throwable) {
                val name = when (imageSource) {
                    is net.lingala.zip4j.model.FileHeader -> imageSource.fileName
                    is File -> imageSource.path
                    else -> "unknown"
                }
                println("Warning: Skipping '$name': ${t.message}")
                logDebug { "Stack trace: ${t.stackTraceToString()}" }
            }
        }
    }

    private fun createEpubFromCbzStream(
        zipFile: ZipFile,
        outputFile: File,
        mangaTitle: String,
        useTrueStreaming: Boolean
    ) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val imageHeaders = zipFile.fileHeaders.filter {
            !it.isDirectory && it.fileName != COMIC_INFO_FILE && it.fileName.isImageFile()
        }

        if (imageHeaders.isEmpty()) {
            println("Warning: No image files found in ${zipFile.file.name}. Skipping EPUB creation.")
            return
        }

        println("Creating EPUB archive: ${outputFile.name}...")

        val chapterGroups = imageHeaders.groupBy { it.fileName.substringBeforeLast('/') }.toSortedMap()
        val metadata = EpubMetadata()

        ZipFile(outputFile).use { epubZip ->
            // Step 1: Add mandatory 'mimetype' file (uncompressed)
            epubZip.addStream(
                EPUB_MIMETYPE.byteInputStream(),
                ZipParameters().apply {
                    fileNameInZip = "mimetype"
                    compressionMethod = CompressionMethod.STORE
                }
            )

            // Step 2: Add container.xml
            epubZip.addStream(
                createContainerXml().byteInputStream(),
                ZipParameters().apply { fileNameInZip = CONTAINER_XML_PATH }
            )

            // Step 3: Add chapter content
            chapterGroups.forEach { (chapter, headers) ->
                processEpubChapter(chapter, headers.sortedBy { it.fileName }, metadata, epubZip, zipFile, useTrueStreaming)
            }

            // Step 4: Add OPF and NCX — always included even if no chapters
            val opfContent = createContentOpf(mangaTitle, metadata)
            val tocContent = createTocNcx(mangaTitle, metadata)

            epubZip.addStream(
                opfContent.byteInputStream(),
                ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/content.opf" }
            )

            epubZip.addStream(
                tocContent.byteInputStream(),
                ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/toc.ncx" }
            )
        }

        println("Successfully created: ${outputFile.absolutePath}")
    }

    fun createEpubFromFolders(mangaTitle: String, chapterFolders: List<File>, outputFile: File) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        println("Creating EPUB archive: ${outputFile.name}...")

        val sortedFolders = sortChapterFolders(chapterFolders)
        val metadata = EpubMetadata()

        ZipFile(outputFile).use { epubZip ->
            // Step 1: Add mimetype
            epubZip.addStream(
                EPUB_MIMETYPE.byteInputStream(),
                ZipParameters().apply {
                    fileNameInZip = "mimetype"
                    compressionMethod = CompressionMethod.STORE
                }
            )

            // Step 2: Add container.xml
            epubZip.addStream(
                createContainerXml().byteInputStream(),
                ZipParameters().apply { fileNameInZip = CONTAINER_XML_PATH }
            )

            // Step 3: Add chapters
            sortedFolders.forEach { folder ->
                val images = folder.listFiles()?.filter { it.isFile }?.sorted() ?: return@forEach
                if (images.isNotEmpty()) {
                    processEpubChapter(folder.name, images, metadata, epubZip)
                }
            }

            // Step 4: Add OPF and NCX — even if no pages
            val opfContent = createContentOpf(mangaTitle, metadata)
            val tocContent = createTocNcx(mangaTitle, metadata)

            epubZip.addStream(
                opfContent.byteInputStream(),
                ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/content.opf" }
            )

            epubZip.addStream(
                tocContent.byteInputStream(),
                ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/toc.ncx" }
            )
        }

        println("Successfully created: ${outputFile.absolutePath}")
    }

    private fun convertCbzToEpubDangerously(
        sourceZip: ZipFile,
        outputFile: File,
        mangaTitle: String
    ) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val headers = sourceZip.fileHeaders.filter { !it.isDirectory && it.fileName != COMIC_INFO_FILE }
        if (headers.isEmpty()) {
            println("Warning: No image files found in ${sourceZip.file.name}. Skipping EPUB creation.")
            return
        }

        println("Creating EPUB archive with DANGEROUS MOVE: ${outputFile.name}...")

        ZipFile(outputFile).use { epubZip ->
            epubZip.addStream(
                EPUB_MIMETYPE.byteInputStream(),
                ZipParameters().apply {
                    fileNameInZip = "mimetype"
                    compressionMethod = CompressionMethod.STORE
                }
            )

            epubZip.addStream(
                createContainerXml().byteInputStream(),
                ZipParameters().apply { fileNameInZip = CONTAINER_XML_PATH }
            )

            val chapterGroups = headers.groupBy { it.fileName.substringBeforeLast('/') }.toSortedMap()
            val metadata = EpubMetadata()
            val allHeaders = chapterGroups.values.flatten().sortedBy { it.fileName }

            allHeaders.forEachIndexed { pageIndex, imageHeader ->
                println("--> Moving image (${pageIndex + 1}/${allHeaders.size}): ${imageHeader.fileName}")
                val imageBytes = sourceZip.getInputStream(imageHeader).use { it.readBytes() }
                val (imgW, imgH) = safeDimensions(imageBytes)
                val originalFileName = imageHeader.fileName.substringAfterLast('/')

                epubZip.addStream(
                    imageBytes.inputStream(),
                    ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/images/$originalFileName" }
                )
                sourceZip.removeFile(imageHeader)

                val chapterName = imageHeader.fileName.substringBeforeLast('/')
                val cleanChapterTitle = chapterName.replace('-', ' ').replace('_', ' ').titlecase()
                val imageId = "img_${chapterName}_$pageIndex"
                val pageId = "page_${chapterName}_$pageIndex"
                val imageHref = "images/$originalFileName"
                val pageHref = "xhtml/$pageId.xhtml"
                val mediaType = originalFileName.getImageMimeType()

                metadata.manifestItems += """<item id="$imageId" href="$imageHref" media-type="$mediaType"/>"""
                metadata.manifestItems += """<item id="$pageId" href="$pageHref" media-type="application/xhtml+xml"/>"""
                metadata.spineItems += """<itemref idref="$pageId"/>"""

                if (chapterGroups[chapterName]?.sortedBy { it.fileName }?.first() == imageHeader) {
                    metadata.tocNavPoints += """<navPoint id="navPoint-${metadata.playOrder}" playOrder="${metadata.playOrder}">
      <navLabel><text>$cleanChapterTitle</text></navLabel>
      <content src="$pageHref"/>
    </navPoint>"""
                    metadata.playOrder++
                }

                val xhtml = createXhtmlPage(cleanChapterTitle, pageIndex, imageHref, imgW, imgH)
                epubZip.addStream(
                    xhtml.byteInputStream(),
                    ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$pageHref" }
                )
            }

            epubZip.addStream(
                createContentOpf(mangaTitle, metadata).byteInputStream(),
                ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/content.opf" }
            )

            epubZip.addStream(
                createTocNcx(mangaTitle, metadata).byteInputStream(),
                ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/toc.ncx" }
            )
        }

        println("DANGEROUS MOVE complete. Original file has been modified and is likely empty of images.")
    }

    private fun convertEpubToCbz(inputFile: File, outputFile: File, mangaTitle: String) {
        println("Converting EPUB ${inputFile.name} to CBZ format...")
        val tempDir = Files.createTempDirectory("epub-to-cbz-").toFile()
        try {
            ZipFile(inputFile).use { sourceZip ->
                val containerHeader = sourceZip.getFileHeader(CONTAINER_XML_PATH)
                    ?: throw Exception("Invalid EPUB: missing container.xml")
                val containerXml = sourceZip.getInputStream(containerHeader).reader().readText()
                val opfPath = Regex("""full-path="([^"]+)"""").find(containerXml)?.groupValues?.get(1)
                    ?: throw Exception("Could not find content.opf path in container.xml")
                val opfText = sourceZip.getInputStream(sourceZip.getFileHeader(opfPath)).reader().readText()
                val opfPackage = xmlSerializer.decodeFromString(OpfPackage.serializer(), opfText)

                val imageItems = opfPackage.manifest.items.filter { it.mediaType.startsWith("image/") }
                val opfBase = Paths.get(opfPath).parent ?: Paths.get("")

                imageItems.forEach { item ->
                    val pathInZip = opfBase.resolve(item.href).normalize().toString().replace(File.separatorChar, '/')
                    val header = sourceZip.getFileHeader(pathInZip)
                    if (header != null) {
                        val parts = pathInZip.split('/')
                        val chapterDir = parts.drop(1).dropLast(1).joinToString(File.separator)
                        val finalDir = File(tempDir, chapterDir)
                        finalDir.mkdirs()
                        sourceZip.extractFile(header, finalDir.path, header.fileName.substringAfterLast('/'))
                    }
                }
            }

            val folders = tempDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            if (folders.isNotEmpty()) createCbzFromFolders(mangaTitle, folders, outputFile)
            else println("No chapters found in EPUB.")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private data class ProcessResult(
        val success: Boolean,
        val outputFile: File? = null,
        val error: String? = null
    )

    fun processLocalFile(
        inputFile: File,
        customTitle: String?,
        forceOverwrite: Boolean,
        outputFormat: String,
        deleteOriginal: Boolean,
        useStreamingConversion: Boolean,
        useTrueStreaming: Boolean,
        useTrueDangerousMode: Boolean,
        skipIfTargetExists: Boolean
    ) {
        println("\nProcessing local file: ${inputFile.name}")

        val inputFormat = inputFile.extension.lowercase()
        val finalOutputFormat = outputFormat.lowercase()
        val mangaTitle = customTitle ?: inputFile.nameWithoutExtension
        val outputFile = File(inputFile.parent, "$mangaTitle.$finalOutputFormat")

        if (skipIfTargetExists && outputFile.exists()) {
            println("Skipping ${inputFile.name}: ${outputFile.name} already exists.")
            return
        }

        if (inputFormat !in setOf("cbz", "epub")) {
            println("Error: Input file must be a .cbz or .epub file.")
            return
        }

        var result = ProcessResult(false)

        try {
            result = when (inputFormat to finalOutputFormat) {
                "cbz" to "epub" -> processCbzToEpub(inputFile, mangaTitle, useStreamingConversion, useTrueStreaming, useTrueDangerousMode)
                "epub" to "cbz" -> processEpubToCbz(inputFile, mangaTitle, useStreamingConversion, useTrueStreaming, useTrueDangerousMode)
                "cbz" to "cbz" -> {
                    if (deleteOriginal) println("Note: --delete-original is ignored when the source and destination formats are the same.")
                    println("Updating metadata for: ${inputFile.name}")
                    ProcessResult(true, inputFile)
                }
                else -> {
                    val message = if (inputFormat == finalOutputFormat)
                        "File is already in the target format."
                    else
                        "Conversion from .$inputFormat to .$finalOutputFormat is not supported."
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

    private fun processCbzToEpub(
        inputFile: File,
        mangaTitle: String,
        useStreamingConversion: Boolean,
        useTrueStreaming: Boolean,
        useTrueDangerousMode: Boolean
    ): ProcessResult {
        val outputFile = File(inputFile.parent, "$mangaTitle.epub")

        return when {
            useTrueDangerousMode -> {
                ZipFile(inputFile).use { convertCbzToEpubDangerously(it, outputFile, mangaTitle) }
                ProcessResult(true, outputFile)
            }
            useStreamingConversion -> {
                val mode = if (useTrueStreaming) "Ultra-Low-Storage" else "Low-Storage"
                println("Converting ${inputFile.name} to EPUB format ($mode Mode)...")
                ZipFile(inputFile).use { createEpubFromCbzStream(it, outputFile, mangaTitle, useTrueStreaming) }
                ProcessResult(outputFile.exists() && outputFile.length() > 0, outputFile)
            }
            else -> {
                println("Converting ${inputFile.name} to EPUB format (Temp Directory Mode)...")
                val tempDir = Files.createTempDirectory("cbz-to-epub-").toFile()
                return try {
                    ZipFile(inputFile).extractAll(tempDir.absolutePath)
                    var chapterFolders = tempDir.listFiles()?.filter { it.isDirectory } ?: emptyList()

                    if (chapterFolders.isEmpty()) {
                        val rootImages = tempDir.listFiles()?.filter { it.isFile && it.name.isImageFile() } ?: emptyList()
                        if (rootImages.isNotEmpty()) {
                            val regex = Regex("""(c?\d{1,4})[_\- ]""", RegexOption.IGNORE_CASE)
                            val grouped = rootImages.groupBy { img ->
                                regex.find(img.name)?.groupValues?.get(1)?.lowercase() ?: "chapter_1"
                            }
                            chapterFolders = grouped.entries.mapIndexed { idx, (key, files) ->
                                val dir = File(tempDir, if (key == "chapter_1" && grouped.size == 1) "chapter_1" else key)
                                dir.mkdirs()
                                files.forEach { it.renameTo(File(dir, it.name)) }
                                dir
                            }
                        }
                    }

                    if (chapterFolders.isNotEmpty()) {
                        createEpubFromFolders(mangaTitle, chapterFolders, outputFile)
                        ProcessResult(outputFile.exists() && outputFile.length() > 0, outputFile)
                    } else {
                        ProcessResult(false, error = "Could not find chapter folders or images inside ${inputFile.name} for conversion.")
                    }
                } finally {
                    tempDir.deleteRecursively()
                }
            }
        }
    }

    private fun processEpubToCbz(
        inputFile: File,
        mangaTitle: String,
        useStreamingConversion: Boolean,
        useTrueStreaming: Boolean,
        useTrueDangerousMode: Boolean
    ): ProcessResult {
        if (useTrueDangerousMode || useStreamingConversion)
            println("Note: Storage-saving modes for EPUB to CBZ conversion are not yet implemented. Using default Temp Directory mode.")
        val outputFile = File(inputFile.parent, "$mangaTitle.cbz")
        convertEpubToCbz(inputFile, outputFile, mangaTitle)
        return ProcessResult(outputFile.exists() && outputFile.length() > 0, outputFile)
    }

    private fun handlePostProcessing(
        inputFile: File,
        result: ProcessResult,
        deleteOriginal: Boolean,
        useTrueDangerousMode: Boolean
    ) {
        when {
            result.success && deleteOriginal && !useTrueDangerousMode -> {
                println("Operation successful. Deleting original file: ${inputFile.name}")
                if (!inputFile.delete()) println("Warning: Failed to delete original file: ${inputFile.name}")
            }
            !result.success && useTrueDangerousMode -> {
                println("DANGEROUS OPERATION FAILED. Your source file ${inputFile.name} is likely CORRUPT.")
            }
            result.success && useTrueDangerousMode -> {
                ZipFile(inputFile).use { src ->
                    val remaining = src.fileHeaders.count { !it.isDirectory }
                    if (remaining <= 1) {
                        println("Dangerous move appears successful. Deleting now-empty source file: ${inputFile.name}")
                        if (!inputFile.delete()) println("Warning: Failed to delete modified source file: ${inputFile.name}")
                    } else {
                        println("Dangerous move complete. Source file still contains non-image data and was not deleted.")
                    }
                }
            }
            result.error != null -> println(result.error)
        }
    }
}
