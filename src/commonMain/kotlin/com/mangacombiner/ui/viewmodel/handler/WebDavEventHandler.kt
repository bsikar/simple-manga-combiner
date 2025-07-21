package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.util.Logger
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

internal fun MainViewModel.handleWebDavEvent(event: Event.WebDav) {
    when (event) {
        is Event.WebDav.UpdateUrl -> _state.update { it.copy(webDavUrl = event.url) }
        is Event.WebDav.UpdateUser -> _state.update { it.copy(webDavUser = event.user) }
        is Event.WebDav.UpdatePass -> _state.update { it.copy(webDavPass = event.pass) }
        is Event.WebDav.ToggleFileSelection -> _state.update {
            val newSelection = it.webDavSelectedFiles.toMutableSet()
            if (event.isSelected) newSelection.add(event.filePath) else newSelection.remove(event.filePath)
            it.copy(webDavSelectedFiles = newSelection)
        }
        is Event.WebDav.Connect -> connectToWebDav()
        is Event.WebDav.DownloadSelected -> downloadFromWebDav()
    }
}

private fun MainViewModel.connectToWebDav() {
    val s = state.value
    if (s.webDavUrl.isBlank()) return

    viewModelScope.launch {
        _state.update { it.copy(isConnectingToWebDav = true, webDavError = null, webDavFiles = emptyList(), webDavSelectedFiles = emptySet()) }
        val result = webDavService.listFiles(s.webDavUrl, s.webDavUser, s.webDavPass)
        result.onSuccess { files ->
            _state.update { it.copy(
                isConnectingToWebDav = false,
                webDavFiles = files.filter { f -> !f.isDirectory && f.name.endsWith(".epub", ignoreCase = true) }
            ) }
        }.onFailure { error ->
            _state.update { it.copy(isConnectingToWebDav = false, webDavError = error.message ?: "An unknown error occurred.") }
        }
    }
}

private fun MainViewModel.downloadFromWebDav() {
    viewModelScope.launch {
        val s = state.value
        if (s.outputPath.isBlank()) {
            _state.update { it.copy(webDavError = "Output path is not set. Please set it in Settings.") }
            return@launch
        }

        val filesToDownload = s.webDavSelectedFiles
        val totalFiles = filesToDownload.size
        var downloadedCount = 0

        _state.update { it.copy(isDownloadingFromWebDav = true, webDavDownloadProgress = 0f, webDavStatus = "Starting download...") }

        for ((index, filePath) in filesToDownload.withIndex()) {
            val fullUrl = s.webDavUrl.trimEnd('/') + "/" + filePath
            val destinationFile = File(s.outputPath, filePath.substringAfterLast('/'))

            _state.update { it.copy(webDavStatus = "Downloading ${index + 1}/$totalFiles: ${destinationFile.name}") }

            val result = webDavService.downloadFile(fullUrl, s.webDavUser, s.webDavPass, destinationFile) { bytesSent, totalBytes ->
                if (totalBytes > 0) {
                    val fileProgress = bytesSent.toFloat() / totalBytes
                    val overallProgress = (index + fileProgress) / totalFiles
                    _state.update { it.copy(webDavDownloadProgress = overallProgress) }
                }
            }

            if (result.isSuccess) {
                downloadedCount++
            } else {
                Logger.logError("Failed to download $fullUrl")
            }
        }

        _state.update {
            it.copy(
                isDownloadingFromWebDav = false,
                webDavStatus = "Download complete.",
                completionMessage = "Successfully downloaded $downloadedCount of $totalFiles files.",
                webDavSelectedFiles = emptySet()
            )
        }
    }
}
