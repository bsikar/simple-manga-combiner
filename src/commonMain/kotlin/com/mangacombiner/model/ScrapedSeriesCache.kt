package com.mangacombiner.model

import kotlinx.serialization.Serializable

@Serializable
data class ScrapedSeries(
    val title: String,
    val url: String,
    val chapterCount: Int?
)

@Serializable
data class ScrapedWebsiteCache(
    val lastUpdated: Long,
    val series: List<ScrapedSeries>
)

@Serializable
data class ScrapedSeriesCache(
    // The key is the hostname, e.g., "mangaread.org"
    val websites: Map<String, ScrapedWebsiteCache>
)
