package com.mangacombiner.model

import com.mangacombiner.service.SeriesMetadata
import com.mangacombiner.ui.viewmodel.state.Chapter
import kotlinx.serialization.Serializable

/**
 * A data class holding the detailed configuration for a job in the download queue.
 */
@Serializable
data class QueuedOperation(
    val jobId: String,
    val seriesUrl: String,
    val customTitle: String,
    val outputFormat: String,
    val outputPath: String,
    val chapters: List<Chapter>,
    val workers: Int,
    val userAgents: List<String>,
    val allowNsfw: Boolean,
    // This field will not be serialized by default json, but will be present in memory
    @kotlinx.serialization.Transient
    val seriesMetadata: SeriesMetadata? = null
)
