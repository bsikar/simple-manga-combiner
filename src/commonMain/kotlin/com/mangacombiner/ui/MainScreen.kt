package com.mangacombiner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.NavigationRail
import androidx.compose.material.NavigationRailItem
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.Screen
import com.mangacombiner.ui.widget.CacheViewerScreen
import com.mangacombiner.ui.widget.ChapterSelectionDialog
import com.mangacombiner.ui.widget.DownloadScreen
import com.mangacombiner.ui.widget.FormControlLabel
import com.mangacombiner.ui.widget.PlatformTooltip
import com.mangacombiner.ui.widget.SettingsScreen
import com.mangacombiner.ui.widget.VerticalSplitter
import com.mangacombiner.ui.widget.rememberSplitterState

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val listState = rememberLazyListState()
    val splitterState = rememberSplitterState()

    LaunchedEffect(logs.size, state.logAutoscrollEnabled) {
        if (state.logAutoscrollEnabled && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        Row(modifier = Modifier.fillMaxSize()) {
            val showNavLabels = state.fontSizePreset != "Large"
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                backgroundColor = MaterialTheme.colors.surface
            ) {
                PlatformTooltip("Download") {
                    NavigationRailItem(
                        selected = state.currentScreen == Screen.DOWNLOAD,
                        onClick = { viewModel.onEvent(MainViewModel.Event.Navigate(Screen.DOWNLOAD)) },
                        icon = { Icon(Icons.Default.Download, contentDescription = "Download") },
                        label = if (showNavLabels) { { Text("Download") } } else null,
                        alwaysShowLabel = false
                    )
                }
                PlatformTooltip("Settings") {
                    NavigationRailItem(
                        selected = state.currentScreen == Screen.SETTINGS || state.currentScreen == Screen.CACHE_VIEWER,
                        onClick = { viewModel.onEvent(MainViewModel.Event.Navigate(Screen.SETTINGS)) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = if (showNavLabels) { { Text("Settings") } } else null,
                        alwaysShowLabel = false
                    )
                }
            }

            Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

            VerticalSplitter(splitterState = splitterState,
                topContent = {
                    Box(modifier = Modifier.padding(16.dp)) {
                        when (state.currentScreen) {
                            Screen.DOWNLOAD -> DownloadScreen(state, viewModel::onEvent)
                            Screen.SETTINGS -> SettingsScreen(state, viewModel::onEvent)
                            Screen.CACHE_VIEWER -> CacheViewerScreen(state, viewModel::onEvent)
                        }
                    }
                },
                bottomContent = {
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Logs", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.onBackground)
                            Spacer(Modifier.weight(1f))
                            PlatformTooltip(if (state.logAutoscrollEnabled) "Disable Auto-scroll" else "Enable Auto-scroll") {
                                IconButton(onClick = { viewModel.onEvent(MainViewModel.Event.ToggleLogAutoscroll) }) {
                                    Icon(
                                        imageVector = Icons.Default.VerticalAlignBottom,
                                        contentDescription = "Toggle Auto-scroll",
                                        tint = if (state.logAutoscrollEnabled) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(
                                            alpha = 0.6f
                                        )
                                    )
                                }
                            }
                            PlatformTooltip("Toggle Logs Panel") {
                                IconButton(onClick = { splitterState.toggle() }) {
                                    Icon(
                                        imageVector = if (splitterState.isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Toggle Logs Panel",
                                        tint = MaterialTheme.colors.onSurface
                                    )
                                }
                            }
                            PlatformTooltip("Reset Layout") {
                                IconButton(onClick = { splitterState.reset() }) {
                                    Icon(
                                        imageVector = Icons.Default.Restore,
                                        contentDescription = "Reset Layout",
                                        tint = MaterialTheme.colors.onSurface
                                    )
                                }
                            }
                            PlatformTooltip("Copy Logs") {
                                IconButton(onClick = { viewModel.onEvent(MainViewModel.Event.CopyLogsToClipboard) }) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy Logs",
                                        tint = MaterialTheme.colors.onSurface
                                    )
                                }
                            }
                            PlatformTooltip("Clear Logs") {
                                IconButton(onClick = { viewModel.onEvent(MainViewModel.Event.ClearLogs) }) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteForever,
                                        contentDescription = "Clear Logs",
                                        tint = MaterialTheme.colors.onSurface
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
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
            )
        }

        if (state.showChapterDialog) {
            ChapterSelectionDialog(state, viewModel::onEvent)
        }
        if (state.showCancelDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.onEvent(MainViewModel.Event.AbortCancelOperation) },
                title = { Text("Confirm Cancel") },
                text = {
                    Column {
                        Text("Are you sure you want to cancel the current operation?")
                        Spacer(Modifier.height(16.dp))
                        FormControlLabel(
                            onClick = { viewModel.onEvent(MainViewModel.Event.ToggleDeleteCacheOnCancel(!state.deleteCacheOnCancel)) },
                            control = { Checkbox(checked = state.deleteCacheOnCancel, onCheckedChange = null) },
                            label = { Text("Delete temporary files for this job") }
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.onEvent(MainViewModel.Event.ConfirmCancelOperation) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                    ) {
                        Text("Cancel Operation")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onEvent(MainViewModel.Event.AbortCancelOperation) }) {
                        Text("Don't Cancel")
                    }
                }
            )
        }
    }
}
