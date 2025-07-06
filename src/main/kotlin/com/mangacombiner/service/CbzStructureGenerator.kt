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
        totalPageCount: Int
    ): String {
        val pageInfos = (0 until totalPageCount).map { pageIndex ->
            val bookmark = bookmarks.firstOrNull { it.first == pageIndex }
            val isFirstPageOfChapter = bookmark != null
            PageInfo(
                image = pageIndex,
                bookmark = bookmark?.second,
                type = if (isFirstPageOfChapter) PageInfo.TYPE_STORY else null
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

    fun createBookmarks(sortedFolders: List<File>): Pair<List<Pair<Int, String>>, Int> {
        var totalPageCount = 0
        val bookmarks = mutableListOf<Pair<Int, String>>()
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
