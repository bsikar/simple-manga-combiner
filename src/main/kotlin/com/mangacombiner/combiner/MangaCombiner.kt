package com.mangacombiner.combiner

import com.github.junrar.Archive
import com.mangacombiner.model.*
import com.mangacombiner.util.checkForMissingChapters
import com.mangacombiner.util.getOutputPath
import com.mangacombiner.util.logDebug
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

fun handleCombination(title: String, chapters: List<MangaChapter>, outputDir: String?, overwrite: Boolean, fix: Boolean, isDryRun: Boolean, isDeepScan: Boolean) {
    println("\n-------------------------------------------")
    println("[$title] Preparing to combine...")
    logDebug { "handleCombination: For '$title' with ${chapters.size} chapters. Deep Scan: $isDeepScan, Dry Run: $isDryRun" }

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
                if (needsUpdate) {
                    println("[$title] !!! Existing file would be UPDATED (--fix) because source files are newer !!!")
                } else {
                    println("[$title] Existing file is up-to-date. No action needed.")
                }
            }
        }
        println("[$title] Output file would be saved to: ${outputPath.toAbsolutePath()}")
        return
    }

    logDebug { "handleCombination: Proceeding with combination. Deep Scan is ${if (isDeepScan) "ENABLED" else "DISABLED"}." }
    if (isDeepScan) {
        combineChaptersDeep(title, chapters, outputDir, overwrite, fix)
    } else {
        combineChaptersSimple(title, sortedChapters, outputDir, overwrite, fix)
    }
}


fun prepareOutputFile(title: String, chapters: List<MangaChapter>, outputDir: String?, overwrite: Boolean, fix: Boolean): ZipFile? {
    logDebug { "prepareOutputFile: Preparing output for '$title'. Overwrite: $overwrite, Fix: $fix" }
    val outputPath = getOutputPath(title, outputDir)

    if (Files.exists(outputPath)) {
        logDebug { "prepareOutputFile: Output file already exists at '$outputPath'." }
        var proceedWithOverwrite = false
        var reason = ""

        if (overwrite) {
            logDebug { "prepareOutputFile: --overwrite is true. Will delete existing file." }
            proceedWithOverwrite = true
            reason = "--overwrite flag is set"
        } else if (fix) {
            logDebug { "prepareOutputFile: --fix is true. Checking file modification times." }
            val combinedFileModTime = Files.getLastModifiedTime(outputPath).toMillis()
            val sourceIsNewer = chapters.any { it.file.lastModified() > combinedFileModTime }
            if (sourceIsNewer) {
                logDebug { "prepareOutputFile: Source files are newer. Will delete existing file." }
                proceedWithOverwrite = true
                reason = "source files are newer"
            } else {
                println("[$title] Combined file is already up-to-date. Skipping.")
                logDebug { "prepareOutputFile: File is up-to-date. No action needed." }
                return null
            }
        }

        if (proceedWithOverwrite) {
            println("[$title] Overwriting existing file: ${outputPath.toAbsolutePath()} ($reason)")
            try {
                logDebug { "prepareOutputFile: Deleting file: '$outputPath'." }
                Files.delete(outputPath)
            } catch (e: Exception) {
                println("[$title] Error: Could not delete existing file. Please check permissions.")
                e.printStackTrace()
                return null
            }
        } else {
            println("[$title] Error: Output file already exists. Use --overwrite or --fix to replace it.")
            logDebug { "prepareOutputFile: Aborting because file exists and no overwrite/fix condition met." }
            return null
        }
    }

    logDebug { "prepareOutputFile: Creating parent directories for '$outputPath'." }
    outputPath.parent?.let { Files.createDirectories(it) }

    logDebug { "prepareOutputFile: Creating new ZipFile object for '${outputPath.toFile().absolutePath}'." }
    return ZipFile(outputPath.toFile())
}

