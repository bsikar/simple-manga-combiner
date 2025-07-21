package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangacombiner.service.WebDavFile
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.util.formatSize
import java.net.URI

@Composable
fun WebDavScreen(state: UiState, onEvent: (Event) -> Unit) {
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
                // Breadcrumb Navigation
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    val canNavigateBack = remember(state.webDavUrl) {
                        try {
                            (URI(state.webDavUrl).path?.trim('/') ?: "").isNotEmpty()
                        } catch (e: Exception) { false }
                    }
                    if (canNavigateBack) {
                        PlatformTooltip("Up one level") {
                            IconButton(
                                onClick = { onEvent(Event.WebDav.NavigateBack) },
                                enabled = !state.isConnectingToWebDav
                            ) { Icon(Icons.Default.ArrowUpward, "Navigate Up") }
                        }
                    }
                    val breadcrumb = remember(state.webDavUrl) {
                        try { URI(state.webDavUrl).path ?: "/" } catch (e: Exception) { "/" }
                    }
                    Text(
                        text = breadcrumb,
                        style = MaterialTheme.typography.body2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                LazyColumn(modifier = Modifier.fillMaxHeight()) {
                    items(state.webDavFiles, key = { it.href }) { file ->
                        WebDavFileItem(
                            file = file,
                            isSelected = file.href in state.webDavSelectedFiles,
                            onItemClick = {
                                if (file.isDirectory) {
                                    onEvent(Event.WebDav.NavigateTo(file))
                                } else {
                                    onEvent(Event.WebDav.ToggleFileSelection(file.href, file.href !in state.webDavSelectedFiles))
                                }
                            },
                            onCheckedChange = { isSelected ->
                                onEvent(Event.WebDav.ToggleFileSelection(file.href, isSelected))
                            },
                            enabled = !state.isDownloadingFromWebDav
                        )
                    }
                }
            }
        }

        // --- BOTTOM SECTION (Progress/Download Button) ---
        if (state.isDownloadingFromWebDav) {
            Column {
                LinearProgressIndicator(progress = state.webDavDownloadProgress, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(state.webDavStatus, style = MaterialTheme.typography.caption)
            }
        } else if (state.webDavFiles.isNotEmpty()) {
            Button(
                onClick = { onEvent(Event.WebDav.DownloadSelected) },
                enabled = state.webDavSelectedFiles.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Download Selected (${state.webDavSelectedFiles.size})")
            }
        }
    }
}

@Composable
private fun WebDavFileItem(
    file: WebDavFile,
    isSelected: Boolean,
    onItemClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    val isSelectable = enabled && !file.isDirectory
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onItemClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = onCheckedChange,
            enabled = isSelectable
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
            if (!file.isDirectory) {
                Text(
                    formatSize(file.size),
                    style = MaterialTheme.typography.caption,
                    color = if (enabled) LocalContentColor.current.copy(alpha = ContentAlpha.medium) else LocalContentColor.current.copy(alpha = ContentAlpha.disabled)
                )
            }
        }
    }
}
