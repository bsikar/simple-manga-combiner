package com.mangacombiner.util

import com.mangacombiner.service.DownloadOptions

data class OperationLogSettings(
    val chapterCount: Int,
    val userAgentName: String,
    val perWorkerUserAgent: Boolean,
    val proxy: String? = null,
    val isResuming: Boolean = false,
    val localCount: Int = 0,
    val cacheCount: Int = 0,
    val optimizeMode: Boolean = false,
    val cleanCache: Boolean = false,
    val skipExisting: Boolean = false,
    val updateExisting: Boolean = false,
    val force: Boolean = false,
    val maxWidth: Int? = null,
    val jpegQuality: Int? = null,
    val noZipCompression: Boolean = false
)

fun logOperationSettings(
    options: DownloadOptions,
    settings: OperationLogSettings
) {
    val title = if (settings.isResuming) "--- Resuming Operation ---" else "--- Starting New Operation ---"
    Logger.logInfo(title)
    if (options.dryRun && !settings.isResuming) {
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
    if (settings.localCount > 0) {
        Logger.logInfo("From Local File:   ${settings.localCount} chapters")
    }
    if (settings.cacheCount > 0) {
        Logger.logInfo("From Cache:        ${settings.cacheCount} chapters")
    }
    if (settings.chapterCount > 0) {
        Logger.logInfo("From Web:          ${settings.chapterCount} chapters")
    }
    Logger.logInfo("Download Workers:  ${options.getWorkers()}")

    val userAgentMessage = when {
        settings.perWorkerUserAgent -> "Randomized per worker"
        else -> settings.userAgentName
    }
    Logger.logInfo("Browser Profile:   $userAgentMessage")

    if (!settings.proxy.isNullOrBlank()) {
        Logger.logInfo("Proxy:             ${settings.proxy}")
    }

    // --- New Section for Flags & Optimizations ---
    if (settings.optimizeMode || settings.cleanCache || settings.skipExisting || settings.updateExisting || settings.force || settings.maxWidth != null || settings.jpegQuality != null || settings.noZipCompression) {
        Logger.logInfo("--- Flags & Options ---")
        if (settings.optimizeMode) {
            Logger.logInfo("Optimize Mode:       Enabled")
            Logger.logInfo("  - Max Image Width: ${settings.maxWidth ?: "Default"}px")
            Logger.logInfo("  - JPEG Quality:    ${settings.jpegQuality ?: "Default"}%")
            if (options.format == "epub") {
                Logger.logInfo("  - No Zip Compression: ${settings.noZipCompression}")
            }
        } else {
            if (settings.maxWidth != null) Logger.logInfo("Max Image Width:   ${settings.maxWidth}px")
            if (settings.jpegQuality != null) Logger.logInfo("JPEG Quality:      ${settings.jpegQuality}%")
            if (options.format == "epub" && settings.noZipCompression) Logger.logInfo("No Zip Compression: true")
        }

        if (settings.cleanCache) Logger.logInfo("Clean Cache on Success: Enabled")

        when {
            settings.force -> Logger.logInfo("Existing Files:    Overwrite (--force)")
            settings.skipExisting -> Logger.logInfo("Existing Files:    Skip (--skip-existing)")
            settings.updateExisting -> Logger.logInfo("Existing Files:    Update (--update-existing)")
        }
    }

    if (Logger.isDebugEnabled) {
        Logger.logDebug { "Full User-Agent string(s) used: ${options.getUserAgents().joinToString(", ")}" }
    }
    Logger.logInfo("---------------------------------------")
}
