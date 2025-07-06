package com.mangacombiner.runner

import java.io.File

internal data class JobOptions(
    val source: String,
    val format: String,
    val tempDirectory: File,
    val storageMode: String,
    val userAgent: String,
    val workers: Int,
    val batchWorkers: Int,
    val force: Boolean,
    val deleteOriginal: Boolean,
    val skip: Boolean,
    val debug: Boolean,
    val dryRun: Boolean,
    val interactive: Boolean
)
