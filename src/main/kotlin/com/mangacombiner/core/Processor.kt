package com.mangacombiner.core

import com.mangacombiner.MainKt.titlecase
import com.mangacombiner.downloader.Downloader
import com.mangacombiner.model.*
import com.mangacombiner.util.inferChapterSlugsFromZip
import com.mangacombiner.util.logDebug
import kotlinx.coroutines.*
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.UnknownChildHandler // Import the interface
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import javax.imageio.ImageIO
import kotlin.io.path.nameWithoutExtension

private fun generateComicInfoXml(mangaTitle: String, bookmarks: List<Pair<Int, String>>, totalPageCount: Int): String {
    val pageInfos = (0 until totalPageCount).map { i ->
        val bookmarkTitle = bookmarks.firstOrNull { it.first == i }?.second
        val firstBookmarkIndex = bookmarks.firstOrNull()?.first
        val pageType = if (bookmarkTitle != null) { if (i == firstBookmarkIndex) "FrontCover" else "Story" } else null
        PageInfo(Image = i, Bookmark = bookmarkTitle, Type = pageType)
    }
    val comicInfo = ComicInfo(Series = mangaTitle, Title = mangaTitle, PageCount = totalPageCount, Pages = Pages(pageInfos))
    val xml = XML { indentString = "  " }
    return xml.encodeToString(ComicInfo.serializer(), comicInfo)
}

