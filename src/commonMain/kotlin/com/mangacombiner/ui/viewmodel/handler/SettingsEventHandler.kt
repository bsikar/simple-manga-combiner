package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.model.AppSettings
import com.mangacombiner.model.ProxyType
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.FilePickerRequest
import com.mangacombiner.ui.viewmodel.state.ProxyStatus
import com.mangacombiner.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun MainViewModel.handleSettingsEvent(event: Event.Settings) {
    when (event) {
        is Event.Settings.UpdateTheme -> _state.update { it.copy(theme = event.theme) }
        is Event.Settings.UpdateFontSizePreset -> _state.update { it.copy(fontSizePreset = event.preset) }
        is Event.Settings.UpdateDefaultOutputLocation -> onUpdateDefaultOutputLocation(event.location)
        is Event.Settings.ToggleDebugLog -> onToggleDebugLog(event.isEnabled)
        is Event.Settings.UpdateWorkers -> _state.update { it.copy(workers = event.count.coerceIn(1, 16)) }
        is Event.Settings.UpdateBatchWorkers -> _state.update { it.copy(batchWorkers = event.count.coerceIn(1, 8)) }
        is Event.Settings.UpdateUserAgent -> _state.update { it.copy(userAgentName = event.name) }
        is Event.Settings.UpdateProxyUrl -> _state.update { it.copy(proxyUrl = event.url) }
        is Event.Settings.TogglePerWorkerUserAgent -> _state.update { it.copy(perWorkerUserAgent = event.isEnabled) }
        is Event.Settings.ToggleOfflineMode -> _state.update { it.copy(offlineMode = event.isEnabled) }
        Event.Settings.PickCustomDefaultPath -> viewModelScope.launch {
            _filePickerRequest.emit(FilePickerRequest.OpenFolder(FilePickerRequest.PathType.CUSTOM_OUTPUT))
        }
        Event.Settings.OpenCacheLocation -> viewModelScope.launch(Dispatchers.IO) {
            platformProvider.openCacheLocation()
        }
        Event.Settings.OpenSettingsLocation -> viewModelScope.launch(Dispatchers.IO) {
            platformProvider.openSettingsLocation()
        }
        Event.Settings.ZoomIn -> _state.update { it.copy(zoomFactor = (it.zoomFactor + 0.1f).coerceIn(0.5f, 2.0f)) }
        Event.Settings.ZoomOut -> _state.update { it.copy(zoomFactor = (it.zoomFactor - 0.1f).coerceIn(0.5f, 2.0f)) }
        Event.Settings.ZoomReset -> _state.update { it.copy(zoomFactor = 1.0f) }
        Event.Settings.RequestRestoreDefaults -> _state.update { it.copy(showRestoreDefaultsDialog = true) }
        Event.Settings.ConfirmRestoreDefaults -> onConfirmRestoreDefaults()
        Event.Settings.CancelRestoreDefaults -> _state.update { it.copy(showRestoreDefaultsDialog = false) }
    }
}

private fun MainViewModel.onUpdateDefaultOutputLocation(location: String) {
    val newPath = when (location) {
        "Downloads" -> platformProvider.getUserDownloadsDir()
        "Documents" -> platformProvider.getUserDocumentsDir()
        "Desktop" -> platformProvider.getUserDesktopDir()
        "Custom" -> _state.value.customDefaultOutputPath
        else -> ""
    } ?: ""
    _state.update { it.copy(defaultOutputLocation = location, outputPath = newPath) }
    checkOutputFileExistence()
}

private fun MainViewModel.onToggleDebugLog(isEnabled: Boolean) {
    Logger.isDebugEnabled = isEnabled
    _state.update { it.copy(debugLog = isEnabled) }
}

private fun MainViewModel.onConfirmRestoreDefaults() {
    val defaultSettings = AppSettings()
    _state.update {
        it.copy(
            showRestoreDefaultsDialog = false,
            theme = defaultSettings.theme,
            defaultOutputLocation = defaultSettings.defaultOutputLocation,
            customDefaultOutputPath = defaultSettings.customDefaultOutputPath,
            workers = defaultSettings.workers,
            outputFormat = defaultSettings.outputFormat,
            userAgentName = defaultSettings.userAgentName,
            perWorkerUserAgent = defaultSettings.perWorkerUserAgent,
            proxyUrl = defaultSettings.proxyUrl,
            debugLog = defaultSettings.debugLog,
            logAutoscrollEnabled = defaultSettings.logAutoscrollEnabled,
            zoomFactor = defaultSettings.zoomFactor,
            fontSizePreset = defaultSettings.fontSizePreset,
            offlineMode = defaultSettings.offlineMode
        )
    }
    Logger.logInfo("All settings restored to default values.")
}
