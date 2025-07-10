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
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.OperationState
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.util.UserAgent

@Composable
fun AdvancedSettingsScreen(state: UiState, onEvent: (Event) -> Unit) {
    var browserDropdownExpanded by remember { mutableStateOf(false) }
    val browserImpersonationOptions = listOf("Random") + UserAgent.browsers.keys.toList()
    val isIdle = state.operationState == OperationState.IDLE
    val isProcessing = state.operationState != OperationState.CANCELLING

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(elevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Queue Settings", style = MaterialTheme.typography.h6)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Concurrent Series Downloads:",
                        style = MaterialTheme.typography.body1,
                        modifier = Modifier.weight(1f)
                    )
                    NumberStepper(
                        value = state.batchWorkers,
                        onValueChange = { onEvent(Event.Settings.UpdateBatchWorkers(it)) },
                        range = 1..8,
                        enabled = true
                    )
                }

                Divider()

                Text("Download Settings", style = MaterialTheme.typography.h6)

                FormControlLabel(
                    onClick = { onEvent(Event.Download.ToggleDryRun(!state.dryRun)) },
                    enabled = isIdle,
                    control = {
                        Switch(
                            checked = state.dryRun,
                            onCheckedChange = { onEvent(Event.Download.ToggleDryRun(it)) },
                            enabled = isIdle
                        )
                    },
                    label = { Text("Dry Run (Simulate Only)") }
                )

                FormControlLabel(
                    onClick = { onEvent(Event.Settings.TogglePerWorkerUserAgent(!state.perWorkerUserAgent)) },
                    enabled = isProcessing,
                    control = {
                        Switch(
                            checked = state.perWorkerUserAgent,
                            onCheckedChange = { onEvent(Event.Settings.TogglePerWorkerUserAgent(it)) },
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
                    Text(
                        "Impersonate Browser:",
                        style = MaterialTheme.typography.body1,
                        modifier = Modifier.weight(1f)
                    )
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
                                            onEvent(Event.Settings.UpdateUserAgent(name))
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
}
