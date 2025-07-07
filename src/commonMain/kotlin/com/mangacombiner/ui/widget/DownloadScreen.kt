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
import com.mangacombiner.ui.viewmodel.UiState
import com.mangacombiner.util.UserAgent

@Composable
fun DownloadScreen(state: UiState, onEvent: (MainViewModel.Event) -> Unit) {
    var formatDropdownExpanded by remember { mutableStateOf(false) }
    var browserDropdownExpanded by remember { mutableStateOf(false) }
    val browserImpersonationOptions = listOf("Random") + UserAgent.browsers.keys.toList()

    Column(modifier = Modifier.fillMaxSize()) {
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
                        singleLine = true
                    )

                    Button(
                        onClick = { onEvent(MainViewModel.Event.FetchChapters) },
                        enabled = state.seriesUrl.isNotBlank() && !state.isFetchingChapters,
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
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = state.outputPath,
                        onValueChange = { onEvent(MainViewModel.Event.UpdateOutputPath(it)) },
                        label = { Text("Output Directory (Optional)") },
                        placeholder = { Text("Default: Same folder as the app") },
                        modifier = Modifier.fillMaxWidth()
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
                            modifier = Modifier.weight(1f)
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { formatDropdownExpanded = true },
                                modifier = Modifier.fillMaxWidth()
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

                    val onToggleDebug: () -> Unit = { onEvent(MainViewModel.Event.ToggleDebugLog(!state.debugLog)) }
                    FormControlLabel(
                        onClick = onToggleDebug,
                        control = {
                            Switch(
                                checked = state.debugLog,
                                onCheckedChange = { onEvent(MainViewModel.Event.ToggleDebugLog(it)) }
                            )
                        },
                        label = { Text("Enable Debug Logging") }
                    )

                    val onToggleDryRun: () -> Unit = { onEvent(MainViewModel.Event.ToggleDryRun(!state.dryRun)) }
                    FormControlLabel(
                        onClick = onToggleDryRun,
                        control = {
                            Switch(
                                checked = state.dryRun,
                                onCheckedChange = { onEvent(MainViewModel.Event.ToggleDryRun(it)) }
                            )
                        },
                        label = { Text("Dry Run (Simulate Only)") }
                    )

                    val onTogglePerWorker: () -> Unit =
                        { onEvent(MainViewModel.Event.TogglePerWorkerUserAgent(!state.perWorkerUserAgent)) }
                    FormControlLabel(
                        onClick = onTogglePerWorker,
                        control = {
                            Switch(
                                checked = state.perWorkerUserAgent,
                                onCheckedChange = { onEvent(MainViewModel.Event.TogglePerWorkerUserAgent(it)) }
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
                                enabled = !state.perWorkerUserAgent
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
        PlatformTooltip("Start the download and combine process with the current settings") {
            Button(
                onClick = { onEvent(MainViewModel.Event.StartOperation) },
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(color = MaterialTheme.colors.onPrimary)
                } else {
                    Text("Start Operation")
                }
            }
        }
    }
}