fun createCbzFromFolders(mangaTitle: String, chapterFolders: List<File>, outputFile: File) {
    if (outputFile.exists()) { outputFile.delete() }
    outputFile.parentFile?.mkdirs()
    println("Creating CBZ archive: ${outputFile.name}...")
    val sortedFolders = chapterFolders.sortedBy { it.name.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }
    var totalPageCount = 0
    val bookmarks = mutableListOf<Pair<Int, String>>()
    sortedFolders.forEach { folder ->
        val pageCount = folder.listFiles()?.filter { it.isFile }?.size ?: 0
        val cleanTitle = folder.name.replace('-', ' ').replace('_', ' ').titlecase()
        bookmarks.add(Pair(totalPageCount, cleanTitle))
        totalPageCount += pageCount
    }
    if (totalPageCount == 0) { println("Warning: No images found for ${mangaTitle}. Skipping CBZ creation."); return }
    val comicInfoXml = generateComicInfoXml(mangaTitle, bookmarks, totalPageCount)
    ZipFile(outputFile).use { zipFile ->
        zipFile.addStream(comicInfoXml.byteInputStream(), ZipParameters().apply { fileNameInZip = "ComicInfo.xml" })
        sortedFolders.forEach { folder ->
            folder.listFiles()?.filter { it.isFile }?.sorted()?.forEach { imageFile ->
                zipFile.addFile(imageFile, ZipParameters().apply { fileNameInZip = "${folder.name}/${imageFile.name}" })
            }
        }
    }
    println("Successfully created: ${outputFile.absolutePath}")
}
private fun createEpubFromCbzStream(zipFile: ZipFile, outputFile: File, mangaTitle: String, useTrueStreaming: Boolean) {
    if (outputFile.exists()) { outputFile.delete() }
    outputFile.parentFile?.mkdirs()
    val imageHeaders = zipFile.fileHeaders.filter { !it.isDirectory && it.fileName != "ComicInfo.xml" && (it.fileName.endsWith(".jpg", true) || it.fileName.endsWith(".jpeg", true) || it.fileName.endsWith(".png", true) || it.fileName.endsWith(".webp", true) || it.fileName.endsWith(".gif", true)) }
    if (imageHeaders.isEmpty()) { println("Warning: No image files found in ${zipFile.file.name}. Skipping EPUB creation."); return }
    val chapterGroups = imageHeaders.groupBy { it.fileName.substringBeforeLast('/') }.toSortedMap()
    println("Creating EPUB archive: ${outputFile.name}...")
    val bookId = UUID.randomUUID().toString()
    val manifestItems = mutableListOf<String>()
    val spineItems = mutableListOf<String>()
    val tocNavPoints = mutableListOf<String>()
    var playOrder = 1
    ZipFile(outputFile).use { epubZip ->
        epubZip.addStream("application/epub+zip".byteInputStream(), ZipParameters().apply { fileNameInZip = "mimetype"; compressionMethod = CompressionMethod.STORE })
        epubZip.addStream("""<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".byteInputStream(), ZipParameters().apply { fileNameInZip = "META-INF/container.xml" })
        for ((chapterName, headersInChapter) in chapterGroups) {
            val cleanChapterTitle = chapterName.replace('-', ' ').replace('_', ' ').titlecase()
            var chapterNavPointAdded = false
            for ((pageIndex, imageHeader) in headersInChapter.sortedBy { it.fileName }.withIndex()) {
                try {
                    val bimg = zipFile.getInputStream(imageHeader).use { ImageIO.read(it) } ?: throw Exception("ImageIO.read returned null, likely not a valid image format.")
                    val originalFileName = imageHeader.fileName.substringAfterLast('/')
                    val imageId = "img_${chapterName}_$pageIndex"; val pageId = "page_${chapterName}_$pageIndex"; val imageHref = "images/$originalFileName"; val pageHref = "xhtml/$pageId.xhtml"
                    val mediaType = when (originalFileName.substringAfterLast('.').lowercase()) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/gif" }
                    manifestItems.add("""<item id="$imageId" href="$imageHref" media-type="$mediaType"/>"""); manifestItems.add("""<item id="$pageId" href="$pageHref" media-type="application/xhtml+xml"/>"""); spineItems.add("""<itemref idref="$pageId"/>""")
                    if (!chapterNavPointAdded) { tocNavPoints.add("""<navPoint id="navPoint-$playOrder" playOrder="$playOrder"><navLabel><text>$cleanChapterTitle</text></navLabel><content src="$pageHref"/></navPoint>"""); chapterNavPointAdded = true; playOrder++ }
                    if (useTrueStreaming) { zipFile.getInputStream(imageHeader).use { imageStream -> epubZip.addStream(imageStream, ZipParameters().apply { fileNameInZip = "OEBPS/$imageHref" }) } } else { val imageBytes = zipFile.getInputStream(imageHeader).use { it.readBytes() }; epubZip.addStream(imageBytes.inputStream(), ZipParameters().apply { fileNameInZip = "OEBPS/$imageHref" }) }
                    val xhtmlContent = """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en"><head><title>$cleanChapterTitle - Page ${pageIndex + 1}</title><meta name="viewport" content="width=${bimg.width}, height=${bimg.height}"/><style>body{margin:0;padding:0;}img{width:100%;height:100%;object-fit:contain;}</style></head><body><img src="../$imageHref" alt="Page ${pageIndex + 1}"/></body></html>""".trimIndent()
                    epubZip.addStream(xhtmlContent.byteInputStream(), ZipParameters().apply { fileNameInZip = "OEBPS/$pageHref" })
                } catch (e: Exception) { println("Warning: Skipping problematic image '${imageHeader.fileName}' in '${zipFile.file.name}'. Reason: ${e.message}"); continue }
            }
        }
        val contentOpf = """<?xml version="1.0"?><package version="2.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf"><dc:title>$mangaTitle</dc:title><dc:creator opf:role="aut">MangaCombiner</dc:creator><dc:language>en</dc:language><dc:identifier id="BookId" opf:scheme="UUID">$bookId</dc:identifier></metadata><manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>${manifestItems.joinToString("\n    ")}</manifest><spine toc="ncx">${spineItems.joinToString("\n    ")}</spine></package>"""
        epubZip.addStream(contentOpf.byteInputStream(), ZipParameters().apply { fileNameInZip = "OEBPS/content.opf" })
        val tocNcx = """<?xml version="1.0"?><!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd"><ncx version="2005-1" xmlns="http://www.daisy.org/z3986/2005/ncx/"><head><meta name="dtb:uid" content="$bookId"/></head><docTitle><text>$mangaTitle</text></docTitle><navMap>${tocNavPoints.joinToString("\n    ")}</navMap></ncx>"""
        epubZip.addStream(tocNcx.byteInputStream(), ZipParameters().apply { fileNameInZip = "OEBPS/toc.ncx" })
    }
    println("Successfully created: ${outputFile.absolutePath}")
}
fun createEpubFromFolders(mangaTitle: String, chapterFolders: List<File>, outputFile: File) {
    if (outputFile.exists()) { outputFile.delete() }
    outputFile.parentFile?.mkdirs()
    println("Creating EPUB archive: ${outputFile.name}...")
    val bookId = UUID.randomUUID().toString()
    val sortedFolders = chapterFolders.sortedBy { it.name.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }
    val manifestItems = mutableListOf<String>()
    val spineItems = mutableListOf<String>()
    val tocNavPoints = mutableListOf<String>()
    var playOrder = 1
    ZipFile(outputFile).use { epubZip ->
        epubZip.addStream("application/epub+zip".byteInputStream(), ZipParameters().apply { fileNameInZip = "mimetype"; compressionMethod = CompressionMethod.STORE })
        epubZip.addStream("""<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".byteInputStream(), ZipParameters().apply { fileNameInZip = "META-INF/container.xml" })
        for (folder in sortedFolders) {
            val imageFiles = folder.listFiles()?.filter { it.isFile }?.sorted() ?: continue
            val cleanChapterTitle = folder.name.replace('-', ' ').replace('_', ' ').titlecase()
            var chapterNavPointAdded = false
            for ((pageIndex, imageFile) in imageFiles.withIndex()) {
                try {
                    val bimg = ImageIO.read(imageFile) ?: throw Exception("ImageIO.read returned null, likely not an image.")
                    val imageId = "img_${folder.name}_$pageIndex"; val pageId = "page_${folder.name}_$pageIndex"; val imageHref = "images/${imageFile.name}"; val pageHref = "xhtml/$pageId.xhtml"
                    val mediaType = when (imageFile.extension.lowercase()) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/gif" }
                    manifestItems.add("""<item id="$imageId" href="$imageHref" media-type="$mediaType"/>"""); manifestItems.add("""<item id="$pageId" href="$pageHref" media-type="application/xhtml+xml"/>"""); spineItems.add("""<itemref idref="$pageId"/>""")
                    if (!chapterNavPointAdded) { tocNavPoints.add("""<navPoint id="navPoint-$playOrder" playOrder="$playOrder"><navLabel><text>$cleanChapterTitle</text></navLabel><content src="$pageHref"/></navPoint>"""); chapterNavPointAdded = true; playOrder++ }
                    epubZip.addFile(imageFile, ZipParameters().apply { fileNameInZip = "OEBPS/$imageHref" })
                    val xhtmlContent = """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en"><head><title>$cleanChapterTitle - Page ${pageIndex + 1}</title><meta name="viewport" content="width=${bimg.width}, height=${bimg.height}"/><style>body{margin:0;padding:0;}img{width:100%;height:100%;object-fit:contain;}</style></head><body><img src="../$imageHref" alt="Page ${pageIndex + 1}"/></body></html>""".trimIndent()
                    epubZip.addStream(xhtmlContent.byteInputStream(), ZipParameters().apply { fileNameInZip = "OEBPS/$pageHref" })
                } catch (e: Exception) { println("Warning: Skipping problematic file '${imageFile.path}'. Reason: ${e.message}"); continue }
            }
        }
        val contentOpf = """<?xml version="1.0"?><package version="2.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf"><dc:title>$mangaTitle</dc:title><dc:creator opf:role="aut">MangaCombiner</dc:creator><dc:language>en</dc:language><dc:identifier id="BookId" opf:scheme="UUID">$bookId</dc:identifier></metadata><manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>${manifestItems.joinToString("\n    ")}</manifest><spine toc="ncx">${spineItems.joinToString("\n    ")}</spine></package>"""
        epubZip.addStream(contentOpf.byteInputStream(), ZipParameters().apply { fileNameInZip = "OEBPS/content.opf" })
        val tocNcx = """<?xml version="1.0"?><!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd"><ncx version="2005-1" xmlns="http://www.daisy.org/z3986/2005/ncx/"><head><meta name="dtb:uid" content="$bookId"/></head><docTitle><text>$mangaTitle</text></docTitle><navMap>${tocNavPoints.joinToString("\n    ")}</navMap></ncx>"""
        epubZip.addStream(tocNcx.byteInputStream(), ZipParameters().apply { fileNameInZip = "OEBPS/toc.ncx" })
    }
    println("Successfully created: ${outputFile.absolutePath}")
}
suspend fun downloadAndCreate(seriesUrl: String, customTitle: String?, exclude: Set<String>, format: String, chapterWorkers: Int) {
    val mangaTitle = customTitle ?: seriesUrl.substringAfter("/manga/").trim('/').replace('-', ' ').titlecase()
    println("Manga Title: $mangaTitle")
    val chapterUrls = Downloader.findChapterUrls(seriesUrl).filterNot { url -> url.trimEnd('/').substringAfterLast('/') in exclude }
    if (chapterUrls.isEmpty()) { println("No chapters found to download. Exiting."); return }
    val tempDir = Files.createTempDirectory("manga-download-").toFile()
    try {
        println("Downloading ${chapterUrls.size} chapters using up to $chapterWorkers parallel workers...")
        val downloadedFolders = coroutineScope { chapterUrls.map { url -> async(Dispatchers.IO) { println("-> Starting download for chapter: ${url.trimEnd('/').substringAfterLast('/')}"); Downloader.downloadChapter(url, tempDir) } }.awaitAll().filterNotNull() }
        if (downloadedFolders.isNotEmpty()) {
            val outputFile = File("$mangaTitle.$format")
            if (format.equals("epub", ignoreCase = true)) { createEpubFromFolders(mangaTitle, downloadedFolders, outputFile) } else { createCbzFromFolders(mangaTitle, downloadedFolders, outputFile) }
        } else { println("No chapters were successfully downloaded.") }
    } finally { tempDir.deleteRecursively() }
}
suspend fun syncCbzWithSource(cbzFile: File, seriesUrl: String, exclude: Set<String>, chapterWorkers: Int) {
    if (!cbzFile.exists()) { println("Error: Local file not found at ${cbzFile.absolutePath}"); return }
    println("--- Starting Sync for: ${cbzFile.name} ---")
    val localSlugs = inferChapterSlugsFromZip(cbzFile)
    println("Found ${localSlugs.size} chapters locally.")
    val allOnlineUrls = Downloader.findChapterUrls(seriesUrl)
    val onlineUrls = allOnlineUrls.filterNot { url -> url.trimEnd('/').substringAfterLast('/') in exclude }
    if (onlineUrls.isEmpty()) { println("Could not retrieve chapter list from source. Aborting sync."); return }
    val slugToUrl = onlineUrls.associateBy { it.trimEnd('/').substringAfterLast('/') }
    val onlineSlugs = slugToUrl.keys
    val missingSlugs = (onlineSlugs - localSlugs).sorted()
    if (missingSlugs.isEmpty()) { println("CBZ is already up-to-date."); return }
    println("Found ${missingSlugs.size} new chapters to download: ${missingSlugs.joinToString()}")
    val urlsToDownload = missingSlugs.mapNotNull { slugToUrl[it] }
    val tempDir = Files.createTempDirectory("manga-sync-").toFile()
    try {
        println("Downloading new chapters using up to $chapterWorkers parallel workers...")
        coroutineScope { urlsToDownload.map { url -> async(Dispatchers.IO) { println("-> Downloading new chapter: ${url.trimEnd('/').substringAfterLast('/')}"); Downloader.downloadChapter(url, tempDir) } }.awaitAll() }
        println("Extracting existing chapters...")
        ZipFile(cbzFile).extractAll(tempDir.absolutePath)
        val allChapterFolders = tempDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
        if (allChapterFolders.isNotEmpty()) {
            val mangaTitle = cbzFile.nameWithoutExtension
            createCbzFromFolders(mangaTitle, allChapterFolders, cbzFile)
            println("--- Sync complete for: ${cbzFile.name} ---")
        }
    } finally { tempDir.deleteRecursively() }
}


