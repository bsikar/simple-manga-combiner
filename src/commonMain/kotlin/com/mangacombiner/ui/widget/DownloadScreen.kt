package com.mangacombiner.ui.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.OperationState
import com.mangacombiner.ui.viewmodel.state.UiState

@Composable
fun DownloadScreen(state: UiState, onEvent: (Event) -> Unit) {
    val isIdle = state.operationState == OperationState.IDLE
    val isRunning = state.operationState == OperationState.RUNNING || state.operationState == OperationState.PAUSED

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(elevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.originalSearchResults.isNotEmpty()) {
                            PlatformTooltip("Back to Search Results") {
                                IconButton(onClick = { onEvent(Event.Download.BackToSearchResults) }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back to Search Results"
                                    )
                                }
                            }
                        }
                        Text("Download & Sync Options", style = MaterialTheme.typography.h6)
                    }
                    Spacer(Modifier.height(8.dp))

                    if (state.offlineMode) {
                        Text(
                            "Offline mode is enabled. Use 'Update Local File' to add or remove chapters from an existing EPUB.",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.primary
                        )
                    }

                    SubmitTextField(
                        value = state.seriesUrl,
                        onValueChange = { onEvent(Event.Download.UpdateUrl(it)) },
                        label = { Text("Series URL") },
                        onSubmit = { onEvent(Event.Download.FetchChapters) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isIdle && !state.offlineMode,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        trailingIcon = {
                            if (state.seriesUrl.isNotBlank()) {
                                IconButton(
                                    onClick = { onEvent(Event.Download.ClearInputs) },
                                    enabled = isIdle
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = "Clear URL and Filename"
                                    )
                                }
                            }
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onEvent(Event.Download.PickLocalFile) },
                            enabled = isIdle
                        ) {
                            Text("Update Local File...")
                        }

                        Button(
                            onClick = {
                                if (state.isFetchingChapters) {
                                    onEvent(Event.Download.CancelFetchChapters)
                                } else {
                                    onEvent(Event.Download.FetchChapters)
                                }
                            },
                            enabled = (state.seriesUrl.isNotBlank() && isIdle) || state.isFetchingChapters,
                            colors = if (state.isFetchingChapters) {
                                ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                            } else {
                                ButtonDefaults.buttonColors()
                            }
                        ) {
                            if (state.isFetchingChapters) {
                                Text("Cancel")
                            } else {
                                Text("Fetch Chapters")
                            }
                        }
                    }

                    if (state.isAnalyzingFile) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Analyzing file...")
                        }
                    }

                    Divider()

                    SubmitTextField(
                        value = state.customTitle,
                        onValueChange = { onEvent(Event.Download.UpdateCustomTitle(it)) },
                        label = { Text("Output Filename (without extension)") },
                        onSubmit = { onEvent(Event.Download.FetchChapters) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isIdle,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go)
                    )

                    SubmitTextField(
                        value = state.outputPath,
                        onValueChange = { onEvent(Event.Download.UpdateOutputPath(it)) },
                        label = { Text("Output Directory") },
                        onSubmit = { onEvent(Event.Download.FetchChapters) },
                        placeholder = { Text("Default: Your Downloads folder") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isIdle,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        trailingIcon = {
                            IconButton(
                                onClick = { onEvent(Event.Download.PickOutputPath) },
                                enabled = isIdle
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "Browse for output directory")
                            }
                        }
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Workers:", style = MaterialTheme.typography.body1)
                            NumberStepper(
                                value = state.workers,
                                onValueChange = { onEvent(Event.Settings.UpdateWorkers(it)) },
                                range = 1..16,
                                enabled = isIdle
                            )
                        }
                    }
                }
            }
        }

        if (isRunning && state.activeDownloadOptions != null) {
            val animatedProgress by animateFloatAsState(
                targetValue = state.progress,
                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LinearProgressIndicator(
                    progress = animatedProgress,
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )
                Text(
                    text = state.progressStatusText,
                    style = MaterialTheme.typography.caption
                )
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (state.operationState) {
                OperationState.IDLE -> {
                    val buttonText = if (state.sourceFilePath != null) "Sync & Update File" else "Add to Queue"
                    Button(
                        onClick = {
                            if (state.sourceFilePath != null) {
                                onEvent(Event.Operation.RequestStart)
                            } else {
                                onEvent(Event.Queue.Add)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.fetchedChapters.any { it.selectedSource != null }
                    ) {
                        Text(buttonText)
                    }
                }
                OperationState.RUNNING -> {
                    Button(
                        onClick = { onEvent(Event.Operation.Pause) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Pause")
                    }
                    Button(
                        onClick = { onEvent(Event.Operation.RequestCancel) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                }
                OperationState.PAUSED -> {
                    Button(
                        onClick = { onEvent(Event.Operation.Resume) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Resume")
                    }
                    Button(
                        onClick = { onEvent(Event.Operation.RequestCancel) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                }
                OperationState.CANCELLING -> {
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancelling...")
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    }
                }
            }
        }
    }
}
