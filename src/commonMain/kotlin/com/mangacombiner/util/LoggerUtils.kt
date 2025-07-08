package com.mangacombiner.util

import com.mangacombiner.service.DownloadOptions

fun logOperationSettings(
    options: DownloadOptions,
    chapterCount: Int,
    userAgentName: String,
    perWorkerUserAgent: Boolean,
    isResuming: Boolean = false
) {
    val title = if (isResuming) "--- Resuming Download Operation ---" else "--- Starting New Download Operation ---"
    Logger.logInfo(title)
    if (options.dryRun && !isResuming) {
        Logger.logInfo("Mode:              Dry Run (no files will be downloaded or created)")
    }
    Logger.logInfo("Series URL:        ${options.seriesUrl}")
    if (!options.cliTitle.isNullOrBlank()) {
        Logger.logInfo("Custom Title:      ${options.cliTitle}")
    }
    if (options.outputPath.isNotBlank()) {
        Logger.logInfo("Output Location:   ${options.outputPath}")
    }
    Logger.logInfo("Output Format:     ${options.format.uppercase()}")
    Logger.logInfo("Chapters to get:   $chapterCount")
    Logger.logInfo("Download Workers:  ${options.getWorkers()}")

    val userAgentMessage = when {
        perWorkerUserAgent -> "Randomized per worker"
        else -> userAgentName
    }
    Logger.logInfo("Browser Profile:   $userAgentMessage")
    if (Logger.isDebugEnabled) {
        Logger.logDebug { "Full User-Agent string(s) used: ${options.getUserAgents().joinToString(", ")}" }
    }
    Logger.logInfo("---------------------------------------")
}
