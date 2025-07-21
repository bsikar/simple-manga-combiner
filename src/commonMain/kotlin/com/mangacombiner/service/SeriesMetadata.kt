package com.mangacombiner.service

import kotlinx.serialization.Serializable

@Serializable
data class SeriesMetadata(
    val title: String,
    val coverImageUrl: String? = null,
    val authors: List<String>? = null,
    val artists: List<String>? = null,
    val genres: List<String>? = null,
    val type: String? = null,
    val release: String? = null,
    val status: String? = null
)
