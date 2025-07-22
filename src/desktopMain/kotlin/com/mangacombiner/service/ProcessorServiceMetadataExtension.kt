package com.mangacombiner.service

import com.mangacombiner.util.Logger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.*
import java.util.zip.*
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException

/**
 * Extension function to update EPUB metadata with scraped series information.
 */
suspend fun ProcessorService.updateEpubMetadata(
    epubFile: File,
    seriesMetadata: SeriesMetadata,
    sourceUrl: String
): Boolean = withContext(Dispatchers.IO) {
    try {
        Logger.logInfo("Starting metadata update for EPUB: ${epubFile.name}")

        val tempDir = File.createTempFile("epub-update-", "-temp").apply {
            delete()
            mkdirs()
        }

        try {
            Logger.logInfo("Extracting EPUB contents...")
            if (!extractEpubToTemp(epubFile, tempDir)) {
                Logger.logError("Failed to extract EPUB contents")
                return@withContext false
            }

            Logger.logInfo("Updating OPF metadata...")
            if (!updateEpubOpfMetadata(tempDir, seriesMetadata, sourceUrl)) {
                Logger.logError("Failed to update OPF metadata")
                return@withContext false
            }

            seriesMetadata.coverImageUrl?.let { coverUrl ->
                Logger.logInfo("Downloading and adding cover image...")
                if (!updateEpubCoverImage(tempDir, coverUrl)) {
                    Logger.logWarn("Failed to update cover image, but continuing with other metadata")
                }
            }

            Logger.logInfo("Repackaging EPUB...")
            if (!repackageEpubFromTemp(tempDir, epubFile)) {
                Logger.logError("Failed to repackage EPUB")
                return@withContext false
            }

            // Return true without logging duplicate success message - let the caller handle final success logging
            true

        } finally {
            tempDir.deleteRecursively()
        }

    } catch (e: Exception) {
        Logger.logError("Error updating EPUB metadata: ${e.message}", e)
        false
    }
}

private fun extractEpubToTemp(epubFile: File, extractDir: File): Boolean {
    return try {
        ZipFile(epubFile).extractAll(extractDir.absolutePath)
        Logger.logDebug { "Successfully extracted EPUB to: ${extractDir.absolutePath}" }
        true
    } catch (e: ZipException) {
        Logger.logError("Failed to extract EPUB: ${e.message}", e)
        false
    } catch (e: Exception) {
        Logger.logError("An unexpected error occurred during EPUB extraction: ${e.message}", e)
        false
    }
}

private fun updateEpubOpfMetadata(tempDir: File, seriesMetadata: SeriesMetadata, sourceUrl: String): Boolean {
    return try {
        val opfFile = findEpubOpfFile(tempDir)
        if (opfFile == null) {
            Logger.logError("Could not find OPF file in EPUB structure")
            return false
        }

        Logger.logDebug { "Found OPF file: ${opfFile.relativeTo(tempDir).path}" }

        val opfContent = opfFile.readText()
        val document = Jsoup.parse(opfContent, "", org.jsoup.parser.Parser.xmlParser())

        updateEpubMetadataElement(document, "title", seriesMetadata.title)

        seriesMetadata.authors?.let { authors ->
            document.select("metadata creator, metadata dc\\:creator").remove()
            val metadataElement = document.selectFirst("metadata")
            authors.forEach { author ->
                metadataElement?.appendElement("dc:creator")?.text(author)
            }
        }

        seriesMetadata.artists?.let { artists ->
            document.select("metadata contributor, metadata dc\\:contributor").remove()
            val metadataElement = document.selectFirst("metadata")
            artists.forEach { artist ->
                metadataElement?.appendElement("dc:contributor")?.text(artist)
            }
        }

        seriesMetadata.genres?.let { genres ->
            document.select("metadata subject, metadata dc\\:subject").remove()
            val metadataElement = document.selectFirst("metadata")
            genres.forEach { genre ->
                metadataElement?.appendElement("dc:subject")?.text(genre)
            }
        }

        updateEpubMetadataElement(document, "source", sourceUrl)

        val description = buildString {
            seriesMetadata.type?.let { append("Type: $it\n") }
            seriesMetadata.status?.let { append("Status: $it\n") }
            seriesMetadata.release?.let { append("Released: $it\n") }
            if (length > 0) append("\nSource: $sourceUrl")
        }

        if (description.isNotBlank()) {
            updateEpubMetadataElement(document, "description", description)
        }

        opfFile.writeText(document.outerHtml())

        Logger.logDebug { "Successfully updated OPF metadata" }
        true

    } catch (e: Exception) {
        Logger.logError("Failed to update OPF metadata: ${e.message}", e)
        false
    }
}

private fun findEpubOpfFile(tempDir: File): File? {
    val containerFile = File(tempDir, "META-INF/container.xml")
    if (containerFile.exists()) {
        try {
            val containerContent = containerFile.readText()
            val containerDoc = Jsoup.parse(containerContent, "", org.jsoup.parser.Parser.xmlParser())
            val rootfileElement = containerDoc.selectFirst("rootfile")
            val fullPath = rootfileElement?.attr("full-path")
            if (!fullPath.isNullOrBlank()) {
                val opfFile = File(tempDir, fullPath)
                if (opfFile.exists()) {
                    return opfFile
                }
            }
        } catch (e: Exception) {
            Logger.logDebug { "Could not parse container.xml: ${e.message}" }
        }
    }

    val commonPaths = listOf(
        "OEBPS/content.opf",
        "OEBPS/package.opf",
        "content.opf",
        "package.opf"
    )

    for (path in commonPaths) {
        val file = File(tempDir, path)
        if (file.exists()) {
            return file
        }
    }

    return tempDir.walkTopDown()
        .filter { it.isFile && it.extension == "opf" }
        .firstOrNull()
}

