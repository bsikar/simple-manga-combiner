package com.mangacombiner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import com.mangacombiner.runner.MangaCombinerRunner
import com.mangacombiner.service.DownloadOptions
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.LocalFileOptions
import com.mangacombiner.service.ProcessorService
import com.mangacombiner.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import javax.swing.JFileChooser
import javax.swing.filechooser.FileSystemView

private data class GuiState(
    val source: String = "",
    val outputFormat: String = "cbz",
    val customTitle: String = "",
    val forceOverwrite: Boolean = false,
    val deleteOriginal: Boolean = false,
    val isProcessing: Boolean = false
)

@Composable
@Suppress("FunctionNaming")
fun MangaCombinerWindow(
    downloadService: DownloadService,
    processorService: ProcessorService,
    onClose: () -> Unit
) {
    Window(onCloseRequest = onClose, title = "Manga Combiner") {
        MaterialTheme {
            MainScreen(downloadService, processorService)
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun MainScreen(downloadService: DownloadService, processorService: ProcessorService) {
    var state by remember { mutableStateOf(GuiState()) }
    val logLines = remember { mutableStateOf(listOf("Welcome to Manga Combiner!")) }
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val listener: (String) -> Unit = { logLines.value = logLines.value + it }
        Logger.addListener(listener)
        onDispose { Logger.removeListener(listener) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        SourceInput(
            state = state,
            onStateChange = { state = it }
        )
        Spacer(Modifier.height(16.dp))
        OptionsRow(
            state = state,
            onStateChange = { state = it }
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                state = state.copy(isProcessing = true)
                coroutineScope.launch(Dispatchers.IO) {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        handleProcessing(state, downloadService, processorService)
                    } catch (e: IOException) {
                        Logger.logError("A file or network error occurred: ${e.message}", e)
                    } catch (e: Exception) {
                        Logger.logError("An unexpected error occurred in the GUI operation.", e)
                    } finally {
                        state = state.copy(isProcessing = false)
                    }
                }
            },
            enabled = state.source.isNotBlank() && !state.isProcessing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isProcessing) "Processing..." else "Start Processing")
        }
        Spacer(Modifier.height(16.dp))
        LogView(logLines.value)
    }
}

@Composable
@Suppress("FunctionNaming")
private fun SourceInput(state: GuiState, onStateChange: (GuiState) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.source,
            onValueChange = { onStateChange(state.copy(source = it)) },
            label = { Text("Source URL or File Path") },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            val chooser = JFileChooser(FileSystemView.getFileSystemView().homeDirectory).apply {
                dialogTitle = "Select a file or directory"
                fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                onStateChange(state.copy(source = chooser.selectedFile.absolutePath))
            }
        }) { Text("Browse") }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.customTitle,
        onValueChange = { onStateChange(state.copy(customTitle = it)) },
        label = { Text("Custom Title (Optional)") },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
@Suppress("FunctionNaming")
private fun OptionsRow(state: GuiState, onStateChange: (GuiState) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            Button(onClick = { expanded = true }) {
                Text("Format: ${state.outputFormat.uppercase()}")
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Format")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(onClick = {
                    onStateChange(state.copy(outputFormat = "cbz"))
                    expanded = false
                }) { Text("CBZ") }
                DropdownMenuItem(onClick = {
                    onStateChange(state.copy(outputFormat = "epub"))
                    expanded = false
                }) { Text("EPUB") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.forceOverwrite,
                onCheckedChange = { onStateChange(state.copy(forceOverwrite = it)) }
            )
            Text("Force Overwrite")
            Spacer(Modifier.width(16.dp))
            Checkbox(
                checked = state.deleteOriginal,
                onCheckedChange = { onStateChange(state.copy(deleteOriginal = it)) }
            )
            Text("Delete Original")
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun LogView(logs: List<String>) {
    val logListState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logListState.animateScrollToItem(logs.lastIndex)
        }
    }
    LazyColumn(
        state = logListState,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.onSurface.copy(alpha = 0.05f))
            .padding(8.dp)
    ) {
        items(logs) { line ->
            Text(line, style = MaterialTheme.typography.body2, softWrap = true)
        }
    }
}

private suspend fun handleProcessing(
    state: GuiState,
    downloadService: DownloadService,
    processorService: ProcessorService
) {
    val tempDir = File(System.getProperty("java.io.tmpdir"))
    val client = createKtorClient()
    try {
        when {
            state.source.startsWith("http", ignoreCase = true) -> {
                downloadService.downloadNewSeries(
                    DownloadOptions(
                        seriesUrl = state.source,
                        cliTitle = state.customTitle.ifBlank { null },
                        imageWorkers = MangaCombinerRunner.DEFAULT_IMAGE_WORKERS,
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
                        tempDirectory = tempDir
                    )
                )
            }
            else -> Logger.logError("Source is not a valid URL or existing file path.")
        }
    } finally {
        client.close()
    }
}

private fun createKtorClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) { requestTimeoutMillis = MangaCombinerRunner.REQUEST_TIMEOUT_MS }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = MangaCombinerRunner.MAX_RETRIES)
        exponentialDelay()
    }
    install(UserAgent) { agent = MangaCombinerRunner.BROWSER_USER_AGENTS.random() }
}
