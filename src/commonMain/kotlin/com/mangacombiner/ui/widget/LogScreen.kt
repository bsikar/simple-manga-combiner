package com.mangacombiner.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.Screen
import com.mangacombiner.ui.viewmodel.state.UiState

@Composable
fun LogScreen(
    state: UiState,
    logs: List<String>,
    onEvent: (Event) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size, state.logAutoscrollEnabled) {
        if (state.logAutoscrollEnabled && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = { onEvent(Event.Navigate(Screen.SETTINGS)) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Settings")
            }
            Text("Logs", style = MaterialTheme.typography.h5, modifier = Modifier.weight(1f))
            PlatformTooltip(if (state.logAutoscrollEnabled) "Disable Auto-scroll" else "Enable Auto-scroll") {
                IconButton(onClick = { onEvent(Event.Log.ToggleAutoscroll) }) {
                    Icon(
                        imageVector = Icons.Default.VerticalAlignBottom,
                        contentDescription = "Toggle Auto-scroll",
                        tint = if (state.logAutoscrollEnabled) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(
                            alpha = 0.6f
                        )
                    )
                }
            }
            PlatformTooltip("Copy Logs") {
                IconButton(onClick = { onEvent(Event.Log.CopyToClipboard) }) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy Logs",
                        tint = MaterialTheme.colors.onSurface
                    )
                }
            }
            PlatformTooltip("Clear Logs") {
                IconButton(onClick = { onEvent(Event.Log.Clear) }) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = "Clear Logs",
                        tint = MaterialTheme.colors.onSurface
                    )
                }
            }
        }
        FormControlLabel(
            onClick = { onEvent(Event.Settings.ToggleDebugLog(!state.debugLog)) },
            enabled = true,
            control = {
                Switch(
                    checked = state.debugLog,
                    onCheckedChange = { onEvent(Event.Settings.ToggleDebugLog(it)) },
                    enabled = true
                )
            },
            label = { Text("Enable Debug Logging") }
        )
        Spacer(Modifier.padding(4.dp))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = MaterialTheme.colors.surface,
                    shape = MaterialTheme.shapes.medium
                )
                .padding(8.dp)
        ) {
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(logs) { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.body2,
                            softWrap = true,
                            color = MaterialTheme.colors.onSurface
                        )
                    }
                }
            }
        }
    }
}
