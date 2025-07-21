package com.mangacombiner.service

import com.mangacombiner.util.*
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.plugins.jpeg.JPEGImageWriteParam
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

class ProcessorService(
    private val fileConverter: FileConverter
) {
    internal companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
        const val FAILURES_FILE = "failures.json"
    }

    private val jsonSerializer = Json { prettyPrint = true }

    private suspend fun processImage(
        inputFile: File,
        outputFile: File,
        maxWidth: Int?,
        jpegQuality: Int?
    ): File {
        val dims = getImageDimensions(inputFile.path) ?: return inputFile
        val needsResize = maxWidth != null && dims.width > maxWidth
        val isJpeg = inputFile.extension.equals("jpg", true) || inputFile.extension.equals("jpeg", true)
        val needsRecompress = isJpeg && jpegQuality != null

        if (!needsResize && !needsRecompress) {
            return inputFile
        }

        withContext(Dispatchers.IO) {
            val image: BufferedImage = ImageIO.read(inputFile) ?: return@withContext

            var imageToProcess = image

            if (needsResize) {
                val newHeight = (image.height.toDouble() / image.width * maxWidth!!).roundToInt()
                val imageType = if (isJpeg) BufferedImage.TYPE_INT_RGB else image.type
                val resized = BufferedImage(maxWidth, newHeight, imageType)
                val g = resized.createGraphics()
                g.drawImage(image, 0, 0, maxWidth, newHeight, null)
                g.dispose()
                imageToProcess = resized
            }

            if (needsRecompress) {
                val writer = ImageIO.getImageWritersByFormatName("jpg").next()
                val writeParam = writer.defaultWriteParam.apply {
                    compressionMode = JPEGImageWriteParam.MODE_EXPLICIT
                    compressionQuality = jpegQuality!! / 100f
                }
                outputFile.outputStream().use { fos ->
                    val ios = ImageIO.createImageOutputStream(fos)
                    writer.output = ios
                    writer.write(null, IIOImage(imageToProcess, null, null), writeParam)
                    writer.dispose()
                    ios.close()
                }
            } else {
                ImageIO.write(imageToProcess, inputFile.extension, outputFile)
            }
        }
        return outputFile
    }

    private fun String.isImageFile(): Boolean =
        substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS

    private fun sortChapterFolders(folders: List<File>): List<File> {
        return folders.sortedWith(compareBy(naturalSortComparator) { it.name })
    }

    suspend fun createEpubFromFolders(
        mangaTitle: String,
        chapterFolders: List<File>,
        outputFile: File,
        seriesUrl: String? = null,
        failedChapters: Map<String, List<String>>? = null,
        seriesMetadata: SeriesMetadata? = null,
        maxWidth: Int? = null,
        jpegQuality: Int? = null
    ) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        Logger.logInfo("Creating EPUB archive: ${outputFile.name}...")

        val epubGenerator = EpubStructureGenerator()
        val metadata = EpubStructureGenerator.EpubMetadata()
        val sortedFolders = sortChapterFolders(chapterFolders)
        val tempImageDir = File(outputFile.parent, "epub-temp-${System.currentTimeMillis()}").apply { mkdirs() }
        var coverImageFile: File? = null

        try {
            // Download cover image if available
            if (seriesMetadata?.coverImageUrl != null) {
                val client = createHttpClient(null)
                try {
                    Logger.logDebug { "Downloading cover image from ${seriesMetadata.coverImageUrl}" }
                    val extension = seriesMetadata.coverImageUrl.substringAfterLast('.', "jpg").substringBefore('?')
                    coverImageFile = File(tempImageDir, "cover.$extension")
                    val response = client.get(seriesMetadata.coverImageUrl)
                    if(response.status.isSuccess()) {
                        response.bodyAsChannel().copyAndClose(coverImageFile.writeChannel())
                    } else {
                        Logger.logError("Failed to download cover image: Status ${response.status}")
                        coverImageFile = null
                    }
                } catch (e: Exception) {
                    Logger.logError("Error downloading cover image", e)
                    coverImageFile = null
                } finally {
                    client.close()
                }
            }


            ZipFile(outputFile).use { epubZip ->
                epubGenerator.addEpubCoreFiles(epubZip)

                if (coverImageFile != null) {
                    epubGenerator.addCoverToEpub(coverImageFile, metadata, epubZip)
                }

                for (folder in sortedFolders) {
                    coroutineContext.ensureActive()
                    val images = folder.listFiles()?.filter { it.isFile && it.name.isImageFile() }?.sorted()
                    if (!images.isNullOrEmpty()) {
                        val processedImages = images.mapIndexed { index, img ->
                            val tempFile = File(tempImageDir, "${folder.name}_${index}.${img.extension}")
                            processImage(img, tempFile, maxWidth, jpegQuality)
                        }

                        // Always store images without re-compressing
                        val imageZipParams = ZipParameters().apply {
                            compressionMethod = CompressionMethod.STORE
                        }
                        epubGenerator.addChapterToEpub(processedImages, folder.name, metadata, epubZip, imageZipParams)
                    }
                }

                if (!failedChapters.isNullOrEmpty()) {
                    val failuresJson = jsonSerializer.encodeToString(failedChapters)
                    // Text files are compressed by default
                    val failuresParams = ZipParameters().apply { fileNameInZip = "OEBPS/$FAILURES_FILE" }
                    epubZip.addStream(failuresJson.byteInputStream(), failuresParams)
                    metadata.manifestItems.add("""<item id="failures" href="$FAILURES_FILE" media-type="application/json"/>""")
                }
                epubGenerator.addEpubMetadataFiles(epubZip, mangaTitle, seriesUrl, seriesMetadata, metadata)
            }
            Logger.logInfo("Successfully created: ${outputFile.name}")
        } catch (e: Exception) {
            Logger.logError("Failed to create EPUB file ${outputFile.name}", e)
        } finally {
            tempImageDir.deleteRecursively()
        }
    }

    fun getChaptersAndInfoFromFile(file: File): Triple<List<String>, String?, Map<String, List<String>>> {
        Logger.logDebug { "Analyzing file for chapters and metadata: ${file.path}" }
        if (!file.exists() || !file.isFile) {
            Logger.logError("Cannot analyze file, it does not exist or is not a file: ${file.path}")
            return Triple(emptyList(), null, emptyMap())
        }

        val (slugs, url) = when (file.extension.lowercase()) {
            "epub" -> ZipUtils.inferChapterSlugsFromEpub(file) to ZipUtils.getSourceUrlFromEpub(file)
            else -> {
                Logger.logError("Unsupported file type for chapter analysis: ${file.extension}")
                emptySet<String>() to null
            }
        }
        val failedItems = ZipUtils.getFailedItems(file, jsonSerializer)
        return Triple(slugs.sortedWith(naturalSortComparator), url, failedItems)
    }

    fun extractChaptersToDirectory(zipFile: File, chaptersToKeep: List<String>, outDir: File): List<File> {
        Logger.logDebug { "Extracting ${chaptersToKeep.size} chapters from ${zipFile.name} to ${outDir.absolutePath}" }
        val chapterSet = chaptersToKeep.toSet()
        val isEpub = zipFile.extension.equals("epub", ignoreCase = true)

        if (!isEpub) {
            Logger.logError("Chapter extraction is only supported for EPUB files.")
            return emptyList()
        }

        try {
            ZipFile(zipFile).use { zip ->
                zip.fileHeaders.forEach { header ->
                    if (header.isDirectory) return@forEach

                    if (header.fileName.startsWith("OEBPS/images/")) {
                        val fileName = File(header.fileName).name
                        val slugRegex = Regex("""^img_(.+)_\d+\..*""")
                        val chapterSlug = slugRegex.find(fileName)?.groupValues?.get(1)

                        if (chapterSlug != null && chapterSlug in chapterSet) {
                            val chapterDir = File(outDir, chapterSlug)
                            if (!chapterDir.exists()) chapterDir.mkdirs()
                            zip.extractFile(header, chapterDir.absolutePath, fileName)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.logError("Failed during chapter extraction: ${e.message}", e)
            return emptyList()
        }
        return outDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
    }
}
