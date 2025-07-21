package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mangacombiner.service.WebDavFile
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.util.formatSize

@Composable
fun WebDavScreen(state: UiState, onEvent: (Event) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                Button(
                    onClick = { onEvent(Event.WebDav.Connect) },
                    enabled = state.webDavUrl.isNotBlank() && !state.isConnectingToWebDav && !state.isDownloadingFromWebDav,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    if (state.isConnectingToWebDav) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Connect & List EPUBs")
                }
                state.webDavError?.let {
                    Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
                }
            }
        }

        AnimatedVisibility(visible = state.webDavFiles.isNotEmpty() || state.isDownloadingFromWebDav) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Available EPUBs (${state.webDavFiles.size})", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.webDavFiles, key = { it.fullPath }) { file ->
                        WebDavFileItem(
                            file = file,
                            isSelected = file.fullPath in state.webDavSelectedFiles,
                            onCheckedChange = { isSelected ->
                                onEvent(Event.WebDav.ToggleFileSelection(file.fullPath, isSelected))
                            },
                            enabled = !state.isDownloadingFromWebDav
                        )
                    }
                }
            }
        }

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
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = isSelected, onCheckedChange = onCheckedChange, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, fontWeight = FontWeight.Bold)
            Text(formatSize(file.size), style = MaterialTheme.typography.caption)
        }
    }
}
