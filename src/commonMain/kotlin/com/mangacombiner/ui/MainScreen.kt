package com.mangacombiner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.Screen
import com.mangacombiner.ui.widget.AboutDialog
import com.mangacombiner.ui.widget.AdvancedSettingsScreen
import com.mangacombiner.ui.widget.BrokenDownloadDialog
import com.mangacombiner.ui.widget.CacheViewerScreen
import com.mangacombiner.ui.widget.ChapterSelectionDialog
import com.mangacombiner.ui.widget.CompletionDialog
import com.mangacombiner.ui.widget.DownloadQueueScreen
import com.mangacombiner.ui.widget.DownloadScreen
import com.mangacombiner.ui.widget.FormControlLabel
import com.mangacombiner.ui.widget.LogScreen
import com.mangacombiner.ui.widget.PlatformTooltip
import com.mangacombiner.ui.widget.SettingsScreen

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val scaffoldState = rememberScaffoldState()

    LaunchedEffect(state.completionMessage) {
        state.completionMessage?.let {
            scaffoldState.snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        scaffoldState = scaffoldState,
        snackbarHost = {
            SnackbarHost(it) { data ->
                Snackbar(
                    snackbarData = data,
                    backgroundColor = MaterialTheme.colors.secondary,
                    contentColor = MaterialTheme.colors.onSecondary
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background).padding(paddingValues)) {
            Row(modifier = Modifier.fillMaxSize()) {
                val showNavLabels = state.fontSizePreset !in listOf("Large", "X-Large", "XX-Large")
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    backgroundColor = MaterialTheme.colors.surface
                ) {
                    PlatformTooltip("Download") {
                        NavigationRailItem(
                            selected = state.currentScreen == Screen.DOWNLOAD,
                            onClick = { viewModel.onEvent(Event.Navigate(Screen.DOWNLOAD)) },
                            icon = { Icon(Icons.Default.Download, contentDescription = "Download") },
                            label = if (showNavLabels) { { Text("Download") } } else null,
                            alwaysShowLabel = false
                        )
                    }
                    PlatformTooltip("Queue") {
                        NavigationRailItem(
                            selected = state.currentScreen == Screen.DOWNLOAD_QUEUE,
                            onClick = { viewModel.onEvent(Event.Navigate(Screen.DOWNLOAD_QUEUE)) },
                            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Download Queue") },
                            label = if (showNavLabels) { { Text("Queue") } } else null,
                            alwaysShowLabel = false
                        )
                    }
                    PlatformTooltip("Logs") {
                        NavigationRailItem(
                            selected = state.currentScreen == Screen.LOGS,
                            onClick = { viewModel.onEvent(Event.Navigate(Screen.LOGS)) },
                            icon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "Logs") },
                            label = if (showNavLabels) { { Text("Logs") } } else null,
                            alwaysShowLabel = false
                        )
                    }
                    PlatformTooltip("Advanced") {
                        NavigationRailItem(
                            selected = state.currentScreen == Screen.ADVANCED_SETTINGS,
                            onClick = { viewModel.onEvent(Event.Navigate(Screen.ADVANCED_SETTINGS)) },
                            icon = { Icon(Icons.Default.Tune, contentDescription = "Advanced Settings") },
                            label = if (showNavLabels) { { Text("Advanced") } } else null,
                            alwaysShowLabel = false
                        )
                    }
                    PlatformTooltip("Settings") {
                        NavigationRailItem(
                            selected = state.currentScreen == Screen.SETTINGS || state.currentScreen == Screen.CACHE_VIEWER,
                            onClick = { viewModel.onEvent(Event.Navigate(Screen.SETTINGS)) },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                            label = if (showNavLabels) { { Text("Settings") } } else null,
                            alwaysShowLabel = false
                        )
                    }
                }

                Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

                Box(modifier = Modifier.weight(1f).padding(16.dp)) {
                    when (state.currentScreen) {
                        Screen.DOWNLOAD -> DownloadScreen(state, viewModel::onEvent)
                        Screen.DOWNLOAD_QUEUE -> DownloadQueueScreen(state)
                        Screen.LOGS -> LogScreen(state, logs, viewModel::onEvent)
                        Screen.ADVANCED_SETTINGS -> AdvancedSettingsScreen(state, viewModel::onEvent)
                        Screen.SETTINGS -> SettingsScreen(state, viewModel::onEvent)
                        Screen.CACHE_VIEWER -> CacheViewerScreen(state, viewModel::onEvent)
                    }
                }
            }

            if (state.showChapterDialog) {
                ChapterSelectionDialog(state, viewModel::onEvent)
            }
            if (state.showAboutDialog) {
                AboutDialog(onDismissRequest = { viewModel.onEvent(Event.ToggleAboutDialog(false)) })
            }
            if (state.showBrokenDownloadDialog) {
                BrokenDownloadDialog(state, viewModel::onEvent)
            }
            if (state.showCompletionDialog) {
                CompletionDialog(state, viewModel::onEvent)
            }
            if (state.showCancelDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.onEvent(Event.Operation.AbortCancel) },
                    title = { Text("Confirm Cancel") },
                    text = {
                        Column {
                            Text("Are you sure you want to cancel the current operation?")
                            FormControlLabel(
                                onClick = { viewModel.onEvent(Event.Operation.ToggleDeleteCacheOnCancel(!state.deleteCacheOnCancel)) },
                                control = { Checkbox(checked = state.deleteCacheOnCancel, onCheckedChange = null) },
                                label = { Text("Delete temporary files for this job") }
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.onEvent(Event.Operation.ConfirmCancel) },
                            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                        ) {
                            Text("Cancel Operation")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.onEvent(Event.Operation.AbortCancel) }) {
                            Text("Don't Cancel")
                        }
                    }
                )
            }
            if (state.showOverwriteConfirmationDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.onEvent(Event.Operation.CancelOverwrite) },
                    title = { Text("Confirm Overwrite") },
                    text = { Text("An existing file will be overwritten with the new chapter selections. This action cannot be undone. Are you sure you want to proceed?") },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.onEvent(Event.Operation.ConfirmOverwrite) },
                            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                        ) {
                            Text("Overwrite")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.onEvent(Event.Operation.CancelOverwrite) }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}
