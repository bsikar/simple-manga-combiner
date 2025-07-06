package com.mangacombiner.service

import io.ktor.client.HttpClient
import java.io.File

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
 * @property dryRun If true, simulate the sync without modifying files.
 */
data class SyncOptions(
    val localPath: String,
    val seriesUrl: String,
    val imageWorkers: Int,
    val exclude: List<String>,
    val checkPageCounts: Boolean,
    val tempDir: File,
    val client: HttpClient,
    val dryRun: Boolean = false
)
