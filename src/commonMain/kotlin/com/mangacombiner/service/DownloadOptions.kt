package com.mangacombiner.service

import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class DownloadOptions(
    val seriesUrl: String,
    val chaptersToDownload: Map<String, String>,
    val cliTitle: String?,
    val getWorkers: () -> Int,
    val exclude: List<String>,
    val format: String,
    val tempDir: File,
    val getUserAgents: () -> List<String>,
    val outputPath: String,
    val isPaused: () -> Boolean,
    val dryRun: Boolean = false,
    val onProgressUpdate: (progress: Float, status: String) -> Unit,
    val onChapterCompleted: (chapterUrl: String) -> Unit
)