private fun updateEpubMetadataElement(document: org.jsoup.nodes.Document, elementName: String, value: String) {
    val selector = "metadata $elementName, metadata dc\\:$elementName"
    var element = document.selectFirst(selector)

    if (element != null) {
        element.text(value)
        Logger.logDebug { "Updated existing $elementName: $value" }
    } else {
        val metadataElement = document.selectFirst("metadata")
        element = metadataElement?.appendElement("dc:$elementName")
        element?.text(value)
        Logger.logDebug { "Added new $elementName: $value" }
    }
}

private suspend fun updateEpubCoverImage(tempDir: File, coverUrl: String): Boolean {
    return try {
        val client = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 30000
            }
        }

        try {
            val response = client.get(coverUrl) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                header(HttpHeaders.Accept, "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            }

            if (response.status != HttpStatusCode.OK) {
                Logger.logWarn("Failed to download cover image: ${response.status}")
                return false
            }

            val imageBytes = response.body<ByteArray>()

            val imageExtension = when {
                coverUrl.contains(".jpg", ignoreCase = true) || coverUrl.contains(".jpeg", ignoreCase = true) -> "jpg"
                coverUrl.contains(".png", ignoreCase = true) -> "png"
                coverUrl.contains(".gif", ignoreCase = true) -> "gif"
                coverUrl.contains(".webp", ignoreCase = true) -> "webp"
                else -> "jpg"
            }

            val imagesDir = listOf(
                File(tempDir, "OEBPS/images"),
                File(tempDir, "OEBPS/Images"),
                File(tempDir, "images"),
                File(tempDir, "OEBPS")
            ).firstOrNull { it.exists() } ?: File(tempDir, "OEBPS/images").apply { mkdirs() }

            val coverFile = File(imagesDir, "cover.$imageExtension")
            coverFile.writeBytes(imageBytes)

            // Calculate the correct relative path for OPF reference
            // If the cover is at tempDir/OEBPS/images/cover.webp,
            // the OPF reference should be images/cover.webp (relative to OEBPS directory)
            val oebpsDir = File(tempDir, "OEBPS")
            val relativePath = if (coverFile.startsWith(oebpsDir)) {
                coverFile.relativeTo(oebpsDir).path.replace("\\", "/")
            } else {
                // Fallback for edge cases
                "images/cover.$imageExtension"
            }

            updateEpubCoverReference(tempDir, relativePath)

            Logger.logDebug { "Successfully updated cover image: ${coverFile.name} with OPF path: $relativePath" }
            true

        } finally {
            client.close()
        }

    } catch (e: Exception) {
        Logger.logError("Failed to update cover image: ${e.message}", e)
        false
    }
}

private fun updateEpubCoverReference(tempDir: File, coverPath: String) {
    val opfFile = findEpubOpfFile(tempDir) ?: return
    val opfContent = opfFile.readText()
    val document = Jsoup.parse(opfContent, "", org.jsoup.parser.Parser.xmlParser())

    var coverItem = document.selectFirst("manifest item[id=cover], manifest item[id=cover-image]")
    if (coverItem == null) {
        val manifest = document.selectFirst("manifest")
        coverItem = manifest?.appendElement("item")
        coverItem?.attr("id", "cover-image")
    }

    coverItem?.attr("href", coverPath)
    val mediaType = when (File(coverPath).extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
    coverItem?.attr("media-type", mediaType)

    var coverMeta = document.selectFirst("metadata meta[name=cover]")
    if (coverMeta == null) {
        val metadata = document.selectFirst("metadata")
        coverMeta = metadata?.appendElement("meta")
        coverMeta?.attr("name", "cover")
    }

    coverMeta?.attr("content", "cover-image")

    opfFile.writeText(document.outerHtml())
}

private fun repackageEpubFromTemp(tempDir: File, outputFile: File): Boolean {
    return try {
        ZipOutputStream(FileOutputStream(outputFile)).use { zipOut ->
            val mimetypeFile = File(tempDir, "mimetype")
            if (mimetypeFile.exists()) {
                val entry = ZipEntry("mimetype")
                entry.method = ZipEntry.STORED
                entry.size = mimetypeFile.length()
                val crc = CRC32()
                crc.update(mimetypeFile.readBytes())
                entry.crc = crc.value

                zipOut.putNextEntry(entry)
                mimetypeFile.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()
            }

            tempDir.walkTopDown()
                .filter { it.isFile && it.name != "mimetype" }
                .forEach { file ->
                    val relativePath = file.relativeTo(tempDir).path.replace("\\", "/")
                    val entry = ZipEntry(relativePath)
                    entry.method = ZipEntry.DEFLATED

                    zipOut.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
        }

        Logger.logDebug { "Successfully repackaged EPUB: ${outputFile.name}" }
        true

    } catch (e: Exception) {
        Logger.logError("Failed to repackage EPUB: ${e.message}", e)
        false
    }
}
