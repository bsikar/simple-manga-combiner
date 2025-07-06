package com.mangacombiner.runner

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.multiple

internal class CliArgs(parser: ArgParser) {
    val source by parser.argument(ArgType.String, "source", "Source URL, local file, or glob pattern.")
    val update by parser.option(ArgType.String, "update", description = "Path to local CBZ to update.")
    val exclude by parser.option(ArgType.String, "exclude", "e", "Chapter URL slug to exclude.").multiple()
    val workers by parser.option(ArgType.Int, "workers", "w", "Concurrent image download threads.")
        .default(DEFAULT_IMAGE_WORKERS)
    val title by parser.option(ArgType.String, "title", "t", "Custom output file title.")
    val format by parser.option(ArgType.String, "format", description = "Output format ('cbz' or 'epub').")
        .default(DEFAULT_FORMAT)
    val force by parser.option(ArgType.Boolean, "force", "f", "Force overwrite of output file.")
        .default(false)
    val dryRun by parser.option(ArgType.Boolean, "dry-run", "d", "Simulate actions without making changes.")
        .default(false)
    val deleteOriginal by parser.option(ArgType.Boolean, "delete-original", "Delete source on success.")
        .default(false)
    val lowStorageMode by parser.option(ArgType.Boolean, "low-storage-mode", "Low RAM usage mode.")
        .default(false)
    val ultraLowStorageMode by parser.option(ArgType.Boolean, "ultra-low-storage-mode", "Aggressive low memory.")
        .default(false)
    val trueDangerousMode by parser.option(ArgType.Boolean, "true-dangerous-mode", "DANGER! Modifies source.")
        .default(false)
    val batchWorkers by parser.option(ArgType.Int, "batch-workers", "Concurrent local file processing.")
        .default(DEFAULT_BATCH_WORKERS)
    val debug by parser.option(ArgType.Boolean, "debug", description = "Enable debug logging.")
        .default(false)
    val skipIfTargetExists by parser.option(ArgType.Boolean, "skip-if-target-exists", "Skip if target exists.")
        .default(false)
    val updateMissing by parser.option(ArgType.Boolean, "update-missing", "Thoroughly check chapters.")
        .default(false)
    val tempIsDestination by parser.option(ArgType.Boolean, "temp-is-destination", "Use output dir for temp.")
        .default(false)
    val impersonateBrowser by parser.option(ArgType.Boolean, "impersonate-browser", "Use browser User-Agent.")
        .default(false)
    val interactive by parser.option(ArgType.Boolean, "interactive", "i", "Interactive chapter selection mode.")
        .default(false)

    companion object {
        const val DEFAULT_IMAGE_WORKERS = 2
        const val DEFAULT_BATCH_WORKERS = 4
        const val DEFAULT_FORMAT = "epub"
    }
}
