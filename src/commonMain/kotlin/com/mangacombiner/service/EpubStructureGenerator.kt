package com.mangacombiner.service

import com.mangacombiner.model.OpfMetadata
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

    fun addEpubMetadataFiles(epubZip: ZipFile, title: String, seriesUrl: String?, metadata: EpubMetadata) {
        val bookId = UUID.randomUUID().toString()
        val opfContent = createContentOpf(title, bookId, seriesUrl, metadata.manifestItems, metadata.spineItems)
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
                    body, html { padding: 0; margin: 0; }
                    svg { padding: 0; margin: 0; }
                </style>
            </head>
            <body>
                <svg xmlns="http://www.w3.org/2000/svg" version="1.1" xmlns:xlink="http://www.w3.org/1999/xlink"
                     width="100%" height="100%" viewBox="0 0 $w $h">
                    <title>${title.escapeXml()} - Page $pageIndex</title>
                    <image width="$w" height="$h" xlink:href="$imageHref"/>
                </svg>
            </body>
        </html>
    """.trimIndent()

    private fun createContentOpf(
        title: String,
        bookId: String,
        seriesUrl: String?,
        manifestItems: List<String>,
        spineItems: List<String>
    ): String {
        val sourceTag = if (seriesUrl != null) "        <dc:source>$seriesUrl</dc:source>" else ""
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <package version="2.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                <dc:title>${title.escapeXml()}</dc:title>
                <dc:creator opf:role="aut">${OpfMetadata.Defaults.CREATOR}</dc:creator>
                <dc:language>en</dc:language>
                <dc:identifier id="BookId" opf:scheme="UUID">$bookId</dc:identifier>
$sourceTag
            </metadata>
            <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                ${manifestItems.joinToString("\n                ")}
            </manifest>
            <spine toc="ncx">
                ${spineItems.joinToString("\n                ")}
            </spine>
        </package>
        """.trimIndent()
    }

    private fun createTocNcx(title: String, bookId: String, navPoints: List<String>): String = """
        <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
        <ncx version="2005-1" xmlns="http://www.daisy.org/z3986/2005/ncx/">
            <head>
                <meta name="dtb:uid" content="$bookId"/>
            </head>
            <docTitle><text>${title.escapeXml()}</text></docTitle>
            <navMap>
                ${navPoints.joinToString("\n        ")}
            </navMap>
        </ncx>
    """.trimIndent()

    private fun createNavPoint(playOrder: Int, title: String, contentSrc: String): String = """
        <navPoint id="navPoint-$playOrder" playOrder="$playOrder">
            <navLabel><text>${title.escapeXml()}</text></navLabel>
            <content src="$contentSrc"/>
        </navPoint>
    """.trimIndent()
}
