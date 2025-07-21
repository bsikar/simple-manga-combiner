package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.service.WebDavFile
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.SortCriteria
import com.mangacombiner.ui.viewmodel.state.SortDirection
import com.mangacombiner.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import java.io.File
import java.net.URI

internal fun MainViewModel.handleWebDavEvent(event: Event.WebDav) {
    when (event) {
        is Event.WebDav.UpdateUrl -> _state.update { it.copy(webDavUrl = event.url) }
        is Event.WebDav.UpdateUser -> _state.update { it.copy(webDavUser = event.user) }
        is Event.WebDav.UpdatePass -> _state.update { it.copy(webDavPass = event.pass) }
        is Event.WebDav.ToggleIncludeHidden -> _state.update { it.copy(webDavIncludeHidden = event.include) }
        is Event.WebDav.ToggleFileSelection -> onToggleWebDavSelection(event.fileHref, event.isSelected)
        is Event.WebDav.NavigateTo -> navigateWebDav(event.file)
        is Event.WebDav.NavigateBack -> navigateWebDavBack()
        is Event.WebDav.Connect -> connectToWebDav()
        is Event.WebDav.DownloadSelected -> downloadFromWebDav()
        is Event.WebDav.CancelDownload -> {
            _state.update { it.copy(webDavStatus = "Cancelling...") }
            webDavDownloadJob?.cancel()
        }
        is Event.WebDav.SetSort -> _state.update { it.copy(webDavSortState = event.sortState) }
        is Event.WebDav.UpdateFilterQuery -> _state.update { it.copy(webDavFilterQuery = event.query) }
        is Event.WebDav.SelectAll -> onSelectAllWebDav(true)
        is Event.WebDav.DeselectAll -> onSelectAllWebDav(false)
    }
}

private fun MainViewModel.connectToWebDav() {
    val s = state.value
    if (s.webDavUrl.isBlank()) return

    viewModelScope.launch {
        _state.update { it.copy(isConnectingToWebDav = true, webDavError = null, webDavFiles = emptyList()) }
        val result = webDavService.listFiles(s.webDavUrl, s.webDavUser, s.webDavPass, s.webDavIncludeHidden)
        result.onSuccess { files ->
            val uniqueFiles = files.distinctBy { it.href }
            _state.update {
                val newCache = it.webDavFileCache.toMutableMap()
                uniqueFiles.forEach { file -> newCache[file.href] = file }
                it.copy(
                    isConnectingToWebDav = false,
                    webDavFiles = uniqueFiles,
                    webDavFileCache = newCache
                )
            }
        }.onFailure { error ->
            _state.update { it.copy(isConnectingToWebDav = false, webDavError = error.message ?: "An unknown error occurred.") }
        }
    }
}

private fun MainViewModel.navigateWebDav(file: WebDavFile) {
    if (!file.isDirectory) return
    viewModelScope.launch {
        val currentUrl = state.value.webDavUrl
        try {
            val serverUri = URI(currentUrl)
            val serverRoot = "${serverUri.scheme}://${serverUri.authority}"
            val newUrl = serverRoot + file.href
            _state.update { it.copy(webDavUrl = newUrl, webDavFilterQuery = "") }
            connectToWebDav()
        } catch (e: Exception) {
            Logger.logError("Failed to construct navigation URL from: $currentUrl and href: ${file.href}", e)
        }
    }
}

private fun MainViewModel.navigateWebDavBack() {
    viewModelScope.launch {
        val currentUrl = state.value.webDavUrl
        try {
            val uri = URI(currentUrl)
            val path = uri.path?.trim('/') ?: ""
            if (path.isEmpty()) {
                Logger.logInfo("At root directory, cannot navigate back further.")
                return@launch
            }
            val parentPath = if (path.contains('/')) path.substringBeforeLast('/') else ""
            val serverRoot = "${uri.scheme}://${uri.authority}"
            val newUrl = "$serverRoot/$parentPath/"
            _state.update { it.copy(webDavUrl = newUrl, webDavFilterQuery = "") }
            connectToWebDav()
        } catch (e: Exception) {
            Logger.logError("Failed to navigate back from URL: $currentUrl", e)
        }
    }
}

