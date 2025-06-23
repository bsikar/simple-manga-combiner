package com.mangacombiner.core

import com.mangacombiner.downloader.Downloader
import com.mangacombiner.model.*
import com.mangacombiner.util.inferChapterSlugsFromZip
import com.mangacombiner.util.logDebug
import kotlinx.coroutines.*
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.serialization.UnknownChildHandler
import nl.adaptivity.xmlutil.serialization.XML
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import javax.imageio.ImageIO
import kotlin.io.path.nameWithoutExtension

/**
 * Core processor for manga file operations including conversion, creation, and synchronization.
 */
object Processor {
    // Constants
    private const val COMIC_INFO_FILE = "ComicInfo.xml"
    private const val EPUB_MIMETYPE = "application/epub+zip"
    private const val CONTAINER_XML_PATH = "META-INF/container.xml"
    private const val OPF_BASE_PATH = "OEBPS"

    // Supported image extensions
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

    // XML configuration
    @OptIn(ExperimentalXmlUtilApi::class)
    @Suppress("DEPRECATION")
    private val xmlSerializer = XML {
        indentString = "  "
        // Keep using the original unknownChildHandler
        unknownChildHandler = UnknownChildHandler { _, _, _, _, _ ->
            emptyList<XML.ParsedData<*>>()
        }
    }

    /**
     * Extension function to convert a string to title case.
     */
    private fun String.titlecase(): String = this.split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase() else char.toString()
        }
    }

    /**
     * Checks if a file is an image based on its extension.
     */
    private fun String.isImageFile(): Boolean {
        val extension = this.substringAfterLast('.').lowercase()
        return extension in IMAGE_EXTENSIONS
    }

    /**
     * Gets the MIME type for an image file.
     */
    private fun String.getImageMimeType(): String {
        return when (this.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png"         -> "image/png"
            "webp"        -> "image/webp"
            "gif"         -> "image/gif"
            else          -> "image/jpeg" // Default fallback
        }
    }

    /**
     * Generates ComicInfo.xml content for CBZ files.
     */
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
                    bookmark != null                -> PageInfo.TYPE_STORY
                    else                            -> null
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

    /**
     * Creates bookmarks for chapters based on folder information.
     */
    private fun createBookmarks(sortedFolders: List<File>): Pair<List<Pair<Int, String>>, Int> {
        var totalPageCount = 0
        val bookmarks = mutableListOf<Pair<Int, String>>()

        sortedFolders.forEach { folder ->
            val pageCount = folder.listFiles()?.count { it.isFile } ?: 0
            val cleanTitle = folder.name.replace('-', ' ').replace('_', ' ').titlecase()
            bookmarks.add(Pair(totalPageCount, cleanTitle))
            totalPageCount += pageCount
        }

        return Pair(bookmarks, totalPageCount)
    }

    /**
     * Sorts folders by chapter number.
     */
    private fun sortChapterFolders(folders: List<File>): List<File> {
        return folders.sortedBy { folder ->
            folder.name.filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
        }
    }

    /**
     * Creates a CBZ archive from chapter folders.
     */
    fun createCbzFromFolders(mangaTitle: String, chapterFolders: List<File>, outputFile: File) {
        if (outputFile.exists()) {
            outputFile.delete()
        }
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
            // Add ComicInfo.xml
            zipFile.addStream(
                comicInfoXml.byteInputStream(),
                ZipParameters().apply { fileNameInZip = COMIC_INFO_FILE }
            )

            // Add image files from each chapter folder
            sortedFolders.forEach { folder ->
                folder.listFiles()
                    ?.filter { it.isFile }
                    ?.sorted()
                    ?.forEach { imageFile ->
                        zipFile.addFile(
                            imageFile,
                            ZipParameters().apply {
                                fileNameInZip = "${folder.name}/${imageFile.name}"
                            }
                        )
                    }
            }
        }

        println("Successfully created: ${outputFile.absolutePath}")
    }

    /**
     * Container for EPUB metadata.
     */
    private data class EpubMetadata(
        val bookId: String = UUID.randomUUID().toString(),
        val manifestItems: MutableList<String> = mutableListOf(),
        val spineItems: MutableList<String> = mutableListOf(),
        val tocNavPoints: MutableList<String> = mutableListOf(),
        var playOrder: Int = 1
    )

    /**
     * Creates the container.xml content for EPUB.
     */
    private fun createContainerXml(): String {
        return """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="$OPF_BASE_PATH/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""".trimIndent()
    }

    /**
     * Creates an XHTML page for an EPUB image.
     */
    private fun createXhtmlPage(
        chapterTitle: String,
        pageIndex: Int,
        imageHref: String,
        width: Int,
        height: Int
    ): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en">
