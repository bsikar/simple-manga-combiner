package com.mangacombiner.ui.widget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
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
        val isPaused = state.operationState == OperationState.PAUSED

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(elevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Download Options", style = MaterialTheme.typography.h6)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.seriesUrl,
                        onValueChange = { onEvent(MainViewModel.Event.UpdateUrl(it)) },
                        label = { Text("Series URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = isIdle
                    )

                    Button(
                        onClick = { onEvent(MainViewModel.Event.FetchChapters) },
                        enabled = state.seriesUrl.isNotBlank() && !state.isFetchingChapters && isIdle,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        if (state.isFetchingChapters) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Fetching...")
                        } else {
                            Text("Fetch Chapters")
                        }
                    }

                    OutlinedTextField(
                        value = state.customTitle,
                        onValueChange = { onEvent(MainViewModel.Event.UpdateCustomTitle(it)) },
                        label = { Text("Custom Title (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isIdle || isPaused
                    )

                    OutlinedTextField(
                        value = state.outputPath,
                        onValueChange = { onEvent(MainViewModel.Event.UpdateOutputPath(it)) },
                        label = { Text("Output Directory (Optional)") },
                        placeholder = { Text("Default: Same folder as the app") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isIdle || isPaused
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.workers.toString(),
                            onValueChange = { onEvent(MainViewModel.Event.UpdateWorkers(it.toIntOrNull() ?: 1)) },
                            label = { Text("Workers") },
                            modifier = Modifier.weight(1f),
                            enabled = isIdle
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { formatDropdownExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = isIdle || isPaused
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

                    FormControlLabel(
                        onClick = { onEvent(MainViewModel.Event.ToggleDebugLog(!state.debugLog)) },
                        enabled = isIdle,
                        control = {
                            Switch(
                                checked = state.debugLog,
                                onCheckedChange = { onEvent(MainViewModel.Event.ToggleDebugLog(it)) },
                                enabled = isIdle
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
                        enabled = isIdle,
                        control = {
                            Switch(
                                checked = state.perWorkerUserAgent,
                                onCheckedChange = { onEvent(MainViewModel.Event.TogglePerWorkerUserAgent(it)) },
                                enabled = isIdle
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
                                enabled = !state.perWorkerUserAgent && isIdle
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
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (state.operationState) {
                OperationState.IDLE -> {
                    Button(
                        onClick = { onEvent(MainViewModel.Event.StartOperation) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start Operation")
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