private fun convertCbzToEpubDangerously(sourceZip: ZipFile, outputFile: File, mangaTitle: String) {
    if (outputFile.exists()) { outputFile.delete() }
    outputFile.parentFile?.mkdirs()

    val imageHeaders = sourceZip.fileHeaders.filter { !it.isDirectory && it.fileName != "ComicInfo.xml" }

    if (imageHeaders.isEmpty()) { println("Warning: No image files found in ${sourceZip.file.name}. Skipping EPUB creation."); return }

    println("Creating EPUB archive with DANGEROUS MOVE: ${outputFile.name}...")
    ZipFile(outputFile).use { epubZip ->
        epubZip.addStream("application/epub+zip".byteInputStream(), ZipParameters().apply { fileNameInZip = "mimetype"; compressionMethod = CompressionMethod.STORE })
        epubZip.addStream("""<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".byteInputStream(), ZipParameters().apply { fileNameInZip = "META-INF/container.xml" })

        val chapterGroups = imageHeaders.groupBy { it.fileName.substringBeforeLast('/') }.toSortedMap()
        val bookId = UUID.randomUUID().toString()
        val manifestItems = mutableListOf<String>()
        val spineItems = mutableListOf<String>()
        val tocNavPoints = mutableListOf<String>()
        var playOrder = 1

        val allHeadersToMove = chapterGroups.values.flatten().sortedBy { it.fileName }

        for ((pageIndex, imageHeader) in allHeadersToMove.withIndex()) {
            println("--> Moving image (${pageIndex + 1}/${allHeadersToMove.size}): ${imageHeader.fileName}")
            val imageBytes = sourceZip.getInputStream(imageHeader).use { it.readBytes() }
            val bimg = ImageIO.read(imageBytes.inputStream()) ?: throw Exception("Could not read image dimensions")

            val originalFileName = imageHeader.fileName.substringAfterLast('/')
            val zipParams = ZipParameters().apply { fileNameInZip = "OEBPS/images/$originalFileName" }
            epubZip.addStream(imageBytes.inputStream(), zipParams)

            sourceZip.removeFile(imageHeader)

            val chapterName = imageHeader.fileName.substringBeforeLast('/')
            val cleanChapterTitle = chapterName.replace('-', ' ').replace('_', ' ').titlecase()
            val imageId = "img_${chapterName}_$pageIndex"; val pageId = "page_${chapterName}_$pageIndex"; val imageHref = "images/$originalFileName"; val pageHref = "xhtml/$pageId.xhtml"
            val mediaType = when (originalFileName.substringAfterLast('.').lowercase()) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/gif" }
            manifestItems.add("""<item id="$imageId" href="$imageHref" media-type="$mediaType"/>"""); manifestItems.add("""<item id="$pageId" href="$pageHref" media-type="application/xhtml+xml"/>"""); spineItems.add("""<itemref idref="$pageId"/>""")
            if (chapterGroups[chapterName]?.sortedBy { it.fileName }?.first() == imageHeader) {
                tocNavPoints.add("""<navPoint id="navPoint-$playOrder" playOrder="$playOrder"><navLabel><text>$cleanChapterTitle</text></navLabel><content src="$pageHref"/></navPoint>"""); playOrder++
            }
            val xhtmlContent = """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en"><head><title>$cleanChapterTitle - Page ${pageIndex + 1}</title><meta name="viewport" content="width=${bimg.width}, height=${bimg.height}"/><style>body{margin:0;padding:0;}img{width:100%;height:100%;object-fit:contain;}</style></head><body><img src="../$imageHref" alt="Page ${pageIndex + 1}"/></body></html>""".trimIndent()
            epubZip.addStream(xhtmlContent.byteInputStream(), ZipParameters().apply { fileNameInZip = "OEBPS/$pageHref" })
        }
        val contentOpf = """<?xml version="1.0"?><package version="2.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf"><dc:title>$mangaTitle</dc:title><dc:creator opf:role="aut">MangaCombiner</dc:creator><dc:language>en</dc:language><dc:identifier id="BookId" opf:scheme="UUID">$bookId</dc:identifier></metadata><manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>${manifestItems.joinToString("\n    ")}</manifest><spine toc="ncx">${spineItems.joinToString("\n    ")}</spine></package>"""
        epubZip.addStream(contentOpf.byteInputStream(), ZipParameters().apply { fileNameInZip = "OEBPS/content.opf" })
        val tocNcx = """<?xml version="1.0"?><!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd"><ncx version="2005-1" xmlns="http://www.daisy.org/z3986/2005/ncx/"><head><meta name="dtb:uid" content="$bookId"/></head><docTitle><text>$mangaTitle</text></docTitle><navMap>${tocNavPoints.joinToString("\n    ")}</navMap></ncx>"""
        epubZip.addStream(tocNcx.byteInputStream(), ZipParameters().apply { fileNameInZip = "OEBPS/toc.ncx" })
    }
    println("DANGEROUS MOVE complete. Original file has been modified and is likely empty of images.")
}

