package com.mangacombiner.service

import com.mangacombiner.util.Logger
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.text.Charsets

internal class EpubStructureGenerator {

    private companion object {
        const val OPF_BASE_PATH = "OEBPS"
        const val MIMETYPE = "application/epub+zip"
        const val DEFAULT_IMAGE_WIDTH = 1200
        const val DEFAULT_IMAGE_HEIGHT = 1920
    }

    /**
     * Holds the state of the EPUB file as it's being built.
     */
    class EpubMetadata {
        val manifestItems = mutableListOf<String>()
        val spineItems = mutableListOf<String>()
        val navPoints = mutableListOf<String>()
        var playOrder = 1
        var totalPages = 0
    }

    /**
     * Adds the mandatory, uncompressed 'mimetype' file and the container.xml file.
     */
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

    /**
     * Adds a chapter to the EPUB with all its images.
     */
    @Suppress("SwallowedException")
    fun addChapterToEpub(images: List<File>, chapterName: String, metadata: EpubMetadata, epubZip: ZipFile) {
        var firstPageOfChapter = true
        // Sanitize chapter name to be valid for file paths and XML IDs
        val safeChapterName = chapterName.replace(Regex("""\s+"""), "_").replace(Regex("""[^a-zA-Z0-9_-]"""), "")

        images.forEachIndexed { index, imageFile ->
            val pageIndex = metadata.totalPages + index + 1
            val imageDim = try {
                ImageIO.read(imageFile)?.let { it.width to it.height } ?: (DEFAULT_IMAGE_WIDTH to DEFAULT_IMAGE_HEIGHT)
            } catch (e: IOException) {
                Logger.logDebug {
                    "Could not read image dimensions for ${imageFile.name}, using defaults. Error: ${e.message}"
                }
                DEFAULT_IMAGE_WIDTH to DEFAULT_IMAGE_HEIGHT
            }

            // Use the sanitized chapter name for IDs and HREFs
            val imageId = "img_${safeChapterName}_$pageIndex"
            val imageHref = "images/$imageId.${imageFile.extension}"
            val pageId = "page_${safeChapterName}_$pageIndex"
            val pageHref = "text/$pageId.xhtml"

            // Add image file
            val imageParams = ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$imageHref" }
            epubZip.addFile(imageFile, imageParams)
            metadata.manifestItems.add(
                """<item id="$imageId" href="$imageHref" media-type="image/${imageFile.extension}"/>"""
            )

            // Add XHTML page file
            val xhtmlContent = createXhtmlPage(chapterName, pageIndex, "../$imageHref", imageDim.first, imageDim.second)
            val pageParams = ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/$pageHref" }
            epubZip.addStream(xhtmlContent.byteInputStream(Charsets.UTF_8), pageParams)
            metadata.manifestItems.add("""<item id="$pageId" href="$pageHref" media-type="application/xhtml+xml"/>""")
            metadata.spineItems.add("""<itemref idref="$pageId"/>""")

            // Add chapter entry to navMap (table of contents) only for first page of chapter
            if (firstPageOfChapter) {
                val cleanChapterName = chapterName.replace(Regex("[_-]"), " ").replaceFirstChar { it.titlecase() }
                // Use current playOrder value and then increment it
                metadata.navPoints.add(createNavPoint(metadata.playOrder, cleanChapterName, pageHref))
                metadata.playOrder++
                firstPageOfChapter = false
            }
        }
        metadata.totalPages += images.size
    }

    /**
     * Adds the final metadata files (content.opf, toc.ncx) using the collected metadata.
     */
    fun addEpubMetadataFiles(epubZip: ZipFile, title: String, metadata: EpubMetadata) {
        val bookId = UUID.randomUUID().toString()

        // Add content.opf
        val opfContent = createContentOpf(title, bookId, metadata.manifestItems, metadata.spineItems)
        val opfParams = ZipParameters().apply { fileNameInZip = "$OPF_BASE_PATH/content.opf" }
        epubZip.addStream(opfContent.byteInputStream(Charsets.UTF_8), opfParams)

        // Add toc.ncx
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
                <title>$title - Page $pageIndex</title>
                <meta name="viewport" content="width=$w, height=$h"/>
                <style type="text/css">
                    body { margin: 0; padding: 0; }
                    img { width: 100%; height: 100%; object-fit: contain; }
                </style>
            </head>
            <body>
                <div><img src="$imageHref" alt="Page $pageIndex"/></div>
            </body>
        </html>
    """.trimIndent()

    private fun createContentOpf(
        title: String,
        bookId: String,
        manifestItems: List<String>,
        spineItems: List<String>
    ): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package version="2.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                <dc:title>$title</dc:title>
                <dc:creator opf:role="aut">MangaCombiner</dc:creator>
                <dc:language>en</dc:language>
                <dc:identifier id="BookId" opf:scheme="UUID">$bookId</dc:identifier>
            </metadata>
            <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                ${manifestItems.joinToString("\n        ")}
            </manifest>
            <spine toc="ncx">
                ${spineItems.joinToString("\n        ")}
            </spine>
        </package>
    """.trimIndent()

    private fun createTocNcx(title: String, bookId: String, navPoints: List<String>): String = """
        <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
        <ncx version="2005-1" xmlns="http://www.daisy.org/z3986/2005/ncx/">
            <head>
                <meta name="dtb:uid" content="$bookId"/>
            </head>
            <docTitle><text>$title</text></docTitle>
            <navMap>
                ${navPoints.joinToString("\n        ")}
            </navMap>
        </ncx>
    """.trimIndent()

    private fun createNavPoint(playOrder: Int, title: String, contentSrc: String): String = """
        <navPoint id="navPoint-$playOrder" playOrder="$playOrder">
            <navLabel><text>$title</text></navLabel>
            <content src="$contentSrc"/>
        </navPoint>
    """.trimIndent()
}
