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
import androidx.compose.ui.unit.dp
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.UiState

@Composable
fun SettingsScreen(state: UiState, onEvent: (MainViewModel.Event) -> Unit) {
    var themeDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(elevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Appearance", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(16.dp))

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
                            AppTheme.values().forEach { theme ->
                                DropdownMenuItem(onClick = {
                                    onEvent(MainViewModel.Event.UpdateTheme(theme))
                                    themeDropdownExpanded = false
                                }) { Text(theme.name.lowercase().replaceFirstChar { it.titlecase() }) }
                            }
                        }
                    }
                }
            }
        }

        Card(elevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cache Management", style = MaterialTheme.typography.h6)
                Text(
                    "The app creates temporary folders for downloads and conversions. These are usually deleted, but can sometimes remain if the app closes unexpectedly.",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = ContentAlpha.medium)
                )
                Button(
                    onClick = { onEvent(MainViewModel.Event.RequestClearCache) },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.error,
                        contentColor = MaterialTheme.colors.onError
                    )
                ) {
                    Text("Clear All Cached Files")
                }
            }
        }
    }

    if (state.showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { onEvent(MainViewModel.Event.CancelClearCache) },
            title = { Text("Confirm Clear Cache") },
            text = { Text("Are you sure you want to delete all temporary application data? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { onEvent(MainViewModel.Event.ConfirmClearCache) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                ) {
                    Text("Clear Cache")
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(MainViewModel.Event.CancelClearCache) }) {
                    Text("Cancel")
                }
            }
        )
    }
}
