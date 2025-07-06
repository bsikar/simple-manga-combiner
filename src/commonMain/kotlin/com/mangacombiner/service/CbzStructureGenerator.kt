package com.mangacombiner.service

import com.mangacombiner.model.ComicInfo
import com.mangacombiner.model.PageInfo
import com.mangacombiner.model.Pages
import nl.adaptivity.xmlutil.serialization.XML
import java.io.File

internal class CbzStructureGenerator(private val xmlSerializer: XML) {

    private fun String.isImageFile(): Boolean =
        substringAfterLast('.').lowercase() in ProcessorService.IMAGE_EXTENSIONS

    fun generateComicInfoXml(
        mangaTitle: String,
        bookmarks: List<Pair<Int, String>>,
        totalPageCount: Int,
        hasInfoPage: Boolean = false
    ): String {
        val pageInfos = (0 until totalPageCount).map { pageIndex ->
            val bookmark = bookmarks.firstOrNull { it.first == pageIndex }
            val isFirstPageOfChapter = bookmark != null
            val isInfoPage = hasInfoPage && pageIndex == 0

            PageInfo(
                image = pageIndex,
                bookmark = when {
                    isInfoPage -> "Information"
                    bookmark != null -> bookmark.second
                    else -> null
                },
                type = when {
                    isInfoPage -> PageInfo.TYPE_OTHER
                    isFirstPageOfChapter -> PageInfo.TYPE_STORY
                    else -> null
                }
            )
        }
        val comicInfo = ComicInfo(
            series = mangaTitle,
            title = mangaTitle,
            pageCount = totalPageCount,
            pages = Pages(pageInfos)
        )
        return xmlSerializer.encodeToString(ComicInfo.serializer(), comicInfo)
    }

    fun createBookmarks(sortedFolders: List<File>, hasInfoPage: Boolean = false): Pair<List<Pair<Int, String>>, Int> {
        // Start page count at 1 if info page exists (info page will be at index 0)
        var totalPageCount = if (hasInfoPage) 1 else 0
        val bookmarks = mutableListOf<Pair<Int, String>>()

        // Add info page bookmark if it exists
        if (hasInfoPage) {
            bookmarks.add(0 to "Information")
        }

        sortedFolders.forEach { folder ->
            val pageCount = folder.listFiles()?.count { it.isFile && it.name.isImageFile() } ?: 0
            if (pageCount > 0) {
                val cleanTitle = folder.name
                    .replace(Regex("[._-]"), " ")
                    .split(" ")
                    .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
                bookmarks.add(totalPageCount to cleanTitle)
                totalPageCount += pageCount
            }
        }
        return bookmarks to totalPageCount
    }
}
