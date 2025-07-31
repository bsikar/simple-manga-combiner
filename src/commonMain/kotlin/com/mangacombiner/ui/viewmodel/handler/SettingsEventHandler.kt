package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.model.AppSettings
import com.mangacombiner.model.ProxyType
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.FilePickerRequest
import com.mangacombiner.ui.viewmodel.state.ProxyStatus
import com.mangacombiner.ui.viewmodel.state.Screen
import com.mangacombiner.util.Logger
import com.mangacombiner.util.createHttpClient
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        is Event.Settings.ToggleAllowNsfw -> _state.update { it.copy(allowNsfw = event.isEnabled) } // New event handler
        is Event.Settings.ToggleProxyOnStartup -> _state.update { it.copy(proxyEnabledOnStartup = event.isEnabled) }
        is Event.Settings.UpdateIpLookupUrl -> _state.update { it.copy(ipLookupUrl = event.url) }
        is Event.Settings.UpdateCustomIpLookupUrl -> _state.update { it.copy(customIpLookupUrl = event.url) }
        Event.Settings.VerifyProxy -> verifyProxyConnection()
        Event.Settings.CheckIpAddress -> checkIpAddress()
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

        is Event.Settings.UpdateProxyType -> {
            _state.update { it.copy(proxyType = event.type) }
            onUpdateProxySetting()
        }
        is Event.Settings.UpdateProxyHost -> {
            _state.update { it.copy(proxyHost = event.host) }
            onUpdateProxySetting()
        }
        is Event.Settings.UpdateProxyPort -> {
            _state.update { it.copy(proxyPort = event.port) }
            onUpdateProxySetting()
        }
        is Event.Settings.UpdateProxyUser -> {
            _state.update { it.copy(proxyUser = event.user) }
            onUpdateProxySetting()
        }
        is Event.Settings.UpdateProxyPass -> {
            _state.update { it.copy(proxyPass = event.pass) }
            onUpdateProxySetting()
        }
        is Event.Settings.AddLibraryScanPath -> viewModelScope.launch {
            _filePickerRequest.emit(FilePickerRequest.OpenFolder(FilePickerRequest.PathType.LIBRARY_SCAN_ADD))
        }
        is Event.Settings.RemoveLibraryScanPath -> _state.update {
            it.copy(libraryScanPaths = it.libraryScanPaths - event.path)
        }
    }
}

private fun MainViewModel.onUpdateProxySetting() {
    _state.update {
        val newUrl = buildProxyUrl(it.proxyType, it.proxyHost, it.proxyPort, it.proxyUser, it.proxyPass) ?: ""
        it.copy(
            proxyUrl = newUrl,
            proxyStatus = ProxyStatus.UNVERIFIED,
            proxyVerificationMessage = null,
            ipInfoResult = null,
            ipCheckError = null
        )
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
            proxyType = defaultSettings.proxyType,
            proxyHost = defaultSettings.proxyHost,
            proxyPort = defaultSettings.proxyPort,
            proxyUser = defaultSettings.proxyUser,
            proxyPass = defaultSettings.proxyPass,
            debugLog = defaultSettings.debugLog,
            logAutoscrollEnabled = defaultSettings.logAutoscrollEnabled,
            zoomFactor = defaultSettings.zoomFactor,
            fontSizePreset = defaultSettings.fontSizePreset,
            offlineMode = defaultSettings.offlineMode,
            allowNsfw = defaultSettings.allowNsfw,
            proxyEnabledOnStartup = defaultSettings.proxyEnabledOnStartup
        )
    }
    Logger.logInfo("All settings restored to default values.")
}

/**
 * Enhanced IP address checking with proxy awareness and detailed logging.
 * This extension function provides better feedback about proxy functionality.
 */
internal fun MainViewModel.checkIpAddress() {
    viewModelScope.launch(Dispatchers.IO) {
        withContext(Dispatchers.Main) {
            _state.update { it.copy(isCheckingIp = true, ipInfoResult = null, ipCheckError = null) }
        }
        val s = state.value
        val url = buildProxyUrl(s.proxyType, s.proxyHost, s.proxyPort, s.proxyUser, s.proxyPass)

        // Define services to check which one is active
        val ipServices = mapOf(
            "ipinfo.io" to AppSettings.Defaults.IP_LOOKUP_URL,
            "ipify.org" to "https://api.ipify.org?format=json",
            "Custom" to s.customIpLookupUrl
        )
        val currentIpServiceName = ipServices.entries.find { it.value == s.ipLookupUrl }?.key ?: "Custom"

        val lookupUrl: String
        if (currentIpServiceName == "Custom" && s.ipLookupUrl.isBlank()) {
            val errorMsg = "Custom IP Lookup Service is selected, but the URL is empty."
            withContext(Dispatchers.Main) {
                _state.update { it.copy(isCheckingIp = false, ipCheckError = errorMsg) }
            }
            Logger.logError(errorMsg)
            return@launch
        } else {
            lookupUrl = s.ipLookupUrl.ifBlank { AppSettings.Defaults.IP_LOOKUP_URL }
        }


        Logger.logInfo("Checking public IP address using: $lookupUrl")
        if (url != null) {
            Logger.logInfo("Using proxy: $url")
        }

        val client = createHttpClient(url)
        try {
            val response = client.get(lookupUrl) {
                // Explicitly set a simple Accept header for JSON APIs
                accept(ContentType.Application.Json)
            }
            if (response.status.isSuccess()) {
                val ipInfo = response.body<com.mangacombiner.model.IpInfo>()
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(ipInfoResult = ipInfo, isNetworkBlocked = false) }
                }
                Logger.logInfo("IP Check Success via $lookupUrl: ${ipInfo.ip} (${ipInfo.city}, ${ipInfo.country})")

                // Additional logging to help debug proxy issues
                if (url != null) {
                    Logger.logInfo("This IP should be your proxy's IP, not your real IP")
                    Logger.logInfo("If this shows your real IP, the proxy is not working correctly")
                }
            } else {
                val responseBody = response.bodyAsText()
                val errorMsg = "Failed: Server responded with ${response.status}"
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(ipCheckError = errorMsg, isNetworkBlocked = it.proxyEnabledOnStartup) }
                }
                Logger.logError(errorMsg)
                Logger.logError("Server Response Body: $responseBody")
            }
        } catch (e: Exception) {
            val errorMsg = "Failed: ${e.message?.take(100) ?: "Unknown error"}"
            withContext(Dispatchers.Main) {
                _state.update { it.copy(ipCheckError = errorMsg, isNetworkBlocked = it.proxyEnabledOnStartup) }
            }
            Logger.logError("IP Check failed.", e)
            if (e is ClientRequestException) {
                val responseBody = e.response.bodyAsText()
                Logger.logError("Server Response Body: $responseBody")
            }
        } finally {
            client.close()
            withContext(Dispatchers.Main) {
                _state.update { it.copy(isCheckingIp = false) }
            }
        }
    }
}
