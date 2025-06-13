import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader as RarFileHeader // Alias to avoid name clash
import kotlinx.cli.*
import java.io.File
import java.io.InputStream
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader as ZipFileHeader // Alias
import net.lingala.zip4j.model.ZipParameters
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

enum class MangaType { VOLUME, CHAPTER }

/**
 * Represents a single manga file (volume or chapter).
 */
data class MangaChapter(val file: File, val title: String, val type: MangaType, val volume: Int, val chapter: Double) : Comparable<MangaChapter> {
    override fun compareTo(other: MangaChapter): Int {
        if (this.title != other.title) return this.title.compareTo(other.title)

        // A simple sort for initial listing; the deep scan will do the definitive sorting.
        val thisSortKey = if (type == MangaType.VOLUME) volume * 1000.0 else chapter
        val otherSortKey = if (other.type == MangaType.VOLUME) other.volume * 1000.0 else other.chapter

        return thisSortKey.compareTo(otherSortKey)
    }
}

/**
 * A sealed interface to represent a file entry from any archive type (ZIP, RAR, EPUB).
 */
sealed interface ArchiveEntry {
    val entryName: String
}
data class ZipArchiveEntry(val header: ZipFileHeader) : ArchiveEntry {
    override val entryName: String get() = header.fileName
}
data class RarArchiveEntry(val header: RarFileHeader) : ArchiveEntry {
    override val entryName: String get() = header.fileName
}

/**
 * Represents a single page, enhanced to track its origin for robust sorting.
 */
data class MangaPage(
    val sourceArchiveFile: File,
    val entry: ArchiveEntry,
    val volume: Int,    // From the source file (e.g., v12)
    val chapter: Int,   // Discovered or inferred chapter
    val page: Int       // Discovered or inferred page
) : Comparable<MangaPage> {
    override fun compareTo(other: MangaPage): Int {
        // The definitive sorting logic for all pages across all files.
        if (this.volume != 0 && other.volume != 0 && this.volume != other.volume) {
            return this.volume.compareTo(other.volume)
        }
        if (this.chapter != other.chapter) return this.chapter.compareTo(other.chapter)
        return this.page.compareTo(other.page)
    }
}


/**
 * Main entry point for the CLI application.
 */
