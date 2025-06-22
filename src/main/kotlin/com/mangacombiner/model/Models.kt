package com.mangacombiner.model

import com.github.junrar.rarfile.FileHeader as RarFileHeader
import net.lingala.zip4j.model.FileHeader as ZipFileHeader
import java.io.File

enum class MangaType { VOLUME, CHAPTER }

data class MangaChapter(val file: File, val title: String, val type: MangaType, val volume: Int, val chapter: Double) : Comparable<MangaChapter> {
    override fun compareTo(other: MangaChapter): Int {
        if (this.title != other.title) return this.title.compareTo(other.title)
        val thisSortKey = if (type == MangaType.VOLUME) volume * 1000.0 else chapter
        val otherSortKey = if (other.type == MangaType.VOLUME) other.volume * 1000.0 else other.chapter
        return thisSortKey.compareTo(otherSortKey)
    }
}

sealed interface ArchiveEntry {
    val entryName: String
}
data class ZipArchiveEntry(val header: ZipFileHeader) : ArchiveEntry {
    override val entryName: String get() = header.fileName
}
data class RarArchiveEntry(val header: RarFileHeader) : ArchiveEntry {
    override val entryName: String get() = header.fileName
}

data class MangaPage(
    val sourceArchiveFile: File,
    val entry: ArchiveEntry,
    val volume: Int,
    val chapter: Int,
    val page: Int
) : Comparable<MangaPage> {
    override fun compareTo(other: MangaPage): Int {
        if (this.volume != 0 && other.volume != 0 && this.volume != other.volume) {
            return this.volume.compareTo(other.volume)
        }
        if (this.chapter != other.chapter) return this.chapter.compareTo(other.chapter)
        return this.page.compareTo(other.page)
    }
}
