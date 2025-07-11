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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.Screen
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.util.AppVersion
import com.mangacombiner.util.titlecase

@Composable
fun SettingsScreen(state: UiState, onEvent: (Event) -> Unit) {
    var themeDropdownExpanded by remember { mutableStateOf(false) }
    var outputDropdownExpanded by remember { mutableStateOf(false) }
    var fontSizeDropdownExpanded by remember { mutableStateOf(false) }
    val outputLocations = listOf("Downloads", "Documents", "Desktop", "Custom").filter {
        !(it == "Desktop" && System.getProperty("java.runtime.name")?.contains("Android") == true)
    }
    val themeOptions = remember { AppTheme.values().toList() }
    val fontPresets = listOf("XX-Small", "X-Small", "Small", "Medium", "Large", "X-Large", "XX-Large")

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(elevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Appearance", style = MaterialTheme.typography.h6)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Theme:", style = MaterialTheme.typography.body1)
                    Box {
                        OutlinedButton(onClick = { themeDropdownExpanded = true }) {
                            Text(state.theme.name.lowercase().replace('_', ' ').titlecase())
                            Icon(Icons.Default.ArrowDropDown, "Theme")
                        }
                        DropdownMenu(
                            expanded = themeDropdownExpanded,
                            onDismissRequest = { themeDropdownExpanded = false }
                        ) {
                            themeOptions.forEach { theme ->
                                DropdownMenuItem(onClick = {
                                    onEvent(Event.Settings.UpdateTheme(theme))
                                    themeDropdownExpanded = false
                                }) { Text(theme.name.lowercase().replace('_', ' ').titlecase()) }
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Font Size:", style = MaterialTheme.typography.body1)
                    Box {
                        OutlinedButton(onClick = { fontSizeDropdownExpanded = true }) {
                            Text(state.fontSizePreset)
                            Icon(Icons.Default.ArrowDropDown, "Font Size")
                        }
                        DropdownMenu(
                            expanded = fontSizeDropdownExpanded,
                            onDismissRequest = { fontSizeDropdownExpanded = false }
                        ) {
                            fontPresets.forEach { preset ->
                                DropdownMenuItem(onClick = {
                                    onEvent(Event.Settings.UpdateFontSizePreset(preset))
                                    fontSizeDropdownExpanded = false
                                }) {
                                    Text(preset)
                                }
                            }
                        }
                    }
                }
            }
        }

        Card(elevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Functionality", style = MaterialTheme.typography.h6)

                FormControlLabel(
                    onClick = { onEvent(Event.Settings.ToggleOfflineMode(!state.isOfflineMode)) },
                    control = { Switch(checked = state.isOfflineMode, onCheckedChange = null) },
                    label = { Text("Offline Mode") }
                )
                Text(
                    "Enable to modify local files, such as removing chapters from a CBZ or EPUB, without needing an internet connection.",
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }

        Card(elevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Output Settings", style = MaterialTheme.typography.h6)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Default Location:", style = MaterialTheme.typography.body1)
                    Box {
                        OutlinedButton(onClick = { outputDropdownExpanded = true }) {
                            Text(state.defaultOutputLocation)
                            Icon(Icons.Default.ArrowDropDown, "Default Location")
                        }
                        DropdownMenu(
                            expanded = outputDropdownExpanded,
                            onDismissRequest = { outputDropdownExpanded = false }
                        ) {
                            outputLocations.forEach { location ->
                                DropdownMenuItem(onClick = {
                                    onEvent(Event.Settings.UpdateDefaultOutputLocation(location))
                                    outputDropdownExpanded = false
                                }) { Text(location) }
                            }
                        }
                    }
                }

                if (state.defaultOutputLocation == "Custom") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Path: ${state.customDefaultOutputPath}",
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Button(onClick = { onEvent(Event.Settings.PickCustomDefaultPath) }) {
                            Text("Browse...")
                        }
                    }
                }
            }
        }

        Card(elevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Network Settings", style = MaterialTheme.typography.h6)
                OutlinedTextField(
                    value = state.proxyUrl,
                    onValueChange = { onEvent(Event.Settings.UpdateProxyUrl(it)) },
                    label = { Text("Proxy URL (Optional)") },
                    placeholder = { Text("http(s)://[user:pass@]host:port") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Card(elevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Diagnostics", style = MaterialTheme.typography.h6)
                Text("Cache Location", style = MaterialTheme.typography.subtitle2)
                Text(
                    text = "Path: ${state.cachePath}",
                    style = MaterialTheme.typography.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onEvent(Event.Settings.OpenCacheLocation) },
                    enabled = state.isCacheLocationOpenable
                ) { Text("Open Cache Directory") }
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                Button(
                    onClick = { onEvent(Event.Navigate(Screen.LOGS)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("View Application Logs")
                }
            }
        }


        Button(
            onClick = { onEvent(Event.Settings.RequestRestoreDefaults) },
            colors = ButtonDefaults.outlinedButtonColors(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Restore All Defaults")
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Manga Combiner v${AppVersion.NAME}",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }

    if (state.showRestoreDefaultsDialog) {
        AlertDialog(
            onDismissRequest = { onEvent(Event.Settings.CancelRestoreDefaults) },
            title = { Text("Restore Default Settings?") },
            text = { Text("Are you sure you want to restore all settings to their original defaults? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { onEvent(Event.Settings.ConfirmRestoreDefaults) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(Event.Settings.CancelRestoreDefaults) }) {
                    Text("Cancel")
                }
            }
        )
    }
}