@OptIn(DelicateCoroutinesApi::class)
fun main(args: Array<String>) {
    val parser = ArgParser("CbzMangaTool")

    if (args.isEmpty() || (args.size == 2 && (args[0] == "-d" || args[0] == "--directory"))) {
        parser.parse(arrayOf("--help"))
        exitProcess(0)
    }

    val directory by parser.option(ArgType.String, shortName = "d", fullName = "directory", description = "Directory to scan for manga files").required()
    val list by parser.option(ArgType.Boolean, shortName = "l", fullName = "list", description = "List all manga series found").default(false)
    val checkFiles by parser.option(ArgType.Boolean, fullName = "check-files", description = "Check if combined files are up-to-date with sources.").default(false)
    val combine by parser.option(ArgType.String, shortName = "c", fullName = "combine", description = "Name of the manga series to combine")
    val combineAll by parser.option(ArgType.Boolean, fullName = "combine-all", description = "Combine all found manga series into their own files").default(false)
    val outputDir by parser.option(ArgType.String, shortName = "o", fullName = "output", description = "Output directory for combined file")
    val dryRun by parser.option(ArgType.Boolean, fullName = "dry-run", description = "Simulate combining without creating a file").default(false)
    val overwrite by parser.option(ArgType.Boolean, fullName = "overwrite", description = "Forcefully overwrite existing combined files.").default(false)
    val fix by parser.option(ArgType.Boolean, fullName = "fix", description = "Update combined files only if source files are newer.").default(false)
    val deepScan by parser.option(ArgType.Boolean, fullName = "deep-scan", description = "Scan inside archives to sort by internal chapter/page numbers.").default(false)
    val outputFormat by parser.option(ArgType.String, fullName = "output-format", description = "Output file format (only cbz is supported for writing)").default("cbz")


    try {
        parser.parse(args)
    } catch (e: Exception) {
        println(e.message)
        return
    }

    if (outputFormat.lowercase() != "cbz") {
        println("Error: Only 'cbz' is supported as an output format for writing files.")
        return
    }

    val dir = File(directory)
    if (!dir.exists() || !dir.isDirectory) {
        println("Error: The provided path is not a valid directory.")
        return
    }

    val mangaCollection = scanForManga(dir)
    if (mangaCollection.isEmpty()) {
        println("No CBZ, CBR, or EPUB manga files found in the specified directory.")
        return
    }

    if (list) {
        println("Found the following manga series:")
        mangaCollection.keys.sorted().forEach { title ->
            println("- $title (${mangaCollection[title]?.size} files)")
        }
    }

    if (checkFiles) {
        println("--- Checking status of combined files ---")
        mangaCollection.entries.sortedBy { it.key }.forEach { (title, chapters) ->
            val outputPath = getOutputPath(title, outputDir)
            print("[$title]: ")
            if(Files.exists(outputPath)) {
                val combinedFileModTime = Files.getLastModifiedTime(outputPath).toMillis()
                val needsUpdate = chapters.any { it.file.lastModified() > combinedFileModTime }
                if(needsUpdate) {
                    println("Needs update (source files are newer).")
                } else {
                    println("Up-to-date.")
                }
            } else {
                println("Not combined yet.")
            }
        }
    }

    if (combine != null) {
        val mangaToCombine = combine!!
        if (mangaToCombine.startsWith("-")) {
            println("Error: The --combine flag requires a manga title. You provided '$mangaToCombine'.")
            println("Usage: --combine \"Manga Title\"")
            return
        }
        val chapters = mangaCollection[mangaToCombine]
        if (chapters == null) {
            println("Error: Manga series '$mangaToCombine' not found.")
            val closestMatches = mangaCollection.keys.filter { it.contains(mangaToCombine, ignoreCase = true) }
            if (closestMatches.isNotEmpty()) {
                println("\nDid you mean one of these?")
                closestMatches.forEach { println("- $it") }
            }
            return
        }

        handleCombination(mangaToCombine, chapters, outputDir, overwrite, fix, dryRun, deepScan)
    }

    if (combineAll) {
        println("--- Combining all found manga series ---")

        if (dryRun) {
            println("--- Performing DRY RUN sequentially for clean output ---")
            mangaCollection.entries.sortedBy { it.key }.forEach { (title, chapters) ->
                handleCombination(title, chapters, outputDir, overwrite, fix, true, deepScan)
            }
        } else {
            runBlocking {
                val jobs = mangaCollection.entries.sortedBy { it.key }.map { (title, chapters) ->
                    async(Dispatchers.IO) {
                        handleCombination(title, chapters, outputDir, overwrite, fix, false, deepScan)
                    }
                }
                jobs.awaitAll()
            }
        }

        println("\n-------------------------------------------")
        println("All series processed.")
    }
}

fun getOutputPath(title: String, outputDir: String?): Path {
    val safeTitle = title.replace(Regex("[^a-zA-Z0-9_\\-\\(\\)]"), "_").replace(" ", "_")
    val outputFileName = "${safeTitle}_Combined.cbz"
    return outputDir?.let { Paths.get(it, outputFileName) } ?: Paths.get(outputFileName)
}

fun handleCombination(title: String, chapters: List<MangaChapter>, outputDir: String?, overwrite: Boolean, fix: Boolean, isDryRun: Boolean, isDeepScan: Boolean) {
    println("\n-------------------------------------------")
    println("[$title] Preparing to combine...")

    // Sort chapters for display and non-deep scan mode. Deep scan will do its own robust sorting.
    val sortedChapters = chapters.sorted()
    checkForMissingChapters(title, sortedChapters)

    val outputPath = getOutputPath(title, outputDir)

    if (isDryRun) {
        println("\n--- DRY RUN for '$title' ---")
        if (Files.exists(outputPath)) {
            if (overwrite) {
                println("[$title] !!! Existing file would be OVERWRITTEN (--overwrite) !!!")
            } else if (fix) {
                val combinedFileModTime = Files.getLastModifiedTime(outputPath).toMillis()
                val needsUpdate = chapters.any { it.file.lastModified() > combinedFileModTime }
                if(needsUpdate) {
                    println("[$title] !!! Existing file would be UPDATED (--fix) because source files are newer !!!")
                } else {
                    println("[$title] Existing file is up-to-date. No action needed.")
                }
            }
        }
        println("[$title] Output file would be saved to: ${outputPath.toAbsolutePath()}")
        return
    }

    if (isDeepScan) {
        // Pass the whole list, deep scan will sort pages correctly from all files.
        combineChaptersDeep(title, chapters, outputDir, overwrite, fix)
    } else {
        // Simple combine uses the pre-sorted file list.
        combineChaptersSimple(title, sortedChapters, outputDir, overwrite, fix)
    }
}


