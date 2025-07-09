package com.mangacombiner.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
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
fun AdvancedSettingsScreen(state: UiState, onEvent: (MainViewModel.Event) -> Unit) {
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
                Text("Advanced Options", style = MaterialTheme.typography.h6)

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
}
