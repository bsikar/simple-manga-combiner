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
 * @property dryRun If true, simulate the download without creating files.
 */
data class DownloadOptions(
    val seriesUrl: String,
    val cliTitle: String?,
    val imageWorkers: Int,
    val exclude: List<String>,
    val format: String,
    val tempDir: File,
    val client: HttpClient,
    val generateInfoPage: Boolean = false,
    val dryRun: Boolean = false
)
