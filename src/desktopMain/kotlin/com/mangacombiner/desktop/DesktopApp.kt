package com.mangacombiner.desktop

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.mangacombiner.di.appModule
import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.LocalFileOptions
import com.mangacombiner.service.ProcessorService
import com.mangacombiner.ui.MainScreen
import com.mangacombiner.util.Logger
import com.mangacombiner.util.createHttpClient
import com.mangacombiner.util.getTmpDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get
import java.io.File

fun main() {
    startKoin {
        modules(appModule)
    }

    val downloadService: DownloadService = get(DownloadService::class.java)
    val processorService: ProcessorService = get(ProcessorService::class.java)
    val scope = CoroutineScope(Dispatchers.IO)

    application {
        Window(onCloseRequest = ::exitApplication, title = "Manga Combiner") {
            MaterialTheme {
                MainScreen(
                    onProcessClick = { state ->
                        scope.launch {
                            try {
                                val client = createHttpClient()
                                val tempDir = File(getTmpDir())
                                when {
                                    state.source.startsWith("http", ignoreCase = true) -> {
                                        downloadService.downloadNewSeries(
                                            DownloadOptions(
                                                seriesUrl = state.source,
                                                cliTitle = state.customTitle.ifBlank { null },
                                                imageWorkers = 2, // Default value
                                                exclude = emptyList(),
                                                format = state.outputFormat,
                                                tempDir = tempDir,
                                                client = client
                                            )
                                        )
                                    }
                                    File(state.source).exists() -> {
                                        processorService.processLocalFile(
                                            LocalFileOptions(
                                                inputFile = File(state.source),
                                                customTitle = state.customTitle.ifBlank { null },
                                                outputFormat = state.outputFormat,
                                                forceOverwrite = state.forceOverwrite,
                                                deleteOriginal = state.deleteOriginal,
                                                useStreamingConversion = false,
                                                useTrueStreaming = false,
                                                useTrueDangerousMode = false,
                                                skipIfTargetExists = !state.forceOverwrite,
                                                tempDirectory = getTmpDir()
                                            )
                                        )
                                    }
                                    else -> Logger.logError("Source is not a valid URL or existing file path.")
                                }
                                client.close()
                            } catch (e: Exception) {
                                Logger.logError("An unexpected error occurred in the GUI operation.", e)
                            }
                        }
                    }
                )
            }
        }
    }
}
