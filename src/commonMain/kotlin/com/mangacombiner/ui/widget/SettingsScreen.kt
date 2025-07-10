package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.Screen
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.util.AppVersion

@Composable
fun SettingsScreen(state: UiState, onEvent: (Event) -> Unit) {
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
                                    onEvent(Event.Settings.UpdateTheme(theme))
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
                                            onEvent(Event.Settings.UpdateSystemLightTheme(theme))
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
                                            onEvent(Event.Settings.UpdateSystemDarkTheme(theme))
                                            systemDarkThemeDropdownExpanded = false
                                        }) { Text(theme.name.lowercase().replaceFirstChar { it.titlecase() }) }
                                    }
                                }
                            }
                        }
                    }
                }

                val fontPresets = listOf("X-Small", "Small", "Medium", "Large", "X-Large")
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Font Size:", style = MaterialTheme.typography.body1)
                    fontPresets.forEach { preset ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onEvent(Event.Settings.UpdateFontSizePreset(preset)) }
                        ) {
                            RadioButton(
                                selected = (state.fontSizePreset == preset),
                                onClick = { onEvent(Event.Settings.UpdateFontSizePreset(preset)) }
                            )
                            Text(text = preset)
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
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cache Management", style = MaterialTheme.typography.h6)
                Text(
                    "The app keeps unfinished downloads in a temporary cache. You can view, manage, or clear this cache.",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "Location: ${state.cachePath}",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
                    softWrap = true
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = {
                            onEvent(Event.Cache.RefreshView)
                            onEvent(Event.Navigate(Screen.CACHE_VIEWER))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("View Cache")
                    }
                    Button(
                        onClick = { onEvent(Event.Cache.RequestClearAll) },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = MaterialTheme.colors.error,
                            contentColor = MaterialTheme.colors.onError
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear Cache")
                    }
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

    if (state.showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { onEvent(Event.Cache.CancelClearAll) },
            title = { Text("Confirm Clear All Cache") },
            text = { Text("Are you sure you want to delete all temporary application data, including paused or incomplete downloads? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { onEvent(Event.Cache.ConfirmClearAll) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                ) {
                    Text("Clear Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(Event.Cache.CancelClearAll) }) {
                    Text("Cancel")
                }
            }
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
