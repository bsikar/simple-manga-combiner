package com.mangacombiner.util

import com.mangacombiner.service.DownloadOptions

fun logOperationSettings(
    options: DownloadOptions,
    chapterCount: Int,
    userAgentName: String,
    perWorkerUserAgent: Boolean,
    isResuming: Boolean = false,
    localCount: Int = 0,
    cacheCount: Int = 0,
) {
    val title = if (isResuming) "--- Resuming Operation ---" else "--- Starting New Operation ---"
    Logger.logInfo(title)
    if (options.dryRun && !isResuming) {
        Logger.logInfo("Mode:              Dry Run (no files will be downloaded or created)")
    }
    if (options.seriesUrl.isNotBlank()) {
        Logger.logInfo("Series URL:        ${options.seriesUrl}")
    }
    if (!options.cliTitle.isNullOrBlank()) {
        Logger.logInfo("Custom Title:      ${options.cliTitle}")
    }
    if (options.outputPath.isNotBlank()) {
        Logger.logInfo("Output Location:   ${options.outputPath}")
    }
    Logger.logInfo("Output Format:     ${options.format.uppercase()}")
    if (localCount > 0) {
        Logger.logInfo("From Local File:   $localCount chapters")
    }
    if (cacheCount > 0) {
        Logger.logInfo("From Cache:        $cacheCount chapters")
    }
    if (chapterCount > 0) {
        Logger.logInfo("From Web:          $chapterCount chapters")
    }
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
