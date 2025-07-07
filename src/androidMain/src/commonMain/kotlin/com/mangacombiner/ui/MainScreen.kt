package com.mangacombiner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.Screen
import com.mangacombiner.ui.widget.CacheViewerScreen
import com.mangacombiner.ui.widget.ChapterSelectionDialog
import com.mangacombiner.ui.widget.DownloadScreen
import com.mangacombiner.ui.widget.PlatformTooltip
import com.mangacombiner.ui.widget.SettingsScreen
import com.mangacombiner.ui.widget.SyncScreen
import com.mangacombiner.ui.widget.VerticalSplitter
import com.mangacombiner.ui.widget.rememberSplitterState

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val listState = rememberLazyListState()
    val splitterState = rememberSplitterState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                backgroundColor = MaterialTheme.colors.surface
            ) {
                PlatformTooltip("Download") {
                    NavigationRailItem(
                        selected = state.currentScreen == Screen.DOWNLOAD,
                        onClick = { viewModel.onEvent(MainViewModel.Event.Navigate(Screen.DOWNLOAD)) },
                        icon = { Icon(Icons.Default.Download, contentDescription = "Download") },
                        label = { Text("Download") },
                        alwaysShowLabel = false
                    )
                }
                PlatformTooltip("Settings") {
                    NavigationRailItem(
                        selected = state.currentScreen == Screen.SETTINGS || state.currentScreen == Screen.CACHE_VIEWER,
                        onClick = { viewModel.onEvent(MainViewModel.Event.Navigate(Screen.SETTINGS)) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
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
                            Text("Logs", style = MaterialTheme.typography.h6)
                            Spacer(Modifier.weight(1f))
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
                onDismissRequest = { onEvent(MainViewModel.Event.AbortCancelOperation) },
                title = { Text("Confirm Cancel") },
                text = { Text("Are you sure you want to cancel the current operation? All temporary files for this job will be deleted.") },
                confirmButton = {
                    Button(
                        onClick = { onEvent(MainViewModel.Event.ConfirmCancelOperation) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                    ) {
                        Text("Cancel Operation")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onEvent(MainViewModel.Event.AbortCancelOperation) }) {
                        Text("Don't Cancel")
                    }
                }
            )
        }
    }
}
