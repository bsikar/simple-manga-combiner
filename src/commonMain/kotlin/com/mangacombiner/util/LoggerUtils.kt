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
    optimizeMode: Boolean = false,
    cleanCache: Boolean = false,
    skipExisting: Boolean = false,
    updateExisting: Boolean = false,
    force: Boolean = false,
    maxWidth: Int? = null,
    jpegQuality: Int? = null,
    noZipCompression: Boolean = false
) {
    val title = if (isResuming) "--- Resuming Operation ---" else "--- Starting New Operation ---"
    Logger.logInfo(title)
    if (options.dryRun && !isResuming) {
        Logger.logInfo("Mode:               Dry Run (no files will be downloaded or created)")
    }
    if (options.seriesUrl.isNotBlank()) {
        Logger.logInfo("Series URL:         ${options.seriesUrl}")
    }
    if (!options.cliTitle.isNullOrBlank()) {
        Logger.logInfo("Custom Title:       ${options.cliTitle}")
    }
    if (options.outputPath.isNotBlank()) {
        Logger.logInfo("Output Location:    ${options.outputPath}")
    }
    Logger.logInfo("Output Format:      ${options.format.uppercase()}")
    if (localCount > 0) {
        Logger.logInfo("From Local File:    $localCount chapters")
    }
    if (cacheCount > 0) {
        Logger.logInfo("From Cache:         $cacheCount chapters")
    }
    if (chapterCount > 0) {
        Logger.logInfo("From Web:           $chapterCount chapters")
    }
    Logger.logInfo("Download Workers:   ${options.getWorkers()}")

    val userAgentMessage = when {
        perWorkerUserAgent -> "Randomized per worker"
        else -> userAgentName
    }
    Logger.logInfo("Browser Profile:    $userAgentMessage")

    // --- New Section for Flags & Optimizations ---
    if (optimizeMode || cleanCache || skipExisting || updateExisting || force || maxWidth != null || jpegQuality != null || noZipCompression) {
        Logger.logInfo("--- Flags & Options ---")
        if (optimizeMode) {
            Logger.logInfo("Optimize Mode:        Enabled")
            Logger.logInfo("  - Max Image Width: ${maxWidth ?: "Default"}px")
            Logger.logInfo("  - JPEG Quality:    ${jpegQuality ?: "Default"}%")
            if (options.format == "epub") {
                Logger.logInfo("  - No Zip Compression: $noZipCompression")
            }
        } else {
            if (maxWidth != null) Logger.logInfo("Max Image Width:    ${maxWidth}px")
            if (jpegQuality != null) Logger.logInfo("JPEG Quality:       ${jpegQuality}%")
            if (options.format == "epub" && noZipCompression) Logger.logInfo("No Zip Compression: true")
        }

        if (cleanCache) Logger.logInfo("Clean Cache on Success: Enabled")

        when {
            force -> Logger.logInfo("Existing Files:     Overwrite (--force)")
            skipExisting -> Logger.logInfo("Existing Files:     Skip (--skip-existing)")
            updateExisting -> Logger.logInfo("Existing Files:     Update (--update-existing)")
        }
    }

    if (Logger.isDebugEnabled) {
        Logger.logDebug { "Full User-Agent string(s) used: ${options.getUserAgents().joinToString(", ")}" }
    }
    Logger.logInfo("---------------------------------------")
}