fun processArchive(file: File, block: (entryName: String, inputStream: InputStream) -> Unit) {
    logDebug { "processArchive: Processing archive '${file.name}'" }
    when (file.extension.lowercase()) {
        "cbz", "epub" -> {
            try {
                logDebug { "processArchive: Treating as ZIP-based archive." }
                val zipFile = ZipFile(file)
                if (!zipFile.isValidZipFile) {
                    logDebug { "processArchive: Invalid ZIP file, skipping." }
                    return
                }
                val sortedHeaders = zipFile.fileHeaders.filter { !it.isDirectory }.sortedBy { it.fileName }
                logDebug { "processArchive: Found ${sortedHeaders.size} file entries." }
                sortedHeaders.forEach { header ->
                    logDebug { "processArchive:  -> Processing entry: ${header.fileName}" }
                    zipFile.getInputStream(header).use { stream -> block(header.fileName, stream) }
                }
            } catch (e: Exception) {
                println("    Warning: Could not read ZIP-based archive ${file.name}. It might be corrupt.")
                logDebug { "processArchive: Exception while reading ZIP: ${e.message}" }
            }
        }
        "cbr" -> {
            try {
                logDebug { "processArchive: Treating as RAR archive." }
                Archive(file).use { archive ->
                    val sortedHeaders = archive.fileHeaders.filter { !it.isDirectory }.sortedBy { it.fileName }
                    logDebug { "processArchive: Found ${sortedHeaders.size} file entries." }
                    sortedHeaders.forEach { header ->
                        logDebug { "processArchive:  -> Processing entry: ${header.fileName}" }
                        archive.getInputStream(header).use { stream -> block(header.fileName, stream) }
                    }
                }
            } catch (e: Exception) {
                println("    Warning: Could not read CBR file ${file.name}. It might be corrupt or an unsupported format.")
                logDebug { "processArchive: Exception while reading RAR: ${e.message}" }
            }
        }
    }
}

fun combineChaptersSimple(title: String, chapters: List<MangaChapter>, outputDir: String?, overwrite: Boolean, fix: Boolean) {
    logDebug { "combineChaptersSimple: Starting simple combination for '$title'." }
    val combinedZip = prepareOutputFile(title, chapters, outputDir, overwrite, fix) ?: return
    println("[$title] Combining ${chapters.size} files into: ${combinedZip.file.absolutePath}")
    var pageCounter = 0

    for (chapter in chapters) {
        println("[$title]   -> Adding ${chapter.file.name}")
        logDebug { "combineChaptersSimple: Adding file '${chapter.file.name}'." }
        processArchive(chapter.file) { entryName, inputStream ->
            val fileExtension = entryName.substringAfterLast('.', "")
            if (fileExtension.lowercase() in listOf("jpg", "jpeg", "png", "webp", "gif")) {
                pageCounter++
                val newFileName = "p%05d.%s".format(pageCounter, fileExtension)
                logDebug { "combineChaptersSimple: Adding page from '$entryName' as '$newFileName'." }
                val zipParameters = ZipParameters().apply { fileNameInZip = newFileName }
                combinedZip.addStream(inputStream, zipParameters)
            } else {
                logDebug { "combineChaptersSimple: Skipping non-image file '$entryName'." }
            }
        }
    }

    println("\n[$title] Successfully combined ${chapters.size} files with a total of $pageCounter pages.")
    println("[$title] Output file created at: ${combinedZip.file.absolutePath}")
}