<head>
  <title>$chapterTitle - Page ${pageIndex + 1}</title>
  <meta name="viewport" content="width=$width, height=$height"/>
  <style>
    body { margin: 0; padding: 0; }
    img { width: 100%; height: 100%; object-fit: contain; }
  </style>
</head>
<body>
  <img src="../$imageHref" alt="Page ${pageIndex + 1}"/>
</body>
</html>""".trimIndent()
    }

    /**
     * Creates the content.opf file for EPUB.
     */
    private fun createContentOpf(mangaTitle: String, metadata: EpubMetadata): String {
        return """<?xml version="1.0"?>
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
    }

    /**
     * Creates the toc.ncx file for EPUB.
     */
    private fun createTocNcx(mangaTitle: String, metadata: EpubMetadata): String {
        return """<?xml version="1.0"?>
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
    }

    /**
     * Processes a single chapter for EPUB creation.
     */
    private fun processEpubChapter(
        chapterName: String,
        imageHeaders: List<Any>, // Can be FileHeader or File
        metadata: EpubMetadata,
        epubZip: ZipFile,
        sourceZip: ZipFile? = null,
        useTrueStreaming: Boolean = false
    ) {
        val cleanChapterTitle = chapterName.replace('-', ' ').replace('_', ' ').titlecase()
        var chapterNavPointAdded = false

        imageHeaders.forEachIndexed { pageIndex, imageSource ->
            try {
                val (bytes, originalFileName, bufferedImage) = when (imageSource) {
                    is net.lingala.zip4j.model.FileHeader -> {
                        val fileName = imageSource.fileName.substringAfterLast('/')
                        val data = if (useTrueStreaming) ByteArray(0)
                        else sourceZip!!.getInputStream(imageSource).use { it.readBytes() }
                        val img = if (useTrueStreaming)
                            sourceZip!!.getInputStream(imageSource).use { ImageIO.read(it) }
                        else ImageIO.read(data.inputStream())
                        Triple(data, fileName, img)
                    }
                    is File -> {
                        val data = imageSource.readBytes()
                        val img = ImageIO.read(imageSource)
                        Triple(data, imageSource.name, img)
                    }
                    else -> throw IllegalArgumentException("Unknown image source type")
                }

                if (bufferedImage == null) throw Exception("ImageIO.read returned null")

                val imageId = "img_${chapterName}_$pageIndex"
                val pageId  = "page_${chapterName}_$pageIndex"
                val imageHref = "images/$originalFileName"
                val pageHref  = "xhtml/$pageId.xhtml"
                val mediaType = originalFileName.getImageMimeType()

                metadata.manifestItems.add("""<item id="$imageId" href="$imageHref" media-type="$mediaType"/>""")
                metadata.manifestItems.add("""<item id="$pageId" href="$pageHref" media-type="application/xhtml+xml"/>""")
                metadata.spineItems.add("""<itemref idref="$pageId"/>""")

                if (!chapterNavPointAdded) {
                    metadata.tocNavPoints.add(
                        """<navPoint id="navPoint-${metadata.playOrder}" playOrder="${metadata.playOrder}">
      <navLabel><text>$cleanChapterTitle</text></navLabel>
      <content src="$pageHref"/>
    </navPoint>"""
                    )
                    chapterNavPointAdded = true
                    metadata.playOrder++
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

                val xhtmlContent = createXhtmlPage(
                    cleanChapterTitle,
                    pageIndex,
                    imageHref,
                    bufferedImage.width,
                    bufferedImage.height
                )
                epubZip.addStream(
                    xhtmlContent.byteInputStream(),
                    ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$pageHref" }
                )

            } catch (e: Exception) {
                val name = when (imageSource) {
                    is net.lingala.zip4j.model.FileHeader -> imageSource.fileName
                    is File -> imageSource.path
                    else -> "unknown"
                }
                println("Warning: Skipping '$name': ${e.message}")
                logDebug { "Stack trace: ${e.stackTraceToString()}" }
            }
        }
    }

    /**
     * Creates an EPUB from a CBZ file using streaming conversion.
     */
    private fun createEpubFromCbzStream(
        zipFile: ZipFile,
        outputFile: File,
        mangaTitle: String,
        useTrueStreaming: Boolean
    ) {
        if (outputFile.exists()) {
            outputFile.delete()
        }
        outputFile.parentFile?.mkdirs()

        val imageHeaders = zipFile.fileHeaders.filter { header ->
            !header.isDirectory &&
                    header.fileName != COMIC_INFO_FILE &&
                    header.fileName.isImageFile()
        }

        if (imageHeaders.isEmpty()) {
            println("Warning: No image files found in ${zipFile.file.name}. Skipping EPUB creation.")
            return
        }

        println("Creating EPUB archive: ${outputFile.name}...")

        val chapterGroups = imageHeaders
            .groupBy { it.fileName.substringBeforeLast('/') }
            .toSortedMap()

        val metadata = EpubMetadata()

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

            chapterGroups.forEach { (chapterName, headersInChapter) ->
                val sortedHeaders = headersInChapter.sortedBy { it.fileName }
                processEpubChapter(
                    chapterName,
                    sortedHeaders,
                    metadata,
                    epubZip,
                    zipFile,
                    useTrueStreaming
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

        println("Successfully created: ${outputFile.absolutePath}")
    }

    /**
     * Creates an EPUB from chapter folders.
     */
    fun createEpubFromFolders(mangaTitle: String, chapterFolders: List<File>, outputFile: File) {
        if (outputFile.exists()) {
            outputFile.delete()
        }
        outputFile.parentFile?.mkdirs()

        println("Creating EPUB archive: ${outputFile.name}...")

        val sortedFolders = sortChapterFolders(chapterFolders)
        val metadata = EpubMetadata()

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

            sortedFolders.forEach { folder ->
                val imageFiles = folder.listFiles()
                    ?.filter { it.isFile }
                    ?.sorted()
                    ?: return@forEach

                if (imageFiles.isNotEmpty()) {
                    processEpubChapter(
                        folder.name,
                        imageFiles,
                        metadata,
                        epubZip
                    )
                }
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

        println("Successfully created: ${outputFile.absolutePath}")
    }

    /**
     * Downloads manga chapters and creates an archive.
     */
    suspend fun downloadAndCreate(
        seriesUrl: String,
        customTitle: String?,
        exclude: Set<String>,
        format: String,
        chapterWorkers: Int
    ) {
        val mangaTitle = customTitle ?: seriesUrl
            .substringAfter("/manga/")
            .trim('/')
            .replace('-', ' ')
            .titlecase()

        println("Manga Title: $mangaTitle")

        val chapterUrls = Downloader.findChapterUrls(seriesUrl)
            .filterNot { url ->
                url.trimEnd('/').substringAfterLast('/') in exclude
            }

        if (chapterUrls.isEmpty()) {
            println("No chapters found to download. Exiting.")
            return
        }

        val tempDir = Files.createTempDirectory("manga-download-").toFile()

        try {
            println("Downloading ${chapterUrls.size} chapters using up to $chapterWorkers parallel workers...")

            val downloadedFolders = coroutineScope {
                chapterUrls.map { url ->
                    async(Dispatchers.IO) {
                        val chapterSlug = url.trimEnd('/').substringAfterLast('/')
                        println("-> Starting download for chapter: $chapterSlug")
                        Downloader.downloadChapter(url, tempDir)
                    }
                }.awaitAll().filterNotNull()
            }

            if (downloadedFolders.isNotEmpty()) {
                val outputFile = File("$mangaTitle.$format")

                when (format.lowercase()) {
                    "epub" -> createEpubFromFolders(mangaTitle, downloadedFolders, outputFile)
                    else   -> createCbzFromFolders(mangaTitle, downloadedFolders, outputFile)
                }
            } else {
                println("No chapters were successfully downloaded.")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Syncs a local CBZ file with its online source.
     */
    suspend fun syncCbzWithSource(
        cbzFile: File,
        seriesUrl: String,
        exclude: Set<String>,
        chapterWorkers: Int
    ) {
        if (!cbzFile.exists()) {
            println("Error: Local file not found at ${cbzFile.absolutePath}")
            return
        }

        println("--- Starting Sync for: ${cbzFile.name} ---")

        val localSlugs = inferChapterSlugsFromZip(cbzFile)
        println("Found ${localSlugs.size} chapters locally.")

        val allOnlineUrls = Downloader.findChapterUrls(seriesUrl)
        val onlineUrls = allOnlineUrls.filterNot { url ->
            url.trimEnd('/').substringAfterLast('/') in exclude
        }

        if (onlineUrls.isEmpty()) {
            println("Could not retrieve chapter list from source. Aborting sync.")
            return
        }

        val slugToUrl = onlineUrls.associateBy { it.trimEnd('/').substringAfterLast('/') }
        val onlineSlugs = slugToUrl.keys

        val missingSlugs = (onlineSlugs - localSlugs).sorted()

        if (missingSlugs.isEmpty()) {
            println("CBZ is already up-to-date.")
            return
        }

        println("Found ${missingSlugs.size} new chapters to download: ${missingSlugs.joinToString()}")

        val urlsToDownload = missingSlugs.mapNotNull { slugToUrl[it] }
        val tempDir = Files.createTempDirectory("manga-sync-").toFile()

        try {
            println("Downloading new chapters using up to $chapterWorkers parallel workers...")
            coroutineScope {
                urlsToDownload.map { url ->
                    async(Dispatchers.IO) {
                        val chapterSlug = url.trimEnd('/').substringAfterLast('/')
                        println("-> Downloading new chapter: $chapterSlug")
                        Downloader.downloadChapter(url, tempDir)
                    }
                }.awaitAll()
            }

            println("Extracting existing chapters...")
            ZipFile(cbzFile).extractAll(tempDir.absolutePath)

            val allChapterFolders = tempDir.listFiles()
                ?.filter { it.isDirectory }
                ?.toList()
                ?: emptyList()

            if (allChapterFolders.isNotEmpty()) {
                val mangaTitle = cbzFile.nameWithoutExtension
                createCbzFromFolders(mangaTitle, allChapterFolders, cbzFile)
                println("--- Sync complete for: ${cbzFile.name} ---")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Converts CBZ to EPUB using dangerous move operation.
     */
    private fun convertCbzToEpubDangerously(
        sourceZip: ZipFile,
        outputFile: File,
        mangaTitle: String
    ) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val imageHeaders = sourceZip.fileHeaders.filter { header ->
            !header.isDirectory && header.fileName != COMIC_INFO_FILE
        }

        if (imageHeaders.isEmpty()) {
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

            val chapterGroups = imageHeaders
                .groupBy { it.fileName.substringBeforeLast('/') }
                .toSortedMap()

            val metadata = EpubMetadata()
            val allHeadersToMove = chapterGroups.values.flatten().sortedBy { it.fileName }

            allHeadersToMove.forEachIndexed { pageIndex, imageHeader ->
                println("--> Moving image (${pageIndex + 1}/${allHeadersToMove.size}): ${imageHeader.fileName}")

                val imageBytes = sourceZip.getInputStream(imageHeader).use { it.readBytes() }
                val bimg = ImageIO.read(imageBytes.inputStream())
                    ?: throw Exception("Could not read image dimensions")

                val originalFileName = imageHeader.fileName.substringAfterLast('/')

                epubZip.addStream(
                    imageBytes.inputStream(),
                    ZipParameters().apply {
                        fileNameInZip = "$OPF_BASE_PATH/images/$originalFileName"
                    }
                )

                sourceZip.removeFile(imageHeader)

                val chapterName = imageHeader.fileName.substringBeforeLast('/')
                val cleanChapterTitle = chapterName.replace('-', ' ').replace('_', ' ').titlecase()

                val imageId = "img_${chapterName}_$pageIndex"
                val pageId = "page_${chapterName}_$pageIndex"
                val imageHref = "images/$originalFileName"
                val pageHref = "xhtml/$pageId.xhtml"
                val mediaType = originalFileName.getImageMimeType()

                metadata.manifestItems.add("""<item id="$imageId" href="$imageHref" media-type="$mediaType"/>""")
                metadata.manifestItems.add("""<item id="$pageId" href="$pageHref" media-type="application/xhtml+xml"/>""")
                metadata.spineItems.add("""<itemref idref="$pageId"/>""")

                if (chapterGroups[chapterName]?.sortedBy { it.fileName }?.first() == imageHeader) {
                    metadata.tocNavPoints.add(
                        """<navPoint id="navPoint-${metadata.playOrder}" playOrder="${metadata.playOrder}">
      <navLabel><text>$cleanChapterTitle</text></navLabel>
      <content src="$pageHref"/>
    </navPoint>"""
                    )
                    metadata.playOrder++
                }

                val xhtmlContent = createXhtmlPage(
                    cleanChapterTitle,
                    pageIndex,
                    imageHref,
                    bimg.width,
                    bimg.height
                )
                epubZip.addStream(
                    xhtmlContent.byteInputStream(),
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

    /**
     * Converts an EPUB file to CBZ format.
     */
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

                val opfStream = sourceZip.getInputStream(sourceZip.getFileHeader(opfPath))
                val opfText = opfStream.reader().readText()
                val opfPackage = xmlSerializer.decodeFromString(OpfPackage.serializer(), opfText)

                val imageItems = opfPackage.manifest.items.filter { it.mediaType.startsWith("image/") }
                val opfBase = Paths.get(opfPath).parent ?: Paths.get("")

                imageItems.forEach { item ->
                    val imagePathInZip = opfBase.resolve(item.href).normalize().toString().replace(File.separatorChar, '/')
                    val header = sourceZip.getFileHeader(imagePathInZip)
                    if (header != null) {
                        val pathParts = imagePathInZip.split('/')
                        val chapterDir = pathParts.drop(1).dropLast(1).joinToString(File.separator)
                        val finalDir = File(tempDir, chapterDir)
                        finalDir.mkdirs()

                        sourceZip.extractFile(
                            header,
                            finalDir.path,
                            header.fileName.substringAfterLast('/')
                        )
                    }
                }
            }

            val chapterFolders = tempDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            if (chapterFolders.isNotEmpty()) {
                createCbzFromFolders(mangaTitle, chapterFolders, outputFile)
            } else {
                println("No chapters found in EPUB.")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Process result information.
     */
    private data class ProcessResult(
        val success: Boolean,
        val outputFile: File? = null,
        val error: String? = null
    )

    /**
     * Processes a local file for conversion or metadata update.
     */
    fun processLocalFile(
        inputFile: File,
        customTitle: String?,
        forceOverwrite: Boolean,
        outputFormat: String,
        deleteOriginal: Boolean,
        useStreamingConversion: Boolean,
        useTrueStreaming: Boolean,
        useTrueDangerousMode: Boolean
    ) {
        println("\nProcessing local file: ${inputFile.name}")

        val inputFormat = inputFile.extension.lowercase()
        val finalOutputFormat = outputFormat.lowercase()

        if (inputFormat !in setOf("cbz", "epub")) {
            println("Error: Input file must be a .cbz or .epub file.")
            return
        }

        val mangaTitle = customTitle ?: inputFile.nameWithoutExtension
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
                    val message = if (inputFormat == finalOutputFormat) "File is already in the target format."
                    else "Conversion from .$inputFormat to .$finalOutputFormat is not supported."
                    println(message)
                    ProcessResult(false, error = message)
                }
            }
        } catch (e: Exception) {
            val errorMsg = "ERROR: A failure occurred while processing ${inputFile.name}. Reason: ${e.message}"
            println(errorMsg)
            logDebug { "Stack trace for ${inputFile.name} failure: ${e.stackTraceToString()}" }
            result = ProcessResult(false, error = errorMsg)
        }

        handlePostProcessing(inputFile, result, deleteOriginal, useTrueDangerousMode)
    }

    /**
     * Processes CBZ to EPUB conversion.
     */
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
                try {
                    ZipFile(inputFile).extractAll(tempDir.absolutePath)
                    val chapterFolders = tempDir.listFiles()?.filter { it.isDirectory } ?: emptyList()

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

    /**
     * Processes EPUB to CBZ conversion.
     */
    private fun processEpubToCbz(
        inputFile: File,
        mangaTitle: String,
        useStreamingConversion: Boolean,
        useTrueStreaming: Boolean,
        useTrueDangerousMode: Boolean
    ): ProcessResult {
        if (useTrueDangerousMode || useStreamingConversion) {
            println("Note: Storage-saving modes for EPUB to CBZ conversion are not yet implemented. Using default Temp Directory mode.")
        }

        val outputFile = File(inputFile.parent, "$mangaTitle.cbz")
        convertEpubToCbz(inputFile, outputFile, mangaTitle)

        return ProcessResult(outputFile.exists() && outputFile.length() > 0, outputFile)
    }

    /**
     * Handles post-processing after file conversion.
     */
    private fun handlePostProcessing(
        inputFile: File,
        result: ProcessResult,
        deleteOriginal: Boolean,
        useTrueDangerousMode: Boolean
    ) {
        when {
            result.success && deleteOriginal && !useTrueDangerousMode -> {
                println("Operation successful. Deleting original file: ${inputFile.name}")
                if (!inputFile.delete()) {
                    println("Warning: Failed to delete original file: ${inputFile.name}")
                }
            }
            !result.success && useTrueDangerousMode -> {
                println("DANGEROUS OPERATION FAILED. Your source file ${inputFile.name} is likely CORRUPT.")
            }
            result.success && useTrueDangerousMode -> {
                ZipFile(inputFile).use { sourceZip ->
                    val remainingFiles = sourceZip.fileHeaders.count { !it.isDirectory }
                    if (remainingFiles <= 1) {
                        println("Dangerous move appears successful. Deleting now-empty source file: ${inputFile.name}")
                        if (!inputFile.delete()) {
                            println("Warning: Failed to delete modified source file: ${inputFile.name}")
                        }
                    } else {
                        println("Dangerous move complete. Source file still contains non-image data and was not deleted.")
                    }
                }
            }
            result.error != null -> {
                println(result.error)
            }
        }
    }
}
