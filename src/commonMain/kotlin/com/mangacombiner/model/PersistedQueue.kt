package com.mangacombiner.model

import kotlinx.serialization.Serializable

/**
 * A serializable wrapper for a list of QueuedOperation objects,
 * used for persisting the download queue to a file.
 */
@Serializable
data class PersistedQueue(
    val operations: List<QueuedOperation>
)