fun combineChaptersDeep(title: String, mangaFiles: List<MangaChapter>, outputDir: String?, overwrite: Boolean, fix: Boolean) {
    logDebug { "combineChaptersDeep: Starting deep scan combination for '$title'." }
    val combinedZip = prepareOutputFile(title, mangaFiles, outputDir, overwrite, fix) ?: return
    println("[$title] Starting deep scan of ${mangaFiles.size} files...")

    val allPages = mutableListOf<MangaPage>()
    val imageExtensions = listOf("jpg", "jpeg", "png", "webp", "gif")
    val pageRegex = """.*?c(\d+)(?:[._-].*?p(\d+))?""".toRegex(RegexOption.IGNORE_CASE)
    val fallbackPageRegex = """.*?[_.-]?p?(\d+)\..*?""".toRegex(RegexOption.IGNORE_CASE)

    for (mangaFile in mangaFiles) {
        println("[$title]  -> Scanning inside ${mangaFile.file.name}")
        logDebug { "combineChaptersDeep: Scanning inside archive '${mangaFile.file.name}'." }
        val pageCounterInFile = AtomicInteger(1)
        val entries = mutableListOf<ArchiveEntry>()
        when (mangaFile.file.extension.lowercase()) {
            "cbz", "epub" -> try { entries.addAll(ZipFile(mangaFile.file).fileHeaders.filter { !it.isDirectory }.sortedBy { it.fileName }.map { ZipArchiveEntry(it) }) } catch (e: Exception) { logDebug { "combineChaptersDeep: Warning: Could not read archive ${mangaFile.file.name}." } }
            "cbr" -> try { entries.addAll(Archive(mangaFile.file).fileHeaders.filter { !it.isDirectory }.sortedBy { it.fileName }.map { RarArchiveEntry(it) }) } catch (e: Exception) { logDebug { "combineChaptersDeep: Warning: Could not read CBR archive ${mangaFile.file.name}." } }
        }
        logDebug { "combineChaptersDeep: Found ${entries.size} internal entries in '${mangaFile.file.name}'." }

        for (entry in entries) {
            val entryName = entry.entryName
            if (entryName.substringAfterLast('.').lowercase() !in imageExtensions) {
                logDebug { "combineChaptersDeep:  -> Skipping non-image entry: $entryName" }
                continue
            }
            var chapterNum: Int? = null
            var pageNum: Int? = null

            pageRegex.find(entryName)?.let { matchResult ->
                chapterNum = matchResult.groupValues[1].takeIf { it.isNotEmpty() }?.toInt()
                pageNum = matchResult.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toInt()
            }
            if (chapterNum == null && mangaFile.type == MangaType.CHAPTER) chapterNum = mangaFile.chapter.toInt()
            if (pageNum == null) pageNum = fallbackPageRegex.find(entryName)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.toInt()

            val finalPageNum = pageNum ?: pageCounterInFile.getAndIncrement()
            val finalChapterNum = chapterNum ?: mangaFile.chapter.toInt()
            val finalVolumeNum = mangaFile.volume
            logDebug { "combineChaptersDeep:  -> Parsed '$entryName' as V:$finalVolumeNum C:$finalChapterNum P:$finalPageNum" }
            allPages.add(MangaPage(mangaFile.file, entry, finalVolumeNum, finalChapterNum, finalPageNum))
        }
    }

    if (allPages.isEmpty()) {
        println("[$title] Warning: Deep scan found no valid pages. Aborting.")
        logDebug { "combineChaptersDeep: Aborting, no pages found after deep scan." }
        combinedZip.file.delete()
        return
    }

    println("[$title] Found ${allPages.size} total pages. Sorting, combining, and optimizing I/O...")
    val sortedPages = allPages.sorted()
    val pagesBySource = sortedPages.groupBy { it.sourceArchiveFile }
    var finalPageCounter = 0

    for ((sourceFile, pages) in pagesBySource) {
        println("[$title]  -> Processing ${pages.size} pages from ${sourceFile.name}")
        logDebug { "combineChaptersDeep: Adding ${pages.size} pages from source file '${sourceFile.name}'." }
        when (sourceFile.extension.lowercase()) {
            "cbz", "epub" -> try { ZipFile(sourceFile).use { zip -> for (page in pages) {
                val entry = page.entry as? ZipArchiveEntry ?: continue
                finalPageCounter++
                val newFileName = "p%05d.%s".format(finalPageCounter, page.entry.entryName.substringAfterLast('.'))
                val zipParameters = ZipParameters().apply { fileNameInZip = newFileName }
                zip.getInputStream(entry.header).use { stream -> combinedZip.addStream(stream, zipParameters) }
            } } } catch (e: Exception) { logDebug { "combineChaptersDeep: Error processing ZIP ${sourceFile.name}: ${e.message}" } }
            "cbr" -> try { Archive(sourceFile).use { archive -> for (page in pages) {
                val entry = page.entry as? RarArchiveEntry ?: continue
                finalPageCounter++
                val newFileName = "p%05d.%s".format(finalPageCounter, page.entry.entryName.substringAfterLast('.'))
                val zipParameters = ZipParameters().apply { fileNameInZip = newFileName }
                archive.getInputStream(entry.header).use { stream -> combinedZip.addStream(stream, zipParameters) }
            } } } catch (e: Exception) { logDebug { "combineChaptersDeep: Error processing CBR ${sourceFile.name}: ${e.message}" } }
        }
    }

    println("\n[$title] Successfully combined ${sortedPages.size} pages from ${mangaFiles.size} source files.")
    println("[$title] Output file created at: ${combinedZip.file.absolutePath}")
}
