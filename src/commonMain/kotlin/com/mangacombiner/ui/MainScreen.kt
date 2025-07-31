package com.mangacombiner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.Screen
import com.mangacombiner.ui.widget.*
import androidx.compose.material.icons.automirrored.filled.MenuBook

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val scaffoldState = rememberScaffoldState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Control the drawer's open/closed state from the ViewModel
    LaunchedEffect(state.showReaderToc) {
        if (state.showReaderToc) {
            drawerState.open()
        } else {
            drawerState.close()
        }
    }

    // Sync the drawer state back to the ViewModel if the user closes it via gesture
    LaunchedEffect(drawerState.isClosed) {
        if (drawerState.isClosed && state.showReaderToc) {
            viewModel.onEvent(Event.Library.ToggleToc)
        }
    }

    ModalDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen, // Allow swiping only when open
        drawerContent = {
            // The content of the drawer is the Table of Contents, shown only when a book is active.
            state.currentBook?.let { book ->
                TableOfContentsDrawer(
                    book = book,
                    currentChapterIndex = state.currentChapterIndex,
                    onEvent = viewModel::onEvent
                )
            }
        }
    ) {
        // The main application content goes here.
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
            Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background).padding(paddingValues)) {
                AnimatedVisibility(visible = state.isNetworkBlocked) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colors.error,
                        elevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.SignalWifiOff, "Network Blocked")
                            Text(
                                text = "Proxy connection failed. Network access is blocked. Please check your Proxy Settings.",
                                style = MaterialTheme.typography.body2,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxSize()) {
                    val showNavLabels = state.fontSizePreset !in listOf("Large", "X-Large", "XX-Large")
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        backgroundColor = MaterialTheme.colors.surface
                    ) {
                        PlatformTooltip("Search") {
                            NavigationRailItem(
                                selected = state.currentScreen == Screen.SEARCH,
                                onClick = { viewModel.onEvent(Event.Navigate(Screen.SEARCH)) },
                                icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                                label = if (showNavLabels) { { Text("Search") } } else null,
                                alwaysShowLabel = false
                            )
                        }
                        PlatformTooltip("Download") {
                            NavigationRailItem(
                                selected = state.currentScreen == Screen.DOWNLOAD,
                                onClick = { viewModel.onEvent(Event.Navigate(Screen.DOWNLOAD)) },
                                icon = { Icon(Icons.Default.Download, contentDescription = "Download") },
                                label = if (showNavLabels) { { Text("Download") } } else null,
                                alwaysShowLabel = false
                            )
                        }
                        PlatformTooltip("WebDAV") {
                            NavigationRailItem(
                                selected = state.currentScreen == Screen.WEB_DAV,
                                onClick = { viewModel.onEvent(Event.Navigate(Screen.WEB_DAV)) },
                                icon = { Icon(Icons.Default.CloudDownload, contentDescription = "WebDAV") },
                                label = if (showNavLabels) { { Text("WebDAV") } } else null,
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
                        PlatformTooltip("Cache") {
                            NavigationRailItem(
                                selected = state.currentScreen == Screen.CACHE_VIEWER,
                                onClick = {
                                    viewModel.onEvent(Event.Cache.RefreshView)
                                    viewModel.onEvent(Event.Navigate(Screen.CACHE_VIEWER))
                                },
                                icon = { Icon(Icons.Default.Storage, contentDescription = "Cache") },
                                label = if (showNavLabels) { { Text("Cache") } } else null,
                                alwaysShowLabel = false
                            )
                        }
                        PlatformTooltip("Library") {
                            NavigationRailItem(
                                selected = state.currentScreen == Screen.LIBRARY,
                                onClick = { viewModel.onEvent(Event.Navigate(Screen.LIBRARY)) },
                                icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Library") },
                                label = if (showNavLabels) { { Text("Library") } } else null,
                                alwaysShowLabel = false
                            )
                        }
                        PlatformTooltip("Settings") {
                            NavigationRailItem(
                                selected = state.currentScreen == Screen.SETTINGS || state.currentScreen == Screen.LOGS,
                                onClick = { viewModel.onEvent(Event.Navigate(Screen.SETTINGS)) },
                                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                label = if (showNavLabels) { { Text("Settings") } } else null,
                                alwaysShowLabel = false
                            )
                        }
                    }

                    Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

                    val padding = if (state.currentScreen == Screen.LIBRARY && state.currentBook != null) 0.dp else 16.dp
                    Box(modifier = Modifier.weight(1f).padding(padding)) {
                        when (state.currentScreen) {
                            Screen.SEARCH -> SearchScreen(state, viewModel::onEvent)
                            Screen.DOWNLOAD -> DownloadScreen(state, viewModel::onEvent)
                            Screen.WEB_DAV -> WebDavScreen(state, viewModel::onEvent)
                            Screen.DOWNLOAD_QUEUE -> DownloadQueueScreen(state, viewModel::onEvent)
                            Screen.LIBRARY -> LibraryScreen(state, viewModel::onEvent)
                            Screen.LOGS -> LogScreen(state, logs, viewModel::onEvent)
                            Screen.SETTINGS -> SettingsScreen(state, viewModel::onEvent)
                            Screen.CACHE_VIEWER -> CacheViewerScreen(state, viewModel::onEvent)
                        }
                    }
                }
            }

            // Dialogs remain at the end to overlay everything correctly.
            val editingJobId = state.editingJobId
            if (editingJobId != null) {
                val job = state.downloadQueue.find { it.id == editingJobId }
                val isEditable = job != null && job.status !in listOf("Completed", "Cancelled") && !job.status.startsWith("Error")

                if (isEditable) {
                    JobEditDialog(state, viewModel::onEvent)
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
            if (state.showNetworkErrorDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.onEvent(Event.Operation.DismissNetworkError) },
                    title = { Text("Network Error") },
                    text = { Text(state.networkErrorMessage ?: "Please check your network connection and try again.") },
                    confirmButton = {
                        Button(onClick = { viewModel.onEvent(Event.Operation.DismissNetworkError) }) {
                            Text("OK")
                        }
                    }
                )
            }
            if (state.showAddDuplicateDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.onEvent(Event.Queue.CancelAddDuplicate) },
                    title = { Text("Duplicate Job") },
                    text = { Text("This series is already in the download queue. Do you want to add it again?") },
                    confirmButton = {
                        Button(onClick = { viewModel.onEvent(Event.Queue.ConfirmAddDuplicate) }) {
                            Text("Add Anyway")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.onEvent(Event.Queue.CancelAddDuplicate) }) {
                            Text("Cancel")
                        }
                    }
                )
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
            if (state.showClearCacheDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.onEvent(Event.Cache.CancelClearAll) },
                    title = { Text("Confirm Clear All Cache") },
                    text = { Text("Are you sure you want to delete all temporary application data, including paused or incomplete downloads? This action cannot be undone.") },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.onEvent(Event.Cache.ConfirmClearAll) },
                            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                        ) {
                            Text("Clear Everything")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.onEvent(Event.Cache.CancelClearAll) }) {
                            Text("Cancel")
                        }
                    }
                )
            }
            if (state.showDeleteConfirmationDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.onEvent(Event.Library.CancelDeleteBook) },
                    title = { Text("Confirm Deletion") },
                    text = { Text("Are you sure you want to permanently delete this file? This action cannot be undone.") },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.onEvent(Event.Library.ConfirmDeleteBook) },
                            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                        ) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.onEvent(Event.Library.CancelDeleteBook) }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}
