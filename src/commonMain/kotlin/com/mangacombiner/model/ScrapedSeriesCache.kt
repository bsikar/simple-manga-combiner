package com.mangacombiner.model

import kotlinx.serialization.Serializable

@Serializable
data class ScrapedSeries(
    val title: String,
    val url: String,
    val chapterCount: Int?
)

@Serializable
data class ScrapedSeriesCache(
    val lastUpdated: Long,
    val series: List<ScrapedSeries>
)
