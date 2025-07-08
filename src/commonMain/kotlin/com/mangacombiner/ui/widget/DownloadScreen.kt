package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.OperationState
import com.mangacombiner.ui.viewmodel.UiState
import com.mangacombiner.util.UserAgent

@Composable
fun DownloadScreen(state: UiState, onEvent: (MainViewModel.Event) -> Unit) {
    var formatDropdownExpanded by remember { mutableStateOf(false) }
    var browserDropdownExpanded by remember { mutableStateOf(false) }
    val browserImpersonationOptions = listOf("Random") + UserAgent.browsers.keys.toList()

    Column(modifier = Modifier.fillMaxSize()) {
        val isIdle = state.operationState == OperationState.IDLE
        val isRunning = state.operationState == OperationState.RUNNING || state.operationState == OperationState.PAUSED
        val isProcessing = state.operationState != OperationState.CANCELLING

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(elevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Download & Sync Options", style = MaterialTheme.typography.h6)
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = state.seriesUrl,
                        onValueChange = { onEvent(MainViewModel.Event.UpdateUrl(it)) },
                        label = { Text("Series URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = isIdle
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onEvent(MainViewModel.Event.PickLocalFile) },
                            enabled = isIdle
                        ) {
                            Text("Update Local File...")
                        }

                        Button(
                            onClick = { onEvent(MainViewModel.Event.FetchChapters) },
                            enabled = state.seriesUrl.isNotBlank() && !state.isFetchingChapters && isIdle,
                        ) {
                            if (state.isFetchingChapters) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Fetching...")
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

                    OutlinedTextField(
                        value = state.customTitle,
                        onValueChange = { onEvent(MainViewModel.Event.UpdateCustomTitle(it)) },
                        label = { Text("Output Filename (without extension)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isProcessing
                    )

                    OutlinedTextField(
                        value = state.outputPath,
                        onValueChange = { onEvent(MainViewModel.Event.UpdateOutputPath(it)) },
                        label = { Text("Output Directory") },
                        placeholder = { Text("Default: Your Downloads folder") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isProcessing
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text("Workers:", style = MaterialTheme.typography.body1)
                            NumberStepper(
                                value = state.workers,
                                onValueChange = { onEvent(MainViewModel.Event.UpdateWorkers(it)) },
                                range = 1..16,
                                enabled = isProcessing
                            )
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { formatDropdownExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = isProcessing
                            ) {
                                Text("Format: ${state.outputFormat.uppercase()}")
                                Icon(Icons.Default.ArrowDropDown, "Format")
                            }
                            DropdownMenu(
                                expanded = formatDropdownExpanded,
                                onDismissRequest = { formatDropdownExpanded = false }
                            ) {
                                DropdownMenuItem(onClick = {
                                    onEvent(MainViewModel.Event.UpdateFormat("cbz"))
                                    formatDropdownExpanded = false
                                }) { Text("CBZ") }
                                DropdownMenuItem(onClick = {
                                    onEvent(MainViewModel.Event.UpdateFormat("epub"))
                                    formatDropdownExpanded = false
                                }) { Text("EPUB") }
                            }
                        }
                    }
                }
            }

            Card(elevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Advanced Options", style = MaterialTheme.typography.h6)
                    Spacer(Modifier.height(16.dp))

                    AnimatedVisibility(visible = state.sourceFilePath != null) {
                        FormControlLabel(
                            onClick = { onEvent(MainViewModel.Event.ToggleReplaceOriginalFile(!state.replaceOriginalFile)) },
                            enabled = isProcessing,
                            control = {
                                Switch(
                                    checked = state.replaceOriginalFile,
                                    onCheckedChange = { onEvent(MainViewModel.Event.ToggleReplaceOriginalFile(it)) },
                                    enabled = isProcessing
                                )
                            },
                            label = { Text("Replace original file on update") }
                        )
                    }

                    FormControlLabel(
                        onClick = { onEvent(MainViewModel.Event.ToggleDebugLog(!state.debugLog)) },
                        enabled = true,
                        control = {
                            Switch(
                                checked = state.debugLog,
                                onCheckedChange = { onEvent(MainViewModel.Event.ToggleDebugLog(it)) },
                                enabled = true
                            )
                        },
                        label = { Text("Enable Debug Logging") }
                    )

                    FormControlLabel(
                        onClick = { onEvent(MainViewModel.Event.ToggleDryRun(!state.dryRun)) },
                        enabled = isIdle,
                        control = {
                            Switch(
                                checked = state.dryRun,
                                onCheckedChange = { onEvent(MainViewModel.Event.ToggleDryRun(it)) },
                                enabled = isIdle
                            )
                        },
                        label = { Text("Dry Run (Simulate Only)") }
                    )

                    FormControlLabel(
                        onClick = { onEvent(MainViewModel.Event.TogglePerWorkerUserAgent(!state.perWorkerUserAgent)) },
                        enabled = isProcessing,
                        control = {
                            Switch(
                                checked = state.perWorkerUserAgent,
                                onCheckedChange = { onEvent(MainViewModel.Event.TogglePerWorkerUserAgent(it)) },
                                enabled = isProcessing
                            )
                        },
                        label = { Text("Randomize browser per worker") }
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Impersonate Browser:", style = MaterialTheme.typography.body1, modifier = Modifier.weight(1f))
                        Box {
                            OutlinedButton(
                                onClick = { browserDropdownExpanded = true },
                                enabled = !state.perWorkerUserAgent && isProcessing
                            ) {
                                Text(state.userAgentName)
                                Icon(Icons.Default.ArrowDropDown, "Impersonate Browser")
                            }
                            DropdownMenu(
                                expanded = browserDropdownExpanded,
                                onDismissRequest = { browserDropdownExpanded = false },
                                modifier = Modifier.widthIn(max = 280.dp)
                            ) {
                                Box(modifier = Modifier.heightIn(max = 400.dp)) {
                                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                        browserImpersonationOptions.forEach { name ->
                                            DropdownMenuItem(onClick = {
                                                onEvent(MainViewModel.Event.UpdateUserAgent(name))
                                                browserDropdownExpanded = false
                                            }) { Text(name) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isRunning) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LinearProgressIndicator(
                    progress = state.progress,
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
                    val buttonText = if (state.sourceFilePath != null) "Sync & Update File" else "Start Download"
                    Button(
                        onClick = { onEvent(MainViewModel.Event.StartOperation) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.fetchedChapters.any { it.selectedSource != null }
                    ) {
                        Text(buttonText)
                    }
                }
                OperationState.RUNNING -> {
                    Button(
                        onClick = { onEvent(MainViewModel.Event.PauseOperation) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Pause")
                    }
                    Button(
                        onClick = { onEvent(MainViewModel.Event.RequestCancelOperation) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                }
                OperationState.PAUSED -> {
                    Button(
                        onClick = { onEvent(MainViewModel.Event.ResumeOperation) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Resume")
                    }
                    Button(
                        onClick = { onEvent(MainViewModel.Event.RequestCancelOperation) },
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
