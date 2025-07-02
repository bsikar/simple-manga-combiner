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
import java.nio.file.StandardCopyOption
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

        println("Normalizing chapter names for CBZ compatibility...")
        val normalizedFolders = chapterFolders.map { folder ->
            val normalizedName = normalizeChapterSlug(folder.name)
            val destFile = File(folder.parentFile, normalizedName)
            if (folder.name != destFile.name) {
                if (folder.renameTo(destFile)) destFile else {
                    logError("Failed to rename folder ${folder.name} to $normalizedName"); folder
                }
            } else folder
        }

        val sortedFolders = sortChapterFolders(normalizedFolders)
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
                    if (images.isNotEmpty()) processEpubChapter(folder.name, images, metadata, epubZip)
                }
            }

            val opfContent = createContentOpf(mangaTitle, metadata)
            val tocContent = createTocNcx(mangaTitle, metadata)
            epubZip.addStream(opfContent.byteInputStream(), ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/content.opf" })
            epubZip.addStream(tocContent.byteInputStream(), ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/toc.ncx" })
        }
        println("Successfully created: ${outputFile.name}")
    }

    private fun processEpubChapter(chapterName: String, imageSources: List<File>, metadata: EpubMetadata, epubZip: ZipFile) {
        val cleanTitle = chapterName.replace(Regex("[._-]"), " ").titlecase()
        var tocAdded = false
        imageSources.forEachIndexed { pageIndex, imageFile ->
            try {
                val uniqueImageName = "${chapterName}_page_${pageIndex + 1}.${imageFile.extension}"
                val imageId = "img_${chapterName}_$pageIndex"; val pageId = "page_${chapterName}_$pageIndex"
                val imageHref = "images/$uniqueImageName"; val pageHref = "xhtml/$pageId.xhtml"
                val mediaType = imageFile.extension.getImageMimeType()

                metadata.manifestItems += """<item id="$imageId" href="$imageHref" media-type="$mediaType"/>"""
                metadata.manifestItems += """<item id="$pageId" href="$pageHref" media-type="application/xhtml+xml"/>"""
                metadata.spineItems += """<itemref idref="$pageId"/>"""

                if (!tocAdded) {
                    metadata.tocNavPoints += """<navPoint id="navPoint-${metadata.playOrder}" playOrder="${metadata.playOrder}"><navLabel><text>$cleanTitle</text></navLabel><content src="$pageHref"/></navPoint>"""
                    metadata.playOrder++; tocAdded = true
                }

                val dims = imageFile.inputStream().use { safeDimensions(it) }
                epubZip.addFile(imageFile, ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$imageHref" })
                val xhtml = createXhtmlPage(cleanTitle, pageIndex, imageHref, dims.first, dims.second)
                epubZip.addStream(xhtml.byteInputStream(), ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$pageHref" })
            } catch (t: Throwable) {
                logError("Skipping image '${imageFile.path}' due to error", t)
            }
        }
    }

    private fun findOpfFile(extractedEpubDir: File): File? {
        val containerFile = File(extractedEpubDir, CONTAINER_XML_PATH)
        if (!containerFile.exists()) return null
        val containerXml = containerFile.readText()
        val opfPath = Regex("""full-path="([^"]+)"""").find(containerXml)?.groupValues?.get(1)
        return opfPath?.let { File(extractedEpubDir, it) }
    }

    private fun extractToChapterFolders(inputFile: File, tempDir: File): List<File> {
        val extractionDir = Files.createTempDirectory(tempDir.toPath(), "extract-").toFile()
        if (inputFile.extension.equals("epub", true)) {
            extractZip(inputFile, extractionDir)
            val opfFile = findOpfFile(extractionDir) ?: return emptyList()
            val opfParent = opfFile.parentFile
            val chapterFolders = mutableMapOf<String, MutableList<File>>()
            val slugRegex = Regex("""^(.*?)_page_\d+\..*$""")
            val imageFiles = opfParent.resolve("images").listFiles() ?: emptyArray()

            for (imageFile in imageFiles) {
                val slug = slugRegex.find(imageFile.name)?.groupValues?.get(1) ?: "chapter-unknown"
                chapterFolders.getOrPut(slug) { mutableListOf() }.add(imageFile)
            }
            val chapterStructureDir = Files.createTempDirectory(tempDir.toPath(), "chapters-").toFile()
            return chapterFolders.map { (rawSlug, images) ->
                val chapterDir = File(chapterStructureDir, rawSlug)
                chapterDir.mkdirs()
                images.forEach { img -> img.copyTo(File(chapterDir, img.name)) }
                chapterDir
            }
        } else {
            extractZip(inputFile, extractionDir)
            return extractionDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
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

    fun processLocalFile(inputFile: File, customTitle: String?, outputFormat: String, tempDirectory: File) {
        val mangaTitle = customTitle ?: inputFile.nameWithoutExtension
        val isReprocessing = inputFile.extension.equals(outputFormat, true)

        if (isReprocessing) {
            println("\nFixing file structure and sorting for: ${inputFile.name}")
        } else {
            println("\nConverting ${inputFile.name} to .$outputFormat...")
        }

        val tempProcessingDir = Files.createTempDirectory(tempDirectory.toPath(), "manga-process-").toFile()
        // When reprocessing, create the new file in a temp dir to avoid overwriting the source until success
        val finalOutputFile = inputFile
        val tempOutputFile = if (isReprocessing) File(tempProcessingDir, inputFile.name) else File(inputFile.parentFile, "$mangaTitle.$outputFormat")

        try {
            val chapterFolders = extractToChapterFolders(inputFile, tempProcessingDir)
            if (chapterFolders.isEmpty()) {
                logError("Could not find any chapters in ${inputFile.name}. Aborting."); return
            }

            if (outputFormat.equals("cbz", true)) {
                createCbzFromFolders(mangaTitle, chapterFolders, tempOutputFile)
            } else {
                createEpubFromFolders(mangaTitle, chapterFolders, tempOutputFile)
            }

            if (isReprocessing && tempOutputFile.exists()) {
                Files.move(tempOutputFile.toPath(), finalOutputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                println("Successfully fixed and updated ${finalOutputFile.name}")
            }
        } catch (e: Exception) {
            logError("An error occurred during file processing for ${inputFile.name}.", e)
        } finally {
            if (tempProcessingDir.exists()) tempProcessingDir.deleteRecursively()
        }
    }
}
