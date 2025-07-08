package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.Screen
import com.mangacombiner.ui.viewmodel.UiState

@Composable
fun SettingsScreen(state: UiState, onEvent: (MainViewModel.Event) -> Unit) {
    var themeDropdownExpanded by remember { mutableStateOf(false) }
    var systemLightThemeDropdownExpanded by remember { mutableStateOf(false) }
    var systemDarkThemeDropdownExpanded by remember { mutableStateOf(false) }
    var outputDropdownExpanded by remember { mutableStateOf(false) }
    val outputLocations = listOf("Downloads", "Documents", "Desktop", "Custom").filter {
        // Simple way to hide "Desktop" on Android, which returns null for it
        !(it == "Desktop" && System.getProperty("java.runtime.name")?.contains("Android") == true)
    }
    val themeOptions = remember { AppTheme.values().toList() }
    val systemThemeOptions = remember { themeOptions.filter { it != AppTheme.SYSTEM } }

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
                            Text(state.theme.name.lowercase().replaceFirstChar { it.titlecase() })
                            Icon(Icons.Default.ArrowDropDown, "Theme")
                        }
                        DropdownMenu(
                            expanded = themeDropdownExpanded,
                            onDismissRequest = { themeDropdownExpanded = false }
                        ) {
                            themeOptions.forEach { theme ->
                                DropdownMenuItem(onClick = {
                                    onEvent(MainViewModel.Event.UpdateTheme(theme))
                                    themeDropdownExpanded = false
                                }) { Text(theme.name.lowercase().replaceFirstChar { it.titlecase() }) }
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = state.theme == AppTheme.SYSTEM) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Light Mode Theme:", style = MaterialTheme.typography.body1)
                            Box {
                                OutlinedButton(onClick = { systemLightThemeDropdownExpanded = true }) {
                                    Text(state.systemLightTheme.name.lowercase().replaceFirstChar { it.titlecase() })
                                    Icon(Icons.Default.ArrowDropDown, "Light Theme")
                                }
                                DropdownMenu(
                                    expanded = systemLightThemeDropdownExpanded,
                                    onDismissRequest = { systemLightThemeDropdownExpanded = false }
                                ) {
                                    systemThemeOptions.forEach { theme ->
                                        DropdownMenuItem(onClick = {
                                            onEvent(MainViewModel.Event.UpdateSystemLightTheme(theme))
                                            systemLightThemeDropdownExpanded = false
                                        }) { Text(theme.name.lowercase().replaceFirstChar { it.titlecase() }) }
                                    }
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Dark Mode Theme:", style = MaterialTheme.typography.body1)
                            Box {
                                OutlinedButton(onClick = { systemDarkThemeDropdownExpanded = true }) {
                                    Text(state.systemDarkTheme.name.lowercase().replaceFirstChar { it.titlecase() })
                                    Icon(Icons.Default.ArrowDropDown, "Dark Theme")
                                }
                                DropdownMenu(
                                    expanded = systemDarkThemeDropdownExpanded,
                                    onDismissRequest = { systemDarkThemeDropdownExpanded = false }
                                ) {
                                    systemThemeOptions.forEach { theme ->
                                        DropdownMenuItem(onClick = {
                                            onEvent(MainViewModel.Event.UpdateSystemDarkTheme(theme))
                                            systemDarkThemeDropdownExpanded = false
                                        }) { Text(theme.name.lowercase().replaceFirstChar { it.titlecase() }) }
                                    }
                                }
                            }
                        }
                    }
                }

                val fontPresets = listOf("Small", "Medium", "Large")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Font Size:", style = MaterialTheme.typography.body1)
                    Row {
                        fontPresets.forEach { preset ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { onEvent(MainViewModel.Event.UpdateFontSizePreset(preset)) }
                            ) {
                                RadioButton(
                                    selected = (state.fontSizePreset == preset),
                                    onClick = { onEvent(MainViewModel.Event.UpdateFontSizePreset(preset)) }
                                )
                                Text(text = preset)
                            }
                        }
                    }
                }
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
                                    onEvent(MainViewModel.Event.UpdateDefaultOutputLocation(location))
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
                        Button(onClick = { onEvent(MainViewModel.Event.PickCustomDefaultPath) }) {
                            Text("Browse...")
                        }
                    }
                }
            }
        }

        Card(elevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cache Management", style = MaterialTheme.typography.h6)
                Text(
                    "The app keeps unfinished downloads in a temporary cache. You can view, manage, or clear this cache.",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = { onEvent(MainViewModel.Event.Navigate(Screen.CACHE_VIEWER)) },
                    ) {
                        Text("View Cached Downloads")
                    }
                    Button(
                        onClick = { onEvent(MainViewModel.Event.RequestClearAllCache) },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = MaterialTheme.colors.error,
                            contentColor = MaterialTheme.colors.onError
                        )
                    ) {
                        Text("Clear All Cache")
                    }
                }
            }
        }

        Card(elevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Configuration File Location", style = MaterialTheme.typography.h6)
                Text(
                    text = state.settingsLocationDescription,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
                if (state.isSettingsLocationOpenable) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Button(onClick = { onEvent(MainViewModel.Event.OpenSettingsLocation) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Open Location")
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("Open Location")
                        }
                    }
                }
            }
        }

        Button(
            onClick = { onEvent(MainViewModel.Event.RequestRestoreDefaults) },
            colors = ButtonDefaults.outlinedButtonColors(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Restore All Defaults")
        }
    }

    if (state.showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { onEvent(MainViewModel.Event.CancelClearAllCache) },
            title = { Text("Confirm Clear All Cache") },
            text = { Text("Are you sure you want to delete all temporary application data, including paused or incomplete downloads? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { onEvent(MainViewModel.Event.ConfirmClearAllCache) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                ) {
                    Text("Clear Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(MainViewModel.Event.CancelClearAllCache) }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (state.showRestoreDefaultsDialog) {
        AlertDialog(
            onDismissRequest = { onEvent(MainViewModel.Event.CancelRestoreDefaults) },
            title = { Text("Restore Default Settings?") },
            text = { Text("Are you sure you want to restore all settings to their original defaults? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { onEvent(MainViewModel.Event.ConfirmRestoreDefaults) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(MainViewModel.Event.CancelRestoreDefaults) }) {
                    Text("Cancel")
                }
            }
        )
    }
}
