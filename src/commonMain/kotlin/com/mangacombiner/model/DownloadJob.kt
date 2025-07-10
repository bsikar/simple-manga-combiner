package com.mangacombiner.model

/**
 * A data class representing a single job in the download queue.
 */
data class DownloadJob(
    val id: String,
    val title: String,
    val progress: Float,
    val status: String,
    val totalChapters: Int,
    val downloadedChapters: Int
)
