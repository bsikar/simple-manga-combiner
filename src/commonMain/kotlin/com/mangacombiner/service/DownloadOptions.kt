package com.mangacombiner.service

import java.io.File

data class DownloadOptions(
    val seriesUrl: String,
    val chaptersToDownload: Map<String, String>, // Map of <URL, Title>
    val cliTitle: String?,
    val imageWorkers: Int,
    val exclude: List<String>,
    val format: String,
    val tempDir: File,
    val userAgents: List<String>,
    val outputPath: String,
    val dryRun: Boolean = false
)
