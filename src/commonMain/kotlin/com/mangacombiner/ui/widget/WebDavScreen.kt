package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangacombiner.service.WebDavFile
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.CacheSortState
import com.mangacombiner.ui.viewmodel.state.SortCriteria
import com.mangacombiner.ui.viewmodel.state.SortDirection
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.util.formatSize
import com.mangacombiner.util.pointer.tooltipHoverFix
import java.net.URI

private fun getFilteredAndSortedFiles(
    files: List<WebDavFile>,
    filterQuery: String,
    sortState: CacheSortState
): List<WebDavFile> {
    val filtered = if (filterQuery.isBlank()) {
        files
    } else {
        files.filter { it.name.contains(filterQuery, ignoreCase = true) }
    }

    return filtered.sortedWith(Comparator { a, b ->
        // Primary sort: directories first
        val typeCompare = b.isDirectory.compareTo(a.isDirectory)
        if (typeCompare != 0) return@Comparator typeCompare

        // Secondary sort: based on state criteria
        val criteriaCompare = when (sortState.criteria) {
            SortCriteria.NAME -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name)
            SortCriteria.SIZE -> a.size.compareTo(b.size)
        }

        // Tertiary sort: apply direction
        if (sortState.direction == SortDirection.ASC) {
            criteriaCompare
        } else {
            -criteriaCompare
        }
    })
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WebDavScreen(state: UiState, onEvent: (Event) -> Unit) {
    val filteredAndSortedFiles = remember(state.webDavFiles, state.webDavFilterQuery, state.webDavSortState) {
        getFilteredAndSortedFiles(state.webDavFiles, state.webDavFilterQuery, state.webDavSortState)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- TOP SECTION ---
        Text("WebDAV Downloader", style = MaterialTheme.typography.h5)

        Card(elevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.webDavUrl,
                    onValueChange = { onEvent(Event.WebDav.UpdateUrl(it)) },
                    label = { Text("WebDAV URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !state.isConnectingToWebDav && !state.isDownloadingFromWebDav,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.webDavUser,
                        onValueChange = { onEvent(Event.WebDav.UpdateUser(it)) },
                        label = { Text("Username (Optional)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !state.isConnectingToWebDav && !state.isDownloadingFromWebDav,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                    )
                    OutlinedTextField(
                        value = state.webDavPass,
                        onValueChange = { onEvent(Event.WebDav.UpdatePass(it)) },
                        label = { Text("Password (Optional)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !state.isConnectingToWebDav && !state.isDownloadingFromWebDav,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
                    )
                }
                FormControlLabel(
                    control = {
                        Switch(
                            checked = state.webDavIncludeHidden,
                            onCheckedChange = { onEvent(Event.WebDav.ToggleIncludeHidden(it)) },
                            enabled = !state.isConnectingToWebDav && !state.isDownloadingFromWebDav
                        )
                    },
                    label = { Text("Search hidden directories/files") },
                    onClick = { onEvent(Event.WebDav.ToggleIncludeHidden(!state.webDavIncludeHidden)) },
                    enabled = !state.isConnectingToWebDav && !state.isDownloadingFromWebDav
                )
                Button(
                    onClick = { onEvent(Event.WebDav.Connect) },
                    enabled = state.webDavUrl.isNotBlank() && !state.isConnectingToWebDav && !state.isDownloadingFromWebDav,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    if (state.isConnectingToWebDav) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Connect & List Files")
                }
                state.webDavError?.let {
                    Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
                }
            }
        }

        // --- MIDDLE SECTION (File List) ---
        AnimatedVisibility(visible = state.webDavFiles.isNotEmpty() || state.isConnectingToWebDav, modifier = Modifier.weight(1f)) {
            Column {
                WebDavToolbar(state, onEvent)
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxHeight()) {
                    items(filteredAndSortedFiles, key = { it.href }) { file ->
                        WebDavFileItem(
                            file = file,
                            isSelected = file.href in state.webDavSelectedFiles,
                            folderSize = state.webDavFolderSizes[file.href],
                            onItemClick = {
                                if (file.isDirectory) onEvent(Event.WebDav.NavigateTo(file))
                                else onEvent(Event.WebDav.ToggleFileSelection(file.href, file.href !in state.webDavSelectedFiles))
                            },
                            onCheckedChange = { isSelected -> onEvent(Event.WebDav.ToggleFileSelection(file.href, isSelected)) },
                            enabled = !state.isDownloadingFromWebDav
                        )
                    }
                }
            }
        }

        // --- BOTTOM SECTION ---
        val selectedItems = remember(state.webDavSelectedFiles, state.webDavFileCache) {
            state.webDavFileCache.values.filter { it.href in state.webDavSelectedFiles }
        }
        val totalSize = remember(selectedItems, state.webDavFolderSizes) {
            selectedItems.sumOf { item ->
                if (item.isDirectory) {
                    state.webDavFolderSizes[item.href]?.takeIf { it >= 0 } ?: 0L
                } else {
                    item.size
                }
            }
        }

        if (state.isDownloadingFromWebDav) {
            Column {
                LinearProgressIndicator(progress = state.webDavDownloadProgress, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.webDavStatus, style = MaterialTheme.typography.caption, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Button(onClick = { onEvent(Event.WebDav.CancelDownload) }) { Text("Cancel") }
                }
            }
        } else if (state.webDavFiles.isNotEmpty()) {
            Button(
                onClick = { onEvent(Event.WebDav.DownloadSelected) },
                enabled = state.webDavSelectedFiles.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Download Selected (${selectedItems.size}) - ${formatSize(totalSize)}")
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WebDavToolbar(state: UiState, onEvent: (Event) -> Unit) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val canNavigateBack = remember(state.webDavUrl) {
                    try { (URI(state.webDavUrl).path?.trim('/') ?: "").isNotEmpty() } catch (e: Exception) { false }
                }
                if (canNavigateBack) {
                    PlatformTooltip("Up one level") {
                        IconButton(onClick = { onEvent(Event.WebDav.NavigateBack) }, enabled = !state.isConnectingToWebDav) {
                            Icon(Icons.Default.ArrowUpward, "Navigate Up")
                        }
                    }
                }
                val breadcrumb = remember(state.webDavUrl) { try { URI(state.webDavUrl).path ?: "/" } catch (e: Exception) { "/" } }
                Text(text = breadcrumb, style = MaterialTheme.typography.body2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(
                value = state.webDavFilterQuery,
                onValueChange = { onEvent(Event.WebDav.UpdateFilterQuery(it)) },
                label = { Text("Filter current view") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, "Filter") },
                trailingIcon = {
                    if (state.webDavFilterQuery.isNotEmpty()) {
                        IconButton(onClick = { onEvent(Event.WebDav.UpdateFilterQuery("")) }) {
                            Icon(Icons.Default.Clear, "Clear filter")
                        }
                    }
                }
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onEvent(Event.WebDav.SelectAll) }) { Text("Select All") }
                TextButton(onClick = { onEvent(Event.WebDav.DeselectAll) }) { Text("Deselect All") }
                Box(modifier = Modifier.tooltipHoverFix()) {
                    OutlinedButton(onClick = { sortMenuExpanded = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, "Sort", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sort By")
                    }
                    DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                        DropdownMenuItem(onClick = { onEvent(Event.WebDav.SetSort(CacheSortState(SortCriteria.NAME, SortDirection.ASC))); sortMenuExpanded = false }) { Text("Name (A-Z)") }
                        DropdownMenuItem(onClick = { onEvent(Event.WebDav.SetSort(CacheSortState(SortCriteria.NAME, SortDirection.DESC))); sortMenuExpanded = false }) { Text("Name (Z-A)") }
                        DropdownMenuItem(onClick = { onEvent(Event.WebDav.SetSort(CacheSortState(SortCriteria.SIZE, SortDirection.ASC))); sortMenuExpanded = false }) { Text("Size (Smallest)") }
                        DropdownMenuItem(onClick = { onEvent(Event.WebDav.SetSort(CacheSortState(SortCriteria.SIZE, SortDirection.DESC))); sortMenuExpanded = false }) { Text("Size (Largest)") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebDavFileItem(
    file: WebDavFile,
    isSelected: Boolean,
    folderSize: Long?,
    onItemClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onItemClick, enabled = enabled)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
        Icon(
            imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = if (file.isDirectory) "Directory" else "File",
            tint = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = ContentAlpha.disabled),
            modifier = Modifier.padding(end = 8.dp).size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                fontWeight = FontWeight.Bold,
                color = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = ContentAlpha.disabled)
            )

            val captionText = if (file.isDirectory) {
                if (isSelected) when (folderSize) {
                    null -> "(Calculating...)"
                    -1L -> "(Error calculating size)"
                    else -> "(${formatSize(folderSize)})"
                } else ""
            } else {
                formatSize(file.size)
            }

            if(captionText.isNotEmpty()){
                Text(
                    text = captionText,
                    style = MaterialTheme.typography.caption,
                    color = if (enabled) LocalContentColor.current.copy(alpha = ContentAlpha.medium) else LocalContentColor.current.copy(alpha = ContentAlpha.disabled)
                )
            }
        }
    }
}
