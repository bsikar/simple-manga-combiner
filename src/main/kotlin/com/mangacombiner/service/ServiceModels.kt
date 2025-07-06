package com.mangacombiner.service

import io.ktor.client.HttpClient
import java.io.File

/**
 * Defines options for downloading a new series.
 *
 * @property seriesUrl The URL of the manga series page.
 * @property cliTitle An optional title to override the one inferred from the URL.
 * @property imageWorkers The number of concurrent image download workers.
 * @property exclude A list of chapter URL slugs to exclude from the download.
 * @property format The desired output format ("cbz" or "epub").
 * @property tempDir The directory to use for temporary files.
 * @property client The HttpClient to use for downloads.
 * @property generateInfoPage If true, generate a dynamic informational first page.
 */
data class DownloadOptions(
    val seriesUrl: String,
    val cliTitle: String?,
    val imageWorkers: Int,
    val exclude: List<String>,
    val format: String,
    val tempDir: File,
    val client: HttpClient,
    val generateInfoPage: Boolean = false
)

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
    val generateInfoPage: Boolean = false
)

/**
 * Represents the result of a file processing operation.
 *
 * @property success Indicates whether the operation was successful.
 * @property outputFile The file that was created, if any.
 * @property error An error message if the operation failed.
 */
data class ProcessResult(
    val success: Boolean,
    val outputFile: File? = null,
    val error: String? = null
)

/**
 * Defines options for synchronizing a local file with an online source.
 *
 * @property localPath The path to the local CBZ or EPUB file to be updated.
 * @property seriesUrl The URL of the manga series page to sync from.
 * @property imageWorkers The number of concurrent image download workers.
 * @property exclude A list of chapter URL slugs to exclude from the sync.
 * @property checkPageCounts If true, perform a thorough check by comparing online and local page counts.
 * @property tempDir The directory to use for temporary files.
 * @property client The HttpClient to use for downloads.
 */
data class SyncOptions(
    val localPath: String,
    val seriesUrl: String,
    val imageWorkers: Int,
    val exclude: List<String>,
    val checkPageCounts: Boolean,
    val tempDir: File,
    val client: HttpClient
)
