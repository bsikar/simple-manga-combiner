package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.service.WebDavFile
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.util.Logger
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI

internal fun MainViewModel.handleWebDavEvent(event: Event.WebDav) {
    when (event) {
        is Event.WebDav.UpdateUrl -> _state.update { it.copy(webDavUrl = event.url) }
        is Event.WebDav.UpdateUser -> _state.update { it.copy(webDavUser = event.user) }
        is Event.WebDav.UpdatePass -> _state.update { it.copy(webDavPass = event.pass) }
        is Event.WebDav.ToggleIncludeHidden -> _state.update { it.copy(webDavIncludeHidden = event.include) }
        is Event.WebDav.ToggleFileSelection -> _state.update {
            val newSelection = it.webDavSelectedFiles.toMutableSet()
            if (event.isSelected) newSelection.add(event.fileHref) else newSelection.remove(event.fileHref)
            it.copy(webDavSelectedFiles = newSelection)
        }
        is Event.WebDav.NavigateTo -> navigateWebDav(event.file)
        is Event.WebDav.NavigateBack -> navigateWebDavBack()
        is Event.WebDav.Connect -> connectToWebDav()
        is Event.WebDav.DownloadSelected -> downloadFromWebDav()
    }
}

private fun MainViewModel.connectToWebDav() {
    val s = state.value
    if (s.webDavUrl.isBlank()) return

    viewModelScope.launch {
        _state.update { it.copy(isConnectingToWebDav = true, webDavError = null, webDavFiles = emptyList(), webDavSelectedFiles = emptySet()) }
        val result = webDavService.listFiles(s.webDavUrl, s.webDavUser, s.webDavPass, s.webDavIncludeHidden)
        result.onSuccess { files ->
            val uniqueFiles = files.distinctBy { it.href }
            if (uniqueFiles.size < files.size) {
                Logger.logDebug { "Removed ${files.size - uniqueFiles.size} duplicate file entries from WebDAV response." }
            }
            _state.update { it.copy(
                isConnectingToWebDav = false,
                webDavFiles = uniqueFiles
            ) }
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
            _state.update { it.copy(webDavUrl = newUrl) }
            connectToWebDav()
        } catch (e: Exception) {
            Logger.logError("Failed to construct navigation URL from: $currentUrl and href: ${file.href}", e)
        }
    }
}

private fun MainViewModel.navigateWebDavBack() {
    viewModelScope.launch {
        val currentUrl = state.value.webDavUrl
        if (currentUrl.isBlank()) return@launch

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

            _state.update { it.copy(webDavUrl = newUrl) }
            connectToWebDav()
        } catch (e: Exception) {
            Logger.logError("Failed to navigate back from URL: $currentUrl", e)
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

        val filesToDownload = s.webDavFiles.filter { it.href in s.webDavSelectedFiles }
        val totalFiles = filesToDownload.size
        var downloadedCount = 0

        Logger.logInfo("Download requested for ${filesToDownload.size} file(s) from WebDAV.")
        _state.update { it.copy(isDownloadingFromWebDav = true, webDavDownloadProgress = 0f, webDavStatus = "Starting download...") }

        val serverUri = URI(s.webDavUrl)
        val serverRoot = "${serverUri.scheme}://${serverUri.authority}"

        for ((index, file) in filesToDownload.withIndex()) {
            val fullUrl = serverRoot + file.href
            val destinationFile = File(s.outputPath, file.name)

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
                Logger.logInfo("Successfully downloaded: ${destinationFile.name}")
            } else {
                Logger.logError("Failed to download $fullUrl: ${result.exceptionOrNull()?.message}")
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
