package com.mangacombiner.service

import com.mangacombiner.ui.viewmodel.OperationState
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class DownloadOptions(
    val seriesUrl: String,
    val chaptersToDownload: Map<String, String>, // Map of <URL, Title>
    val cliTitle: String?,
    val imageWorkers: Int,
    val exclude: List<String>,
    val format: String,
    val tempDir: File,
    val getUserAgents: () -> List<String>,
    val outputPath: String,
    val operationState: StateFlow<OperationState>,
    val dryRun: Boolean = false
)
