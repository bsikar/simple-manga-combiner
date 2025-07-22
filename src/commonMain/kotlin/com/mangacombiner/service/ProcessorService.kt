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
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        const val FAILURES_FILE = "failures.json"
        val SUPPORTED_COVER_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    }

    private val jsonSerializer = Json { prettyPrint = true }

    private suspend fun processImage(
        inputFile: File,
        outputFile: File,
        maxWidth: Int?,
        jpegQuality: Int?
    ): File {
        if (!inputFile.exists() || !inputFile.canRead()) {
            Logger.logError("Input image file does not exist or is not readable: ${inputFile.absolutePath}")
            return inputFile
        }

        val dims = getImageDimensions(inputFile.path)
        if (dims == null) {
            Logger.logWarn("Could not determine dimensions for image: ${inputFile.path}, using original file")
            return inputFile
        }

        val needsResize = maxWidth != null && dims.width > maxWidth
        val isJpeg = inputFile.extension.equals("jpg", true) || inputFile.extension.equals("jpeg", true)
        val needsRecompress = isJpeg && jpegQuality != null

        if (!needsResize && !needsRecompress) {
            return inputFile
        }

        try {
            withContext(Dispatchers.IO) {
                val image: BufferedImage? = ImageIO.read(inputFile)
                if (image == null) {
                    Logger.logError("Failed to read image data from: ${inputFile.path}")
                    return@withContext
                }

                var imageToProcess = image

                if (needsResize) {
                    val newHeight = (image.height.toDouble() / image.width * maxWidth!!).roundToInt()
                    val imageType = if (isJpeg) BufferedImage.TYPE_INT_RGB else image.type
                    val resized = BufferedImage(maxWidth, newHeight, imageType)
                    val g = resized.createGraphics()
                    try {
                        g.drawImage(image, 0, 0, maxWidth, newHeight, null)
                        imageToProcess = resized
                        Logger.logDebug { "Resized image ${inputFile.name} from ${dims.width}x${dims.height} to ${maxWidth}x${newHeight}" }
                    } finally {
                        g.dispose()
                    }
                }

                // Ensure output directory exists
                outputFile.parentFile?.let { parentDir ->
                    if (!parentDir.exists()) {
                        parentDir.mkdirs()
                    }
                }

                if (needsRecompress && isJpeg) {
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
                    Logger.logDebug { "Recompressed JPEG ${inputFile.name} with quality $jpegQuality%" }
                } else {
                    if (!ImageIO.write(imageToProcess, inputFile.extension, outputFile)) {
                        Logger.logError("Failed to write processed image: ${outputFile.path}")
                        return@withContext
                    }
                }
            }
            return outputFile
        } catch (e: Exception) {
            Logger.logError("Error processing image ${inputFile.path}: ${e.message}", e)
            return inputFile
        }
    }

    private fun String.isImageFile(): Boolean =
        substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS

    private fun sortChapterFolders(folders: List<File>): List<File> {
        return folders.sortedWith(compareBy(naturalSortComparator) { it.name })
    }

    private fun getImageExtensionFromUrl(url: String): String {
        val urlWithoutQuery = url.substringBefore('?').substringBefore('#')
        val extension = urlWithoutQuery.substringAfterLast('.', "").lowercase()
        return when {
            extension in SUPPORTED_COVER_EXTENSIONS -> extension
            extension.isEmpty() -> "jpg"
            else -> "jpg" // Default fallback
        }
    }

    private fun validateCoverImage(coverFile: File): Boolean {
        if (!coverFile.exists()) {
            Logger.logDebug { "Cover image file does not exist: ${coverFile.absolutePath}" }
            return false
        }

        if (!coverFile.canRead()) {
            Logger.logDebug { "Cover image file is not readable: ${coverFile.absolutePath}" }
            return false
        }

        if (coverFile.length() == 0L) {
            Logger.logDebug { "Cover image file is empty: ${coverFile.absolutePath}" }
            return false
        }

        // Try to read image dimensions as a basic validation
        val dimensions = getImageDimensions(coverFile.absolutePath)
        if (dimensions == null) {
            Logger.logDebug { "Cover image file appears to be corrupted or invalid: ${coverFile.absolutePath}" }
            return false
        }

        Logger.logDebug { "Cover image validated: ${coverFile.name} (${dimensions.width}x${dimensions.height})" }
        return true
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
        if (outputFile.exists()) {
            outputFile.delete()
        }
        outputFile.parentFile?.mkdirs()

        Logger.logInfo("Creating EPUB archive: ${outputFile.name}...")
        Logger.logDebug { "Processing ${chapterFolders.size} chapters with series metadata: ${seriesMetadata != null}" }

        val epubGenerator = EpubStructureGenerator()
        val metadata = EpubStructureGenerator.EpubMetadata()
        val sortedFolders = sortChapterFolders(chapterFolders)
        val tempImageDir = File(outputFile.parent, "epub-temp-${System.currentTimeMillis()}").apply { mkdirs() }
        var coverImageFile: File? = null

        var totalProcessedImages = 0

        try {
            // Download cover image if available
            seriesMetadata?.coverImageUrl?.let { coverUrl ->
                Logger.logInfo("Downloading cover image from: $coverUrl")

                val client = createHttpClient(null)
                try {
                    val extension = getImageExtensionFromUrl(coverUrl)
                    val tempCoverFile = File(tempImageDir, "cover.$extension")

                    val response = client.get(coverUrl)
                    if (response.status.isSuccess()) {
                        response.bodyAsChannel().copyAndClose(tempCoverFile.writeChannel())

                        if (validateCoverImage(tempCoverFile)) {
                            coverImageFile = tempCoverFile
                            Logger.logInfo("Successfully downloaded cover image: ${tempCoverFile.name}")
                        } else {
                            Logger.logWarn("Downloaded cover image failed validation, continuing without cover")
                            tempCoverFile.delete()
                        }
                    } else {
                        Logger.logWarn("Failed to download cover image: HTTP ${response.status.value}, continuing without cover")
                    }
                } catch (e: Exception) {
                    Logger.logWarn("Error downloading cover image: ${e.message}, continuing without cover", e)
                } finally {
                    try {
                        client.close()
                    } catch (e: Exception) {
                        Logger.logDebug { "Error closing HTTP client: ${e.message}" }
                    }
                }
            }

            ZipFile(outputFile).use { epubZip ->
                // Add core EPUB files
                epubGenerator.addEpubCoreFiles(epubZip)

                // Add cover image if available and valid
                coverImageFile?.let { cover ->
                    epubGenerator.addCoverToEpub(cover, metadata, epubZip)
                }

                // Process chapters
                for (folder in sortedFolders) {
                    coroutineContext.ensureActive()

                    Logger.logDebug { "Processing chapter folder: ${folder.name}" }
                    val images = folder.listFiles()
                        ?.filter { it.isFile && it.name.isImageFile() && it.canRead() }
                        ?.sorted()

                    if (images.isNullOrEmpty()) {
                        Logger.logWarn("No valid images found in chapter folder: ${folder.name}")
                        continue
                    }

                    val processedImages = mutableListOf<File>()
                    images.forEachIndexed { index, img ->
                        try {
                            val tempFile = File(tempImageDir, "${folder.name}_${index}.${img.extension}")
                            val processedImg = processImage(img, tempFile, maxWidth, jpegQuality)
                            processedImages.add(processedImg)
                        } catch (e: Exception) {
                            Logger.logError("Failed to process image ${img.name} in chapter ${folder.name}: ${e.message}", e)
                        }
                    }

                    if (processedImages.isNotEmpty()) {
                        // Always store images without re-compressing in ZIP to maintain quality
                        val imageZipParams = ZipParameters().apply {
                            compressionMethod = CompressionMethod.STORE
                        }

                        try {
                            epubGenerator.addChapterToEpub(processedImages, folder.name, metadata, epubZip, imageZipParams)
                            totalProcessedImages += processedImages.size
                        } catch (e: Exception) {
                            Logger.logError("Failed to add chapter ${folder.name} to EPUB: ${e.message}", e)
                        }
                    }
                }

                // Add failure information if present
                if (!failedChapters.isNullOrEmpty()) {
                    try {
                        val failuresJson = jsonSerializer.encodeToString(failedChapters)
                        val failuresParams = ZipParameters().apply { fileNameInZip = "OEBPS/$FAILURES_FILE" }
                        epubZip.addStream(failuresJson.byteInputStream(), failuresParams)
                        metadata.manifestItems.add("""<item id="failures" href="$FAILURES_FILE" media-type="application/json"/>""")
                        Logger.logDebug { "Added failure information to EPUB" }
                    } catch (e: Exception) {
                        Logger.logError("Failed to add failure information to EPUB: ${e.message}", e)
                    }
                }

                // Add metadata files
                try {
                    epubGenerator.addEpubMetadataFiles(epubZip, mangaTitle, seriesUrl, seriesMetadata, metadata)
                } catch (e: Exception) {
                    Logger.logError("Failed to add EPUB metadata files: ${e.message}", e)
                    throw e
                }
            }

            Logger.logInfo("Successfully created EPUB: ${outputFile.name}")
            Logger.logInfo("  - Total chapters: ${sortedFolders.size}")
            Logger.logInfo("  - Total images: $totalProcessedImages")
            Logger.logInfo("  - File size: ${formatSize(outputFile.length())}")
            coverImageFile?.let { Logger.logInfo("  - Cover image: included") }

        } catch (e: Exception) {
            Logger.logError("Failed to create EPUB file ${outputFile.name}: ${e.message}", e)
            // Clean up partial file on failure
            if (outputFile.exists()) {
                try {
                    outputFile.delete()
                    Logger.logDebug { "Cleaned up partial EPUB file" }
                } catch (deleteException: Exception) {
                    Logger.logDebug { "Failed to clean up partial EPUB file: ${deleteException.message}" }
                }
            }
            throw e
        } finally {
            // Always clean up temp directory
            try {
                if (tempImageDir.exists()) {
                    tempImageDir.deleteRecursively()
                    Logger.logDebug { "Cleaned up temporary image directory" }
                }
            } catch (e: Exception) {
                Logger.logDebug { "Failed to clean up temp directory: ${e.message}" }
            }
        }
    }

    fun getChaptersAndInfoFromFile(file: File): Triple<List<String>, String?, Map<String, List<String>>> {
        Logger.logDebug { "Analyzing file for chapters and metadata: ${file.path}" }

        if (!file.exists() || !file.isFile) {
            Logger.logError("Cannot analyze file, it does not exist or is not a file: ${file.path}")
            return Triple(emptyList(), null, emptyMap())
        }

        if (!file.canRead()) {
            Logger.logError("Cannot analyze file, no read permission: ${file.path}")
            return Triple(emptyList(), null, emptyMap())
        }

        val (slugs, url) = when (file.extension.lowercase()) {
            "epub" -> {
                try {
                    ZipUtils.inferChapterSlugsFromEpub(file) to ZipUtils.getSourceUrlFromEpub(file)
                } catch (e: Exception) {
                    Logger.logError("Error analyzing EPUB file: ${e.message}", e)
                    emptySet<String>() to null
                }
            }
            else -> {
                Logger.logError("Unsupported file type for chapter analysis: ${file.extension}")
                emptySet<String>() to null
            }
        }

        val failedItems = try {
            ZipUtils.getFailedItems(file, jsonSerializer)
        } catch (e: Exception) {
            Logger.logError("Error reading failed items from file: ${e.message}", e)
            emptyMap<String, List<String>>()
        }

        return Triple(slugs.sortedWith(naturalSortComparator), url, failedItems)
    }

    fun extractChaptersToDirectory(zipFile: File, chaptersToKeep: List<String>, outDir: File): List<File> {
        Logger.logDebug { "Extracting ${chaptersToKeep.size} chapters from ${zipFile.name} to ${outDir.absolutePath}" }

        if (!zipFile.exists() || !zipFile.canRead()) {
            Logger.logError("Source file does not exist or is not readable: ${zipFile.path}")
            return emptyList()
        }

        if (chaptersToKeep.isEmpty()) {
            Logger.logWarn("No chapters specified for extraction")
            return emptyList()
        }

        val chapterSet = chaptersToKeep.toSet()
        val isEpub = zipFile.extension.equals("epub", ignoreCase = true)

        if (!isEpub) {
            Logger.logError("Chapter extraction is only supported for EPUB files, got: ${zipFile.extension}")
            return emptyList()
        }

        // Ensure output directory exists
        if (!outDir.exists()) {
            outDir.mkdirs()
        }

        try {
            ZipFile(zipFile).use { zip ->
                var extractedFiles = 0

                zip.fileHeaders.forEach { header ->
                    if (header.isDirectory) return@forEach

                    if (header.fileName.startsWith("OEBPS/images/")) {
                        val fileName = File(header.fileName).name
                        val slugRegex = Regex("""^img_(.+)_\d+\..*""")
                        val chapterSlug = slugRegex.find(fileName)?.groupValues?.get(1)

                        if (chapterSlug != null && chapterSlug in chapterSet) {
                            val chapterDir = File(outDir, chapterSlug)
                            if (!chapterDir.exists()) {
                                chapterDir.mkdirs()
                            }

                            try {
                                zip.extractFile(header, chapterDir.absolutePath, fileName)
                                extractedFiles++
                            } catch (e: Exception) {
                                Logger.logError("Failed to extract file ${header.fileName}: ${e.message}", e)
                            }
                        }
                    }
                }

                Logger.logInfo("Successfully extracted $extractedFiles files from ${zipFile.name}")
            }
        } catch (e: Exception) {
            Logger.logError("Failed during chapter extraction from ${zipFile.name}: ${e.message}", e)
            return emptyList()
        }

        return outDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
    }
}
