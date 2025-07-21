package com.mangacombiner.service

import com.mangacombiner.util.getImageDimensions
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import java.io.File
import java.util.UUID
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
        const val COVER_HREF = "images/cover.jpeg"
    }

    class EpubMetadata {
        val manifestItems = mutableListOf<String>()
        val spineItems = mutableListOf<String>()
        val navPoints = mutableListOf<String>()
        var playOrder = 1
        var totalPages = 0
    }

    fun addEpubCoreFiles(epubZip: ZipFile) {
        val mimetypeParams = ZipParameters().apply {
            fileNameInZip = "mimetype"
            compressionMethod = CompressionMethod.STORE
        }
        epubZip.addStream(MIMETYPE.byteInputStream(Charsets.UTF_8), mimetypeParams)

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
        val imageDim = getImageDimensions(coverImage.absolutePath)?.let { it.width to it.height }
            ?: (DEFAULT_IMAGE_WIDTH to DEFAULT_IMAGE_HEIGHT)

        val coverPageId = "cover-page"
        val coverPageHref = "text/cover.xhtml"

        // Add cover image to zip
        val coverZipParams = ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$COVER_HREF" }
        epubZip.addFile(coverImage, coverZipParams)
        metadata.manifestItems.add("""<item id="$COVER_ID" href="$COVER_HREF" media-type="image/jpeg"/>""")

        // Create and add XHTML page for the cover
        val xhtmlContent = createXhtmlPage("Cover", 0, "../$COVER_HREF", imageDim.first, imageDim.second)
        val pageParams = ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$coverPageHref" }
        epubZip.addStream(xhtmlContent.byteInputStream(Charsets.UTF_8), pageParams)
        metadata.manifestItems.add("""<item id="$coverPageId" href="$coverPageHref" media-type="application/xhtml+xml"/>""")

        // Add to spine but mark as non-linear so it doesn't appear in the reading flow twice
        metadata.spineItems.add("""<itemref idref="$coverPageId" linear="no"/>""")
    }

    fun addChapterToEpub(
        images: List<File>,
        chapterName: String,
        metadata: EpubMetadata,
        epubZip: ZipFile,
        imageZipParams: ZipParameters = ZipParameters()
    ) {
        var firstPageOfChapter = true
        val safeChapterName = chapterName.replace(Regex("""\s+"""), "_").replace(Regex("""[^a-zA-Z0-9_-]"""), "")

        images.forEachIndexed { index, imageFile ->
            val pageIndex = metadata.totalPages + index + 1
            val imageDim = getImageDimensions(imageFile.absolutePath)?.let { it.width to it.height }
                ?: (DEFAULT_IMAGE_WIDTH to DEFAULT_IMAGE_HEIGHT)

            val imageId = "img_${safeChapterName}_$pageIndex"
            val imageHref = "images/$imageId.${imageFile.extension}"
            val pageId = "page_${safeChapterName}_$pageIndex"
            val pageHref = "text/$pageId.xhtml"

            val finalImageParams = ZipParameters(imageZipParams).apply { fileNameInZip = "$OPF_BASE_PATH/$imageHref" }
            epubZip.addFile(imageFile, finalImageParams)
            metadata.manifestItems.add(
                """<item id="$imageId" href="$imageHref" media-type="image/${imageFile.extension.lowercase()}"/>"""
            )

            val xhtmlContent = createXhtmlPage(chapterName, pageIndex, "../$imageHref", imageDim.first, imageDim.second)
            val pageParams = ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$pageHref" }
            epubZip.addStream(xhtmlContent.byteInputStream(Charsets.UTF_8), pageParams)
            metadata.manifestItems.add("""<item id="$pageId" href="$pageHref" media-type="application/xhtml+xml"/>""")
            metadata.spineItems.add("""<itemref idref="$pageId"/>""")

            if (firstPageOfChapter) {
                val cleanChapterName = chapterName.replace(Regex("[_-]"), " ").replaceFirstChar { it.titlecase() }
                metadata.navPoints.add(createNavPoint(metadata.playOrder, cleanChapterName, pageHref))
                metadata.playOrder++
                firstPageOfChapter = false
            }
        }
        metadata.totalPages += images.size
    }

    fun addEpubMetadataFiles(epubZip: ZipFile, title: String, seriesUrl: String?, seriesMetadata: SeriesMetadata?, metadata: EpubMetadata) {
        val bookId = UUID.randomUUID().toString()
        val opfContent = createContentOpf(title, bookId, seriesUrl, seriesMetadata, metadata)
        val opfParams = ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/content.opf" }
        epubZip.addStream(opfContent.byteInputStream(Charsets.UTF_8), opfParams)
        val ncxContent = createTocNcx(title, bookId, metadata.navPoints)
        val ncxParams = ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/toc.ncx" }
        epubZip.addStream(ncxContent.byteInputStream(Charsets.UTF_8), ncxParams)
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
                <title>${title.escapeXml()} - Page $pageIndex</title>
                <meta name="viewport" content="width=$w, height=$h"/>
                <style type="text/css">
                    body { margin: 0; padding: 0; }
                    div { text-align: center; }
                    img { max-width: 100%; max-height: 100vh; }
                </style>
            </head>
            <body>
                <div>
                    <img src="$imageHref" alt="${title.escapeXml()} - Page $pageIndex"/>
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
        metadataBuilder.appendLine("""    <dc:title>${(seriesMetadata?.title ?: title).escapeXml()}</dc:title>""")
        metadataBuilder.appendLine("""    <dc:creator opf:role="aut">MangaCombiner</dc:creator>""")

        seriesMetadata?.authors?.forEach { author ->
            metadataBuilder.appendLine("""    <dc:creator opf:role="aut">${author.escapeXml()}</dc:creator>""")
        }
        seriesMetadata?.artists?.forEach { artist ->
            metadataBuilder.appendLine("""    <dc:creator opf:role="art">${artist.escapeXml()}</dc:creator>""")
        }

        metadataBuilder.appendLine("""    <dc:language>en</dc:language>""")
        metadataBuilder.appendLine("""    <dc:identifier id="BookId" opf:scheme="UUID">$bookId</dc:identifier>""")

        seriesMetadata?.release?.let {
            metadataBuilder.appendLine("""    <dc:date>${it.escapeXml()}</dc:date>""")
        }
        seriesMetadata?.genres?.forEach { genre ->
            metadataBuilder.appendLine("""    <dc:subject>${genre.escapeXml()}</dc:subject>""")
        }

        val descriptionParts = mutableListOf<String>()
        seriesMetadata?.type?.let { descriptionParts.add("Type: $it") }
        seriesMetadata?.status?.let { descriptionParts.add("Status: $it") }
        if (descriptionParts.isNotEmpty()) {
            metadataBuilder.appendLine("""    <dc:description>${descriptionParts.joinToString(" | ").escapeXml()}</dc:description>""")
        }

        if (seriesUrl != null) {
            metadataBuilder.appendLine("""    <dc:source>${seriesUrl.escapeXml()}</dc:source>""")
        }
        if (seriesMetadata?.coverImageUrl != null) {
            metadataBuilder.appendLine("""    <meta name="cover" content="$COVER_ID"/>""")
        }

        // Using a manually formatted string to avoid any issues with trimIndent() or leading newlines.
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

    private fun createTocNcx(title: String, bookId: String, navPoints: List<String>): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx version="2005-1" xmlns="http://www.daisy.org/z3986/2005/ncx/">
    <head>
        <meta name="dtb:uid" content="$bookId"/>
    </head>
    <docTitle>
        <text>${title.escapeXml()}</text>
    </docTitle>
    <navMap>
        ${navPoints.joinToString("\n        ")}
    </navMap>
</ncx>"""

    private fun createNavPoint(playOrder: Int, title: String, contentSrc: String): String = """
        <navPoint id="navPoint-$playOrder" playOrder="$playOrder">
            <navLabel><text>${title.escapeXml()}</text></navLabel>
            <content src="$contentSrc"/>
        </navPoint>
    """.trimIndent()
}