fun scanForManga(directory: File): Map<String, List<MangaChapter>> {
    val supportedExtensions = listOf("cbz", "cbr", "epub")
    val parsers = listOf(
        // Parser for filenames like "Title v01 c01"
        fun(file: File): MangaChapter? {
            val regex = """^(.*?)[,\s_-]+v(\d+)\s*c(\d+(\.\d)?).*?\.(?:${supportedExtensions.joinToString("|")})$""".toRegex(RegexOption.IGNORE_CASE)
            return regex.find(file.name)?.let {
                val title = it.groupValues[1].trim().replace("_", " ")
                MangaChapter(file, title, MangaType.CHAPTER, it.groupValues[2].toInt(), it.groupValues[3].toDouble())
            }
        },
        // Parser for volume files like "Title v01"
        fun(file: File): MangaChapter? {
            val regex = """^(.*?)[,\s_-]+v(\d+)\s*(?:\(.*\)|\[.*\])?\.(?:${supportedExtensions.joinToString("|")})$""".toRegex(RegexOption.IGNORE_CASE)
            if (file.name.contains(Regex("""c\d""", RegexOption.IGNORE_CASE))) return null
            return regex.find(file.name)?.let {
                val title = it.groupValues[1].trim().replace("_", " ")
                MangaChapter(file, title, MangaType.VOLUME, it.groupValues[2].toInt(), 0.0)
            }
        },
        // Parser for chapter files like "Title 128" or "Title c128"
        fun(file: File): MangaChapter? {
            val regex = """^(.*?)[,\s_-]+c?(\d{1,4}(\.\d)?)\s*(?:\(.*\)|\[.*\])?\.(?:${supportedExtensions.joinToString("|")})$""".toRegex(RegexOption.IGNORE_CASE)
            return regex.find(file.name)?.let {
                val title = it.groupValues[1].trim().replace("_", " ")
                if (title.equals("Volume", ignoreCase = true)) return@let null
                MangaChapter(file, title, MangaType.CHAPTER, 0, it.groupValues[2].toDouble())
            }
        },
        // Parser for files named "Volume 01" inside a series folder
        fun(file: File): MangaChapter? {
            val regex = """^Volume\s*(\d+).*?\.(?:${supportedExtensions.joinToString("|")})$""".toRegex(RegexOption.IGNORE_CASE)
            return regex.find(file.name)?.let {
                val title = file.parentFile.name
                    .replace(Regex("""\s*Volumes.*$""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\s*\(\d{4}.*"""), "")
                    .trim()
                MangaChapter(file, title, MangaType.VOLUME, it.groupValues[1].toInt(), 0.0)
            }
        }
    )

    val allChapters = directory.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in supportedExtensions && !it.name.startsWith("._") }
        .mapNotNull { file ->
            parsers.asSequence().mapNotNull { parser -> parser(file) }.firstOrNull()
        }.toList()

    return allChapters.groupBy { it.title }
}

fun checkForMissingChapters(title: String, chapters: List<MangaChapter>) {
    println("[$title] Checking for missing chapters...")
    var warnings = 0
    // Note: This check is basic and works best on `Chapter`-type files.
    if (chapters.size > 1) {
        for (i in 0 until chapters.size - 1) {
            val current = chapters[i]
            val next = chapters[i + 1]
            if (current.type == MangaType.CHAPTER && next.type == MangaType.CHAPTER) {
                val expectedNextChapter = current.chapter + 1
                if (next.chapter > expectedNextChapter) {
                    println("[$title] Warning: Possible missing chapter(s) between ${current.file.name} and ${next.file.name}. (Gap between Ch. ${current.chapter.toInt()} and ${next.chapter.toInt()})")
                    warnings++
                }
            }
        }
    }
    if (warnings == 0) println("[$title] No obvious gaps found based on filenames.")
}


