package com.mangacombiner.util

import kotlin.test.Test
import kotlin.test.assertEquals

class SlugUtilsTest {

    @Test
    fun `test parseChapterSlugsForSorting extracts numbers correctly`() {
        assertEquals(listOf(1, 5), SlugUtils.parseChapterSlugsForSorting("chapter-1-part-5"))
        assertEquals(listOf(10), SlugUtils.parseChapterSlugsForSorting("chapter-10"))
        assertEquals(listOf(10, 5), SlugUtils.parseChapterSlugsForSorting("chapter-10.5"))
        assertEquals(emptyList<Int>(), SlugUtils.parseChapterSlugsForSorting("chapter-extra"))
    }

    @Test
    fun `test normalizeChapterSlug pads numbers and replaces separators`() {
        assertEquals("chapter.0001", SlugUtils.normalizeChapterSlug("chapter-1"))
        assertEquals("chapter.0010", SlugUtils.normalizeChapterSlug("chapter_10"))
        assertEquals("chapter.0010.0005", SlugUtils.normalizeChapterSlug("chapter-10-5"))
        assertEquals("volume.0001.chapter.0010", SlugUtils.normalizeChapterSlug("volume-1-chapter-10"))
        assertEquals("no.numbers.here", SlugUtils.normalizeChapterSlug("no-numbers_here"))
    }
}
