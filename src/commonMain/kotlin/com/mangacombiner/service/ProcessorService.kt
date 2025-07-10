package com.mangacombiner.service

import com.mangacombiner.model.ComicInfo
import com.mangacombiner.ui.viewmodel.OperationState
import com.mangacombiner.util.Logger
import com.mangacombiner.util.SlugUtils
import com.mangacombiner.util.ZipUtils
import com.mangacombiner.util.naturalSortComparator
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import nl.adaptivity.xmlutil.serialization.XML
import java.io.File
import kotlin.coroutines.coroutineContext

class ProcessorService(
    private val fileConverter: FileConverter
) {
    internal companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
        const val COMIC_INFO_FILE = "ComicInfo.xml"
        const val FAILURES_FILE = "failures.json"
    }

    private val xmlSerializer = XML { indentString = "  " }
    private val jsonSerializer = Json { prettyPrint = true }

    private fun String.isImageFile(): Boolean =
        substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS

    private val chapterComparator = Comparator<File> { f1, f2 ->
        val parts1 = SlugUtils.parseChapterSlugsForSorting(f1.name)
        val parts2 = SlugUtils.parseChapterSlugsForSorting(f2.name)
        if (parts1.isEmpty() || parts2.isEmpty()) return@Comparator f1.name.compareTo(f2.name)
        val maxIndex = minOf(parts1.size, parts2.size)
        for (i in 0 until maxIndex) {
            val compare = parts1[i].compareTo(parts2[i])
            if (compare != 0) return@Comparator compare
        }
        return@Comparator parts1.size.compareTo(parts2.size)
    }

    private fun sortChapterFolders(folders: List<File>): List<File> = folders.sortedWith(chapterComparator)

    private fun addImageToCbz(zipFile: ZipFile, folder: File, imageFile: File) {
        val zipParams = ZipParameters().apply { fileNameInZip = "${folder.name}/${imageFile.name}" }
        zipFile.addFile(imageFile, zipParams)
    }

    private suspend fun addChaptersToCbz(zipFile: ZipFile, sortedFolders: List<File>) {
        for (folder in sortedFolders) {
            coroutineContext.ensureActive()
            val imageFiles = folder.listFiles()?.filter { it.isFile && it.name.isImageFile() }?.sorted() ?: continue
            for (imageFile in imageFiles) {
                coroutineContext.ensureActive()
                addImageToCbz(zipFile, folder, imageFile)
            }
        }
    }

    private suspend fun populateCbzFile(
        zipFile: ZipFile,
        mangaTitle: String,
        sortedFolders: List<File>,
        seriesUrl: String?,
        failedChapters: Map<String, List<String>>? = null
    ) {
        val cbzGenerator = CbzStructureGenerator(xmlSerializer)
        val (bookmarks, totalPageCount) = cbzGenerator.createBookmarks(sortedFolders)

        if (totalPageCount == 0) {
            Logger.logInfo("Warning: No images found for $mangaTitle. Skipping CBZ creation.")
            return
        }

        coroutineContext.ensureActive()
        val comicInfoXml = cbzGenerator.generateComicInfoXml(mangaTitle, bookmarks, totalPageCount, seriesUrl)
        val params = ZipParameters().apply { fileNameInZip = COMIC_INFO_FILE }
        zipFile.addStream(comicInfoXml.byteInputStream(), params)

        if (!failedChapters.isNullOrEmpty()) {
            coroutineContext.ensureActive()
            val failuresJson = jsonSerializer.encodeToString(failedChapters)
            val failuresParams = ZipParameters().apply { fileNameInZip = FAILURES_FILE }
            zipFile.addStream(failuresJson.byteInputStream(), failuresParams)
            Logger.logInfo("Embedding failure metadata into the archive.")
        }

        addChaptersToCbz(zipFile, sortedFolders)
    }

    private suspend fun buildCbz(
        outputFile: File,
        mangaTitle: String,
        sortedFolders: List<File>,
        seriesUrl: String?,
        failedChapters: Map<String, List<String>>? = null
    ) {
        try {
            ZipFile(outputFile).use { zipFile ->
                populateCbzFile(zipFile, mangaTitle, sortedFolders, seriesUrl, failedChapters)
            }
            coroutineContext.ensureActive()
            Logger.logInfo("Successfully created: ${outputFile.name}")
        } catch (e: ZipException) {
            Logger.logError("Failed to create CBZ file ${outputFile.name}", e)
        }
    }

    suspend fun createCbzFromFolders(
        mangaTitle: String,
        chapterFolders: List<File>,
        outputFile: File,
        seriesUrl: String? = null,
        failedChapters: Map<String, List<String>>? = null
    ) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val sortedFolders = sortChapterFolders(chapterFolders)
        Logger.logInfo("Creating CBZ archive: ${outputFile.name}...")
        buildCbz(outputFile, mangaTitle, sortedFolders, seriesUrl, failedChapters)
    }

    private suspend fun addChaptersToEpub(
        epubZip: ZipFile,
        sortedFolders: List<File>,
        epubGenerator: EpubStructureGenerator,
        metadata: EpubStructureGenerator.EpubMetadata
    ) {
        for (folder in sortedFolders) {
            coroutineContext.ensureActive()
            val images = folder.listFiles()?.filter { it.isFile && it.name.isImageFile() }?.sorted()
            if (!images.isNullOrEmpty()) {
                epubGenerator.addChapterToEpub(images, folder.name, metadata, epubZip)
            }
        }
    }

    suspend fun createEpubFromFolders(
        mangaTitle: String,
        chapterFolders: List<File>,
        outputFile: File,
        seriesUrl: String? = null,
        failedChapters: Map<String, List<String>>? = null
    ) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()
        Logger.logInfo("Creating EPUB archive: ${outputFile.name}...")

        val epubGenerator = EpubStructureGenerator()
        val metadata = EpubStructureGenerator.EpubMetadata()
        val sortedFolders = sortChapterFolders(chapterFolders)

        try {
            ZipFile(outputFile).use { epubZip ->
                epubGenerator.addEpubCoreFiles(epubZip)
                addChaptersToEpub(epubZip, sortedFolders, epubGenerator, metadata)

                coroutineContext.ensureActive()

                if (!failedChapters.isNullOrEmpty()) {
                    val failuresJson = jsonSerializer.encodeToString(failedChapters)
                    val failuresParams = ZipParameters().apply { fileNameInZip = "OEBPS/$FAILURES_FILE" }
                    epubZip.addStream(failuresJson.byteInputStream(), failuresParams)
                    metadata.manifestItems.add("""<item id="failures" href="$FAILURES_FILE" media-type="application/json"/>""")
                    Logger.logInfo("Embedding failure metadata into the archive.")
                }
                epubGenerator.addEpubMetadataFiles(epubZip, mangaTitle, seriesUrl, metadata)
            }
            coroutineContext.ensureActive()
            Logger.logInfo("Successfully created: ${outputFile.name}")
        } catch (e: ZipException) {
            Logger.logError("Failed to create EPUB file ${outputFile.name}", e)
        }
    }

    fun getChaptersAndInfoFromFile(file: File): Triple<List<String>, String?, Map<String, List<String>>> {
        if (!file.exists() || !file.isFile) return Triple(emptyList(), null, emptyMap())

        val (slugs, url) = when (file.extension.lowercase()) {
            "cbz" -> ZipUtils.inferChapterSlugsFromZip(file) to ZipUtils.getSourceUrlFromCbz(file, xmlSerializer)
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
        val chapterSet = chaptersToKeep.toSet()
        val isEpub = zipFile.extension.equals("epub", ignoreCase = true)

        try {
            ZipFile(zipFile).use { zip ->
                zip.fileHeaders.forEach { header ->
                    if (header.isDirectory) return@forEach

                    if (isEpub) {
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
                    } else {
                        val chapterName = File(header.fileName).parent ?: return@forEach
                        if (chapterName in chapterSet) {
                            zip.extractFile(header, outDir.absolutePath)
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