fun prepareOutputFile(title: String, chapters: List<MangaChapter>, outputDir: String?, overwrite: Boolean, fix: Boolean): ZipFile? {
    val outputPath = getOutputPath(title, outputDir)

    if (Files.exists(outputPath)) {
        var proceedWithOverwrite = false
        var reason = ""

        if (overwrite) {
            proceedWithOverwrite = true
            reason = "--overwrite flag is set"
        } else if (fix) {
            val combinedFileModTime = Files.getLastModifiedTime(outputPath).toMillis()
            val sourceIsNewer = chapters.any { it.file.lastModified() > combinedFileModTime }
            if (sourceIsNewer) {
                proceedWithOverwrite = true
                reason = "source files are newer"
            } else {
                println("[$title] Combined file is already up-to-date. Skipping.")
                return null
            }
        }

        if (proceedWithOverwrite) {
            println("[$title] Overwriting existing file: ${outputPath.toAbsolutePath()} ($reason)")
            try {
                Files.delete(outputPath)
            } catch (e: Exception) {
                println("[$title] Error: Could not delete existing file. Please check permissions.")
                e.printStackTrace()
                return null
            }
        } else {
            println("[$title] Error: Output file already exists. Use --overwrite or --fix to replace it.")
            return null
        }
    }

    outputPath.parent?.let { Files.createDirectories(it) }

    return ZipFile(outputPath.toFile())
}

fun processArchive(file: File, block: (entryName: String, inputStream: InputStream) -> Unit) {
    when (file.extension.lowercase()) {
        "cbz", "epub" -> {
            try {
                val zipFile = ZipFile(file)
                if (!zipFile.isValidZipFile) return
                val sortedHeaders = zipFile.fileHeaders.filter { !it.isDirectory }.sortedBy { it.fileName }
                sortedHeaders.forEach { header ->
                    zipFile.getInputStream(header).use { stream ->
                        block(header.fileName, stream)
                    }
                }
            } catch (e: Exception) {
                println("   Warning: Could not read ZIP-based archive ${file.name}. It might be corrupt.")
            }
        }
        "cbr" -> {
            try {
                Archive(file).use { archive ->
                    val sortedHeaders = archive.fileHeaders.filter { !it.isDirectory }.sortedBy { it.fileName }
                    sortedHeaders.forEach { header ->
                        archive.getInputStream(header).use { stream ->
                            block(header.fileName, stream)
                        }
                    }
                }
            } catch (e: Exception) {
                println("   Warning: Could not read CBR file ${file.name}. It might be corrupt or an unsupported format.")
            }
        }
    }
}

fun combineChaptersSimple(title: String, chapters: List<MangaChapter>, outputDir: String?, overwrite: Boolean, fix: Boolean) {
    val combinedZip = prepareOutputFile(title, chapters, outputDir, overwrite, fix) ?: return
    println("[$title] Combining ${chapters.size} files into: ${combinedZip.file.absolutePath}")
    var pageCounter = 0

    for (chapter in chapters) {
        println("[$title]   -> Adding ${chapter.file.name}")
        processArchive(chapter.file) { entryName, inputStream ->
            val fileExtension = entryName.substringAfterLast('.', "")
            if (fileExtension.lowercase() in listOf("jpg", "jpeg", "png", "webp", "gif")) {
                pageCounter++
                val newFileName = "p%05d.%s".format(pageCounter, fileExtension)
                val zipParameters = ZipParameters().apply { fileNameInZip = newFileName }
                combinedZip.addStream(inputStream, zipParameters)
            }
        }
    }

    println("\n[$title] Successfully combined ${chapters.size} files with a total of $pageCounter pages.")
    println("[$title] Output file created at: ${combinedZip.file.absolutePath}")
}

