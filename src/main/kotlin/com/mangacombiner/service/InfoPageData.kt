package com.mangacombiner.service

import java.io.File

data class InfoPageData(
    val title: String,
    val sourceUrl: String,
    val lastUpdated: String?,
    val chapterCount: Int,
    val pageCount: Int,
    val tempDir: File
)