private fun convertEpubToCbz(inputFile: File, outputFile: File, mangaTitle: String) {
    println("Converting EPUB ${inputFile.name} to CBZ format...")
    val tempDir = Files.createTempDirectory("epub-to-cbz-").toFile()
    try {
        ZipFile(inputFile).use { sourceZip ->
            val containerStream = sourceZip.getInputStream(sourceZip.getFileHeader("META-INF/container.xml"))
            val containerXml = containerStream.reader().readText()
            val opfPath = Regex("""full-path="([^"]+)"""").find(containerXml)?.groupValues?.get(1)
                ?: throw Exception("Could not find content.opf path in container.xml")

            val opfStream = sourceZip.getInputStream(sourceZip.getFileHeader(opfPath))

            // **FINAL FIX**
            // The compiler needs the lambda to be explicitly converted to the `UnknownChildHandler`
            // functional interface. This resolves the `Assignment type mismatch` error.
            val xml = XML {
                indentString = "  "
                unknownChildHandler = UnknownChildHandler { _, _, _, _, _ ->
                    emptyList<XML.ParsedData<*>>()
                }
            }
            // **END OF FIX**

            val opfText = opfStream.reader().readText()
            val opfPackage = xml.decodeFromString(OpfPackage.serializer(), opfText)
            val imageItems = opfPackage.manifest.items.filter { it.mediaType.startsWith("image/") }

            val opfBase = Paths.get(opfPath).parent ?: Paths.get("")
            for (item in imageItems) {
                val imagePathInZip = opfBase.resolve(item.href).normalize().toString().replace(File.separatorChar, '/')
                val header = sourceZip.getFileHeader(imagePathInZip)
                if (header != null) {
                    val pathParts = imagePathInZip.split('/')
                    val chapterDir = pathParts.drop(1).dropLast(1).joinToString(File.separator)
                    val finalDir = File(tempDir, chapterDir)
                    finalDir.mkdirs()
                    sourceZip.extractFile(header, finalDir.path, header.fileName.substringAfterLast('/'))
                }
            }
        }

        val chapterFolders = tempDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
        if (chapterFolders.isNotEmpty()) {
            createCbzFromFolders(mangaTitle, chapterFolders, outputFile)
        } else {
            println("No chapters found in EPUB.")
        }

    } finally {
        tempDir.deleteRecursively()
    }
}


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

    if (!inputFormat.equals("cbz", true) && !inputFormat.equals("epub", true)) {
        println("Error: Input file must be a .cbz or .epub file.")
        return
    }

    val mangaTitle = customTitle ?: inputFile.nameWithoutExtension
    var success = false

    try {
        when (inputFormat to finalOutputFormat) {
            "cbz" to "epub" -> {
                val outputFile = File(inputFile.parent, "$mangaTitle.epub")
                when {
                    useTrueDangerousMode -> ZipFile(inputFile).use { convertCbzToEpubDangerously(it, outputFile, mangaTitle) }
                    useStreamingConversion -> {
                        val mode = if (useTrueStreaming) "Ultra-Low-Storage" else "Low-Storage"
                        println("Converting ${inputFile.name} to EPUB format ($mode Mode)...")
                        ZipFile(inputFile).use { createEpubFromCbzStream(it, outputFile, mangaTitle, useTrueStreaming) }
                    }
                    else -> {
                        println("Converting ${inputFile.name} to EPUB format (Temp Directory Mode)...")
                        val tempDir = Files.createTempDirectory("cbz-to-epub-").toFile()
                        try {
                            ZipFile(inputFile).extractAll(tempDir.absolutePath)
                            val chapterFolders = tempDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
                            if (chapterFolders.isNotEmpty()) { createEpubFromFolders(mangaTitle, chapterFolders, outputFile) }
                            else { println("Could not find chapter folders inside ${inputFile.name} for conversion.") }
                        } finally {
                            tempDir.deleteRecursively()
                        }
                    }
                }
                success = if (useTrueDangerousMode) true else outputFile.exists() && outputFile.length() > 0
            }

            "epub" to "cbz" -> {
                if (useTrueDangerousMode || useStreamingConversion) {
                    println("Note: Storage-saving modes for EPUB to CBZ conversion are not yet implemented. Using default Temp Directory mode.")
                }
                val outputFile = File(inputFile.parent, "$mangaTitle.cbz")
                convertEpubToCbz(inputFile, outputFile, mangaTitle)
                success = outputFile.exists() && outputFile.length() > 0
            }

            "cbz" to "cbz" -> {
                if (deleteOriginal) println("Note: --delete-original is ignored when the source and destination formats are the same.")
                println("Updating metadata for: ${inputFile.name}")
                success = true
            }

            else -> {
                if (inputFormat == finalOutputFormat) println("File is already in the target format.")
                else println("Conversion from .$inputFormat to .$finalOutputFormat is not supported.")
            }
        }
    } catch (e: Exception) {
        println("ERROR: A failure occurred while processing ${inputFile.name}. Reason: ${e.message}")
        logDebug { "Stack trace for ${inputFile.name} failure: ${e.stackTraceToString()}" }
        success = false
    }

    if (success && deleteOriginal && !useTrueDangerousMode) {
        println("Operation successful. Deleting original file: ${inputFile.name}")
        if (!inputFile.delete()) {
            println("Warning: Failed to delete original file: ${inputFile.name}")
        }
    } else if (!success && useTrueDangerousMode) {
        println("DANGEROUS OPERATION FAILED. Your source file ${inputFile.name} is likely CORRUPT.")
    } else if (success && useTrueDangerousMode) {
        ZipFile(inputFile).use { sourceZip ->
            if (sourceZip.fileHeaders.count { !it.isDirectory } <= 1) {
                println("Dangerous move appears successful. Deleting now-empty source file: ${inputFile.name}")
            } else {
                println("Dangerous move complete. Source file still contains non-image data and was not deleted.")
            }
        }
        if (!inputFile.delete()) {
            println("Warning: Failed to delete modified source file: ${inputFile.name}")
        }
    }
}
