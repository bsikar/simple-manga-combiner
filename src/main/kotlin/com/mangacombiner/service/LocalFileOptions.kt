package com.mangacombiner.service

import java.io.File

/**
 * Defines options for processing a single local file.
 *
 * @property inputFile The source file (CBZ or EPUB) to process.
 * @property customTitle An optional title to override the one inferred from the filename.
 * @property outputFormat The desired output format ("cbz" or "epub").
 * @property forceOverwrite If true, overwrite the destination file if it exists.
 * @property deleteOriginal If true, delete the source file upon successful conversion.
 * @property useStreamingConversion A flag to enable memory-saving streaming modes.
 * @property useTrueStreaming An aggressive streaming mode that uses even less memory.
 * @property useTrueDangerousMode A high-risk mode that modifies the source file in-place.
 * @property skipIfTargetExists If true, do not process the file if the output file already exists.
 * @property tempDirectory The directory to use for temporary files.
 * @property generateInfoPage If true, generate a dynamic informational first page.
 * @property dryRun If true, simulate the processing without creating files.
 */
data class LocalFileOptions(
    val inputFile: File,
    val customTitle: String?,
    val outputFormat: String,
    val forceOverwrite: Boolean,
    val deleteOriginal: Boolean,
    val useStreamingConversion: Boolean,
    val useTrueStreaming: Boolean,
    val useTrueDangerousMode: Boolean,
    val skipIfTargetExists: Boolean,
    val tempDirectory: File,
    val generateInfoPage: Boolean = false,
    val dryRun: Boolean = false
)