fun combineChaptersDeep(title: String, mangaFiles: List<MangaChapter>, outputDir: String?, overwrite: Boolean, fix: Boolean) {
    val combinedZip = prepareOutputFile(title, mangaFiles, outputDir, overwrite, fix) ?: return
    println("[$title] Starting deep scan of ${mangaFiles.size} files...")

    val allPages = mutableListOf<MangaPage>()
    val imageExtensions = listOf("jpg", "jpeg", "png", "webp", "gif")
    val pageRegex = """.*?c(\d+)(?:[._-].*?p(\d+))?""".toRegex(RegexOption.IGNORE_CASE)
    val fallbackPageRegex = """.*?[_.-]?p?(\d+)\..*?""".toRegex(RegexOption.IGNORE_CASE)

    for (mangaFile in mangaFiles) {
        println("[$title]  -> Scanning inside ${mangaFile.file.name}")
        val pageCounterInFile = AtomicInteger(1)

        val entries = mutableListOf<ArchiveEntry>()
        when (mangaFile.file.extension.lowercase()) {
            "cbz", "epub" -> try {
                ZipFile(mangaFile.file).fileHeaders.filter { !it.isDirectory }.sortedBy { it.fileName }.forEach { entries.add(ZipArchiveEntry(it)) }
            } catch (e: Exception) { println("   Warning: Could not read archive ${mangaFile.file.name}.") }
            "cbr" -> try {
                Archive(mangaFile.file).fileHeaders.filter { !it.isDirectory }.sortedBy { it.fileName }.forEach { entries.add(RarArchiveEntry(it)) }
            } catch (e: Exception) { println("   Warning: Could not read CBR archive ${mangaFile.file.name}.") }
        }

        for (entry in entries) {
            val entryName = entry.entryName
            if (entryName.substringAfterLast('.').lowercase() !in imageExtensions) {
                continue
            }

            var chapterNum: Int? = null
            var pageNum: Int? = null

            pageRegex.find(entryName)?.let { matchResult ->
                chapterNum = matchResult.groupValues[1].takeIf { it.isNotEmpty() }?.toInt()
                pageNum = matchResult.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toInt()
            }

            if (chapterNum == null && mangaFile.type == MangaType.CHAPTER) {
                chapterNum = mangaFile.chapter.toInt()
            }

            if (pageNum == null) {
                pageNum = fallbackPageRegex.find(entryName)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.toInt()
            }

            val finalPageNum = pageNum ?: pageCounterInFile.getAndIncrement()
            val finalChapterNum = chapterNum ?: mangaFile.chapter.toInt()
            val finalVolumeNum = mangaFile.volume

            allPages.add(MangaPage(mangaFile.file, entry, finalVolumeNum, finalChapterNum, finalPageNum))
        }
    }

    if (allPages.isEmpty()) {
        println("[$title] Warning: Deep scan found no valid pages. Aborting.")
        combinedZip.file.delete()
        return
    }

    println("[$title] Found ${allPages.size} total pages. Sorting, combining, and optimizing I/O...")
    val sortedPages = allPages.sorted()
    val pagesBySource = sortedPages.groupBy { it.sourceArchiveFile }
    var finalPageCounter = 0

    for ((sourceFile, pages) in pagesBySource) {
        println("[$title]  -> Processing ${pages.size} pages from ${sourceFile.name}")
        when (sourceFile.extension.lowercase()) {
            "cbz", "epub" -> try {
                ZipFile(sourceFile).use { zip ->
                    for (page in pages) {
                        val entry = page.entry as? ZipArchiveEntry ?: continue
                        finalPageCounter++
                        val newFileName = "p%05d.%s".format(finalPageCounter, page.entry.entryName.substringAfterLast('.'))
                        val zipParameters = ZipParameters().apply { fileNameInZip = newFileName }
                        zip.getInputStream(entry.header).use { stream -> combinedZip.addStream(stream, zipParameters) }
                    }
                }
            } catch (e: Exception) {
                println("[$title]   Error: Could not process ZIP-based file ${sourceFile.name}. Skipping. (${e.message})")
            }
            "cbr" -> try {
                Archive(sourceFile).use { archive ->
                    for (page in pages) {
                        val entry = page.entry as? RarArchiveEntry ?: continue
                        finalPageCounter++
                        val newFileName = "p%05d.%s".format(finalPageCounter, page.entry.entryName.substringAfterLast('.'))
                        val zipParameters = ZipParameters().apply { fileNameInZip = newFileName }
                        archive.getInputStream(entry.header).use { stream -> combinedZip.addStream(stream, zipParameters) }
                    }
                }
            } catch (e: Exception) {
                println("[$title]   Error: Could not process CBR file ${sourceFile.name}. Skipping. (${e.message})")
            }
        }
    }

    println("\n[$title] Successfully combined ${sortedPages.size} pages from ${mangaFiles.size} source files.")
    println("[$title] Output file created at: ${combinedZip.file.absolutePath}")
}