private var webDavDownloadJob: Job? = null
private fun MainViewModel.downloadFromWebDav() {
    webDavDownloadJob = viewModelScope.launch {
        val s = state.value
        if (s.outputPath.isBlank()) {
            _state.update { it.copy(webDavError = "Output path is not set. Please set it in Settings.") }
            return@launch
        }
        val itemsToDownload = s.webDavFileCache.values.filter { it.href in s.webDavSelectedFiles }
        var downloadedCount = 0

        try {
            Logger.logInfo("Download requested for ${itemsToDownload.size} item(s) from WebDAV.")
            _state.update { it.copy(isDownloadingFromWebDav = true, webDavDownloadProgress = 0f, webDavStatus = "Starting download...") }

            val serverUri = URI(s.webDavUrl)
            val serverRoot = "${serverUri.scheme}://${serverUri.authority}"

            for ((index, item) in itemsToDownload.withIndex()) {
                if (!isActive) break // Check for cancellation before starting next item

                if (item.isDirectory) {
                    val zipFile = File(s.outputPath, "${item.name}.zip")
                    Logger.logInfo("Starting download of folder '${item.name}' as a ZIP archive...")
                    _state.update { it.copy(webDavStatus = "Scanning folder ${index + 1}/${itemsToDownload.size}: ${item.name}") }
                    val filesInFolderResult = webDavService.scanDirectoryRecursively(serverRoot + item.href, s.webDavUser, s.webDavPass, s.webDavIncludeHidden)

                    filesInFolderResult.onSuccess { filesInFolder ->
                        Logger.logInfo("Found ${filesInFolder.size} files in folder '${item.name}'. Starting download and zip...")
                        ZipFile(zipFile).use { zip ->
                            filesInFolder.forEachIndexed { fileIndex, fileToZip ->
                                if (!isActive) return@forEachIndexed
                                _state.update {
                                    val overallProgress = (index.toFloat() + ((fileIndex + 1).toFloat() / filesInFolder.size)) / itemsToDownload.size
                                    it.copy(
                                        webDavStatus = "Zipping ${item.name} (${fileIndex + 1}/${filesInFolder.size}): ${fileToZip.name}",
                                        webDavDownloadProgress = overallProgress
                                    )
                                }
                                val tempFile = File.createTempFile("webdav-", fileToZip.name)
                                webDavService.downloadFile(serverRoot + fileToZip.href, s.webDavUser, s.webDavPass, tempFile) { _, _ -> }.getOrThrow()
                                val zipPath = fileToZip.fullPath.removePrefix(item.fullPath).trimStart('/')
                                val params = net.lingala.zip4j.model.ZipParameters().apply { fileNameInZip = zipPath }
                                zip.addFile(tempFile, params)
                                tempFile.delete()
                            }
                        }
                        if (isActive) {
                            Logger.logInfo("Successfully created ZIP for folder: ${zipFile.absolutePath}")
                            downloadedCount++
                        }
                    }.onFailure {
                        Logger.logError("Failed to scan or download folder '${item.name}': ${it.message}")
                    }
                } else {
                    val destinationFile = File(s.outputPath, item.name)
                    _state.update { it.copy(webDavStatus = "Downloading ${index + 1}/${itemsToDownload.size}: ${item.name}") }
                    val result = webDavService.downloadFile(serverRoot + item.href, s.webDavUser, s.webDavPass, destinationFile) { bytes, total ->
                        if (total > 0) {
                            val fileProgress = bytes.toFloat() / total
                            val overallProgress = (index + fileProgress) / itemsToDownload.size
                            _state.update { it.copy(webDavDownloadProgress = overallProgress) }
                        }
                    }
                    if (result.isSuccess) {
                        downloadedCount++
                        Logger.logInfo("Successfully downloaded: ${destinationFile.name}")
                    } else {
                        Logger.logError("Failed to download ${item.name}: ${result.exceptionOrNull()?.message}")
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                val wasCancelled = !isActive
                val finalStatus = if (wasCancelled) "Download cancelled." else "Download complete."
                val finalMessage = if (wasCancelled) {
                    "Download cancelled. $downloadedCount of ${itemsToDownload.size} items were downloaded."
                } else {
                    "Successfully downloaded $downloadedCount of ${itemsToDownload.size} selected items."
                }
                _state.update {
                    it.copy(
                        isDownloadingFromWebDav = false,
                        webDavStatus = finalStatus,
                        completionMessage = finalMessage,
                        webDavSelectedFiles = emptySet(),
                        webDavFolderSizes = emptyMap()
                    )
                }
            }
        }
    }
}

private fun MainViewModel.onSelectAllWebDav(select: Boolean) {
    _state.update {
        val currentVisibleHrefs = it.webDavFiles.map { file -> file.href }.toSet()
        val newSelection = it.webDavSelectedFiles.toMutableSet()
        if (select) {
            newSelection.addAll(currentVisibleHrefs)
        } else {
            newSelection.removeAll(currentVisibleHrefs)
        }
        it.copy(webDavSelectedFiles = newSelection)
    }
}

private fun MainViewModel.onToggleWebDavSelection(fileHref: String, isSelected: Boolean) {
    _state.update {
        val selection = it.webDavSelectedFiles.toMutableSet()
        val folderSizes = it.webDavFolderSizes.toMutableMap()
        val file = it.webDavFileCache[fileHref] ?: return@update it

        if (isSelected) {
            selection.add(fileHref)
            if (file.isDirectory) {
                folderSizes[fileHref] = null // Mark as calculating
                viewModelScope.launch {
                    val s = state.value
                    val serverUri = URI(s.webDavUrl)
                    val serverRoot = "${serverUri.scheme}://${serverUri.authority}"
                    val folderUrl = serverRoot + file.href
                    val result = webDavService.scanDirectoryRecursively(folderUrl, s.webDavUser, s.webDavPass, s.webDavIncludeHidden)
                    result.onSuccess { filesInFolder ->
                        val totalSize = filesInFolder.sumOf { f -> f.size }
                        _state.update { current ->
                            current.copy(webDavFolderSizes = current.webDavFolderSizes + (fileHref to totalSize))
                        }
                    }.onFailure {
                        _state.update { current ->
                            current.copy(webDavFolderSizes = current.webDavFolderSizes + (fileHref to -1L)) // Use -1 to indicate error
                        }
                    }
                }
            }
        } else {
            selection.remove(fileHref)
            if (file.isDirectory) {
                folderSizes.remove(fileHref)
            }
        }
        it.copy(webDavSelectedFiles = selection, webDavFolderSizes = folderSizes)
    }
}
