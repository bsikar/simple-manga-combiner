package com.mangacombiner.model

import com.mangacombiner.ui.viewmodel.state.Chapter

/**
 * A data class holding the detailed configuration for a job in the download queue.
 */
data class QueuedOperation(
    val jobId: String,
    val seriesUrl: String,
    val customTitle: String,
    val outputFormat: String,
    val outputPath: String,
    val chapters: List<Chapter>,
    val workers: Int,
    val userAgents: List<String>,
)
