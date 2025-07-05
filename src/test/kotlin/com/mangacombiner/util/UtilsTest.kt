package com.mangacombiner.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UtilsTest {

    @Test
    fun `test parseChapterSlugsForSorting extracts numbers correctly`() {
        assertEquals(listOf(1, 5), parseChapterSlugsForSorting("chapter-1-part-5"))
        assertEquals(listOf(10), parseChapterSlugsForSorting("chapter-10"))
        assertEquals(listOf(10, 5), parseChapterSlugsForSorting("chapter-10.5"))
        assertEquals(emptyList<Int>(), parseChapterSlugsForSorting("chapter-extra"))
    }

    @Test
    fun `test normalizeChapterSlug pads numbers and replaces separators`() {
        assertEquals("chapter.0001", normalizeChapterSlug("chapter-1"))
        assertEquals("chapter.0010", normalizeChapterSlug("chapter_10"))
        assertEquals("chapter.0010.0005", normalizeChapterSlug("chapter-10-5"))
        assertEquals("volume.0001.chapter.0010", normalizeChapterSlug("volume-1-chapter-10"))
        assertEquals("no.numbers.here", normalizeChapterSlug("no-numbers_here"))
    }
}
