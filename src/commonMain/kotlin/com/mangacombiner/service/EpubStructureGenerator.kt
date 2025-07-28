package com.mangacombiner.service

import com.mangacombiner.util.Logger
import com.mangacombiner.util.getImageDimensions
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import kotlin.text.Charsets

/**
 * Escapes special XML characters to prevent parsing errors.
 */
private fun String.escapeXml(): String {
    return this.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

internal class EpubStructureGenerator {

    private companion object {
        const val OPF_BASE_PATH = "OEBPS"
        const val MIMETYPE = "application/epub+zip"
        const val DEFAULT_IMAGE_WIDTH = 1200
        const val DEFAULT_IMAGE_HEIGHT = 1920
        const val COVER_ID = "cover-image"
    }

    class EpubMetadata {
        val manifestItems = mutableListOf<String>()
        val spineItems = mutableListOf<String>()
        val navPoints = mutableListOf<String>()
        var playOrder = 1
        var totalPages = 0
        var coverImagePath: String? = null // Track the actual cover image path
    }

    fun addEpubCoreFiles(epubZip: ZipFile) {
        val mimetypeBytes = MIMETYPE.toByteArray(Charsets.UTF_8)
        val crc = CRC32()
        crc.update(mimetypeBytes)

        val mimetypeParams = ZipParameters().apply {
            fileNameInZip = "mimetype"
            compressionMethod = CompressionMethod.STORE
            entrySize = mimetypeBytes.size.toLong()
            entryCRC = crc.value
        }
        epubZip.addStream(mimetypeBytes.inputStream(), mimetypeParams)

        val containerParams = ZipParameters().apply {
            fileNameInZip = "META-INF/container.xml"
        }
        epubZip.addStream(createContainerXml().byteInputStream(Charsets.UTF_8), containerParams)
    }

    fun addCoverToEpub(
        coverImage: File,
        metadata: EpubMetadata,
        epubZip: ZipFile
    ) {
        if (!coverImage.exists() || !coverImage.canRead()) {
            Logger.logWarn("Cover image file does not exist or is not readable: ${coverImage.absolutePath}. Continuing without cover.")
            return
        }

        val imageDim = getImageDimensions(coverImage.absolutePath)?.let { it.width to it.height }
            ?: (DEFAULT_IMAGE_WIDTH to DEFAULT_IMAGE_HEIGHT)

        // Determine the correct file extension and media type from the actual file
        val fileExtension = coverImage.extension.lowercase()
        val coverImageName = "cover.$fileExtension"
        val coverHref = "images/$coverImageName"  // This should be relative path only
        val mediaType = getImageMediaType(fileExtension)

        val coverPageId = "cover-page"
        val coverPageHref = "text/cover.xhtml"

        Logger.logInfo("Adding cover image: $coverImageName with dimensions ${imageDim.first}x${imageDim.second}")

        try {
            // Add cover image to zip - make sure path doesn't get duplicated
            val coverZipParams = ZipParameters().apply {
                fileNameInZip = "$OPF_BASE_PATH/$coverHref"  // This creates OEBPS/images/cover.ext
            }
            epubZip.addFile(coverImage, coverZipParams)
            metadata.manifestItems.add("""<item id="$COVER_ID" href="$coverHref" media-type="$mediaType"/>""")

            // Store the actual cover path for OPF metadata reference
            metadata.coverImagePath = coverHref
            Logger.logDebug { "Added cover image to EPUB at path: $OPF_BASE_PATH/$coverHref" }

            // Create and add XHTML page for the cover
            val xhtmlContent = createXhtmlPage("Cover", 0, "../$coverHref", imageDim.first, imageDim.second)
            val pageParams = ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$coverPageHref" }
            epubZip.addStream(xhtmlContent.byteInputStream(Charsets.UTF_8), pageParams)
            metadata.manifestItems.add("""<item id="$coverPageId" href="$coverPageHref" media-type="application/xhtml+xml"/>""")

            // Add to spine but mark as non-linear so it doesn't appear in the reading flow twice
            metadata.spineItems.add("""<itemref idref="$coverPageId" linear="no"/>""")
            Logger.logInfo("Successfully added cover image and cover page to EPUB")
        } catch (e: Exception) {
            Logger.logWarn("Failed to add cover image to EPUB: ${e.message}. Continuing without cover.", e)
            // Clear the cover path since adding failed, but don't fail the entire EPUB creation
            metadata.coverImagePath = null
        }
    }

    fun addChapterToEpub(
        images: List<File>,
        chapterName: String,
        metadata: EpubMetadata,
        epubZip: ZipFile,
        imageZipParams: ZipParameters = ZipParameters()
    ) {
        if (images.isEmpty()) {
            Logger.logWarn("No images provided for chapter: $chapterName")
            return
        }

        var firstPageOfChapter = true
        val safeChapterName = chapterName.replace(Regex("""\s+"""), "_").replace(Regex("""[^a-zA-Z0-9_-]"""), "")

        Logger.logDebug { "Adding chapter '$chapterName' with ${images.size} images" }

        images.forEachIndexed { index, imageFile ->
            if (!imageFile.exists() || !imageFile.canRead()) {
                Logger.logError("Image file does not exist or is not readable: ${imageFile.absolutePath}")
                return@forEachIndexed
            }

            val pageIndex = metadata.totalPages + index + 1
            val imageDim = getImageDimensions(imageFile.absolutePath)?.let { it.width to it.height }
                ?: (DEFAULT_IMAGE_WIDTH to DEFAULT_IMAGE_HEIGHT)

            val fileExtension = imageFile.extension.lowercase()
            val imageId = "img_${safeChapterName}_$pageIndex"
            val imageHref = "images/$imageId.$fileExtension"
            val pageId = "page_${safeChapterName}_$pageIndex"
            val pageHref = "text/$pageId.xhtml"

            try {
                // Add image to EPUB with proper error handling
                val finalImageParams = ZipParameters(imageZipParams).apply {
                    fileNameInZip = "$OPF_BASE_PATH/$imageHref"
                }
                epubZip.addFile(imageFile, finalImageParams)

                val mediaType = getImageMediaType(fileExtension)
                metadata.manifestItems.add(
                    """<item id="$imageId" href="$imageHref" media-type="$mediaType"/>"""
                )

                // Create XHTML page for the image
                val xhtmlContent = createXhtmlPage(chapterName, pageIndex, "../$imageHref", imageDim.first, imageDim.second)
                val pageParams = ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$pageHref" }
                epubZip.addStream(xhtmlContent.byteInputStream(Charsets.UTF_8), pageParams)
                metadata.manifestItems.add("""<item id="$pageId" href="$pageHref" media-type="application/xhtml+xml"/>""")
                metadata.spineItems.add("""<itemref idref="$pageId"/>""")

                // Add navigation point for the first page of each chapter
                if (firstPageOfChapter) {
                    val cleanChapterName = chapterName.replace(Regex("[_-]"), " ").replaceFirstChar { it.titlecase() }
                    metadata.navPoints.add(createNavPoint(metadata.playOrder, cleanChapterName, pageHref))
                    metadata.playOrder++
                    firstPageOfChapter = false
                }
            } catch (e: Exception) {
                Logger.logError("Failed to add image ${imageFile.name} to chapter $chapterName", e)
            }
        }

        metadata.totalPages += images.size
        Logger.logDebug { "Successfully added chapter '$chapterName' with ${images.size} pages" }
    }

    fun addEpubMetadataFiles(
        epubZip: ZipFile,
        title: String,
        seriesUrl: String?,
        seriesMetadata: SeriesMetadata?,
        metadata: EpubMetadata
    ) {
        val bookId = UUID.randomUUID().toString()

        try {
            // Create and add OPF file
            val opfContent = createContentOpf(title, bookId, seriesUrl, seriesMetadata, metadata)
            val opfParams = ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/content.opf" }
            epubZip.addStream(opfContent.byteInputStream(Charsets.UTF_8), opfParams)
            Logger.logDebug { "Added content.opf to EPUB" }

            // Create and add NCX file
            val ncxContent = createTocNcx(title, bookId, metadata.navPoints)
            val ncxParams = ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/toc.ncx" }
            epubZip.addStream(ncxContent.byteInputStream(Charsets.UTF_8), ncxParams)
            Logger.logDebug { "Added toc.ncx to EPUB" }
        } catch (e: Exception) {
            Logger.logError("Failed to add EPUB metadata files", e)
            throw e
        }
    }

    private fun getImageMediaType(extension: String): String {
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            else -> "image/jpeg" // Default fallback
        }
    }

    private fun createContainerXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles>
                <rootfile full-path="$OPF_BASE_PATH/content.opf" media-type="application/oebps-package+xml"/>
            </rootfiles>
        </container>
    """.trimIndent()

    private fun createXhtmlPage(title: String, pageIndex: Int, imageHref: String, w: Int, h: Int): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
        <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en">
            <head>
                <title>${title.escapeXml()}${if (pageIndex > 0) " - Page $pageIndex" else ""}</title>
                <meta name="viewport" content="width=$w, height=$h"/>
                <style type="text/css">
                    body { margin: 0; padding: 0; text-align: center; }
                    div { display: flex; justify-content: center; align-items: center; min-height: 100vh; }
                    img { max-width: 100%; max-height: 100vh; height: auto; width: auto; }
                </style>
            </head>
            <body>
                <div>
                    <img src="$imageHref" alt="${title.escapeXml()}${if (pageIndex > 0) " - Page $pageIndex" else ""}"/>
                </div>
            </body>
        </html>
    """.trimIndent()

    private fun createContentOpf(
        title: String,
        bookId: String,
        seriesUrl: String?,
        seriesMetadata: SeriesMetadata?,
        metadata: EpubMetadata
    ): String {
        val metadataBuilder = StringBuilder()

        // Title - prefer series metadata title over provided title
        val finalTitle = seriesMetadata?.title?.takeIf { it.isNotBlank() } ?: title
        metadataBuilder.appendLine("""    <dc:title>${finalTitle.escapeXml()}</dc:title>""")

        // Creator - always include MangaCombiner
        metadataBuilder.appendLine("""    <dc:creator opf:role="aut">MangaCombiner</dc:creator>""")

        // Authors from metadata
        seriesMetadata?.authors?.forEach { author ->
            if (author.isNotBlank()) {
                metadataBuilder.appendLine("""    <dc:creator opf:role="aut">${author.escapeXml()}</dc:creator>""")
            }
        }

        // Artists from metadata
        seriesMetadata?.artists?.forEach { artist ->
            if (artist.isNotBlank()) {
                metadataBuilder.appendLine("""    <dc:creator opf:role="art">${artist.escapeXml()}</dc:creator>""")
            }
        }

        // Standard metadata
        metadataBuilder.appendLine("""    <dc:language>en</dc:language>""")
        metadataBuilder.appendLine("""    <dc:identifier id="BookId" opf:scheme="UUID">$bookId</dc:identifier>""")

        // Release date
        seriesMetadata?.release?.takeIf { it.isNotBlank() }?.let {
            metadataBuilder.appendLine("""    <dc:date>${it.escapeXml()}</dc:date>""")
        }

        // Genres as subjects
        seriesMetadata?.genres?.forEach { genre ->
            if (genre.isNotBlank()) {
                metadataBuilder.appendLine("""    <dc:subject>${genre.escapeXml()}</dc:subject>""")
            }
        }

        // Description from scraped metadata
        seriesMetadata?.description?.takeIf { it.isNotBlank() }?.let {
            metadataBuilder.appendLine("""    <dc:description>${it.escapeXml()}</dc:description>""")
        }

        // Source URL
        seriesUrl?.takeIf { it.isNotBlank() }?.let {
            metadataBuilder.appendLine("""    <dc:source>${it.escapeXml()}</dc:source>""")
        }

        // Cover reference if available
        if (metadata.coverImagePath != null) {
            metadataBuilder.appendLine("""    <meta name="cover" content="$COVER_ID"/>""")
        }

        return """<?xml version="1.0" encoding="UTF-8"?>
<package version="2.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
${metadataBuilder.toString().trim()}
    </metadata>
    <manifest>
        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
        ${metadata.manifestItems.joinToString("\n        ")}
    </manifest>
    <spine toc="ncx">
        ${metadata.spineItems.joinToString("\n        ")}
    </spine>
</package>"""
    }

    private fun createTocNcx(title: String, bookId: String, navPoints: List<String>): String {
        val finalTitle = title.takeIf { it.isNotBlank() } ?: "Untitled"

        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx version="2005-1" xmlns="http://www.daisy.org/z3986/2005/ncx/">
    <head>
        <meta name="dtb:uid" content="$bookId"/>
        <meta name="dtb:depth" content="1"/>
        <meta name="dtb:totalPageCount" content="0"/>
        <meta name="dtb:maxPageNumber" content="0"/>
    </head>
    <docTitle>
        <text>${finalTitle.escapeXml()}</text>
    </docTitle>
    <navMap>
        ${navPoints.joinToString("\n        ")}
    </navMap>
</ncx>"""
    }

    private fun createNavPoint(playOrder: Int, title: String, contentSrc: String): String = """
        <navPoint id="navPoint-$playOrder" playOrder="$playOrder">
            <navLabel><text>${title.escapeXml()}</text></navLabel>
            <content src="$contentSrc"/>
        </navPoint>
    """.trimIndent()
}
