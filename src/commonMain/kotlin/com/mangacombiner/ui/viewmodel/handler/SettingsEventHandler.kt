package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.model.AppSettings
import com.mangacombiner.model.ProxyType
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.FilePickerRequest
import com.mangacombiner.ui.viewmodel.state.ProxyStatus
import com.mangacombiner.util.Logger
import com.mangacombiner.util.ProxyTestUtility
import com.mangacombiner.util.createHttpClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
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
        is Event.Settings.ToggleProxyOnStartup -> _state.update { it.copy(proxyEnabledOnStartup = event.isEnabled) }
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
        is Event.Settings.VerifyProxy -> verifyProxyConnection()
        is Event.Settings.CheckIpAddress -> checkIpAddress()
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
            proxyEnabledOnStartup = defaultSettings.proxyEnabledOnStartup
        )
    }
    Logger.logInfo("All settings restored to default values.")
}

/**
 * Enhanced proxy verification that tests both basic connectivity and kill switch behavior.
 * This extension function provides comprehensive proxy testing using ProxyTestUtility.
 */
internal fun MainViewModel.verifyProxyConnection() {
    viewModelScope.launch {
        _state.update {
            it.copy(
                proxyStatus = ProxyStatus.VERIFYING,
                proxyVerificationMessage = "Running comprehensive proxy test...",
                ipInfoResult = null,
                ipCheckError = null,
                isNetworkBlocked = true // Block network while verifying
            )
        }

        try {
            val s = state.value
            val url = buildProxyUrl(s.proxyType, s.proxyHost, s.proxyPort, s.proxyUser, s.proxyPass)

            if (url == null) {
                _state.update {
                    it.copy(
                        proxyStatus = ProxyStatus.UNVERIFIED,
                        proxyVerificationMessage = "No proxy configured.",
                        isNetworkBlocked = false // Unblock if no proxy is set
                    )
                }
                return@launch
            }

            Logger.logInfo("Starting comprehensive proxy verification for: $url")
            val testResult = ProxyTestUtility.runComprehensiveProxyTest(url)

            if (testResult.success) {
                val message = buildString {
                    append("✓ Proxy working correctly")
                    if (testResult.ipChanged) {
                        append("\n✓ IP changed: ${testResult.directIp} → ${testResult.proxyIp}")
                    }
                    if (testResult.killSwitchWorking) {
                        append("\n✓ Kill switch active")
                    }
                    testResult.proxyLocation?.let {
                        append("\n📍 Location: $it")
                    }
                }

                _state.update {
                    it.copy(
                        proxyStatus = ProxyStatus.CONNECTED,
                        proxyVerificationMessage = message,
                        ipInfoResult = com.mangacombiner.model.IpInfo(
                            ip = testResult.proxyIp,
                            city = testResult.proxyLocation?.split(", ")?.get(0),
                            country = testResult.proxyLocation?.split(", ")?.get(1)
                        ),
                        isNetworkBlocked = false // Unblock on success
                    )
                }
            } else {
                val message = buildString {
                    append("✗ Proxy test failed")
                    testResult.error?.let { append(": $it") }
                    if (!testResult.ipChanged && testResult.directIp != null && testResult.proxyIp != null) {
                        append("\n⚠️ IP unchanged - proxy may not be working")
                    }
                    if (!testResult.killSwitchWorking) {
                        append("\n⚠️ Kill switch not working - traffic may leak!")
                    }
                }

                _state.update {
                    it.copy(
                        proxyStatus = ProxyStatus.FAILED,
                        proxyVerificationMessage = message,
                        isNetworkBlocked = true // Keep blocked on failure
                    )
                }
            }
        } catch (e: Exception) {
            val message = "Test failed: ${e.message?.take(100) ?: "Unknown error"}"
            _state.update {
                it.copy(
                    proxyStatus = ProxyStatus.FAILED,
                    proxyVerificationMessage = message,
                    isNetworkBlocked = true // Keep blocked on failure
                )
            }
            Logger.logError("Comprehensive proxy verification failed", e)
        } finally {
            // This is the crucial fix: always clear the "initial check" flag when done.
            _state.update { it.copy(isInitialProxyCheckRunning = false) }
        }
    }
}

/**
 * Enhanced IP address checking with proxy awareness and detailed logging.
 * This extension function provides better feedback about proxy functionality.
 */
internal fun MainViewModel.checkIpAddress() {
    viewModelScope.launch {
        _state.update { it.copy(isCheckingIp = true, ipInfoResult = null, ipCheckError = null) }
        val s = state.value
        val url = buildProxyUrl(s.proxyType, s.proxyHost, s.proxyPort, s.proxyUser, s.proxyPass)

        Logger.logInfo("Checking public IP address...")
        if (url != null) {
            Logger.logInfo("Using proxy: $url")
        }

        val client = createHttpClient(url)
        try {
            val response = client.get("https://ipinfo.io/json")
            if (response.status.isSuccess()) {
                val ipInfo = response.body<com.mangacombiner.model.IpInfo>()
                _state.update { it.copy(ipInfoResult = ipInfo, isNetworkBlocked = false) }
                Logger.logInfo("IP Check Success: ${ipInfo.ip} (${ipInfo.city}, ${ipInfo.country})")

                // Additional logging to help debug proxy issues
                if (url != null) {
                    Logger.logInfo("This IP should be your proxy's IP, not your real IP")
                    Logger.logInfo("If this shows your real IP, the proxy is not working correctly")
                }
            } else {
                val errorMsg = "Failed: Server responded with ${response.status}"
                _state.update { it.copy(ipCheckError = errorMsg, isNetworkBlocked = it.proxyEnabledOnStartup) }
                Logger.logError(errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = "Failed: ${e.message?.take(100) ?: "Unknown error"}"
            _state.update { it.copy(ipCheckError = errorMsg, isNetworkBlocked = it.proxyEnabledOnStartup) }
            Logger.logError("IP Check failed.", e)
        } finally {
            client.close()
            _state.update { it.copy(isCheckingIp = false) }
        }
    }
}
