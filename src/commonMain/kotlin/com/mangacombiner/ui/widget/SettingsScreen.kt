package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangacombiner.model.ProxyType
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.ProxyStatus
import com.mangacombiner.ui.viewmodel.state.Screen
import com.mangacombiner.ui.viewmodel.state.UiState
import com.mangacombiner.util.AppVersion
import com.mangacombiner.util.pointer.tooltipHoverFix
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
                    Box(
                        modifier = Modifier.tooltipHoverFix()
                    ) {
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
                    Box(
                        modifier = Modifier.tooltipHoverFix()
                    ) {
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
                    onClick = { onEvent(Event.Settings.ToggleOfflineMode(!state.offlineMode)) },
                    control = { Switch(checked = state.offlineMode, onCheckedChange = null) },
                    label = { Text("Offline Mode") }
                )
                Text(
                    "Enable to modify local files, such as removing chapters from an EPUB, without needing an internet connection.",
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }

        Card(elevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Proxy Settings", style = MaterialTheme.typography.h6)

                var proxyTypeDropdownExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Proxy Type:", style = MaterialTheme.typography.body1)
                    Box(modifier = Modifier.tooltipHoverFix()) {
                        OutlinedButton(onClick = { proxyTypeDropdownExpanded = true }) {
                            Text(state.proxyType.name)
                            Icon(Icons.Default.ArrowDropDown, "Proxy Type")
                        }
                        DropdownMenu(
                            expanded = proxyTypeDropdownExpanded,
                            onDismissRequest = { proxyTypeDropdownExpanded = false }
                        ) {
                            ProxyType.entries.forEach { type ->
                                DropdownMenuItem(onClick = {
                                    onEvent(Event.Settings.UpdateProxyType(type))
                                    proxyTypeDropdownExpanded = false
                                }) { Text(type.name) }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.proxyHost,
                        onValueChange = { onEvent(Event.Settings.UpdateProxyHost(it)) },
                        label = { Text("Proxy Host") },
                        modifier = Modifier.weight(1f),
                        enabled = state.proxyType != ProxyType.NONE,
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.proxyPort,
                        onValueChange = { onEvent(Event.Settings.UpdateProxyPort(it.filter(Char::isDigit))) },
                        label = { Text("Port") },
                        modifier = Modifier.width(100.dp),
                        enabled = state.proxyType != ProxyType.NONE,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.proxyUser,
                        onValueChange = { onEvent(Event.Settings.UpdateProxyUser(it)) },
                        label = { Text("Username (Optional)") },
                        modifier = Modifier.weight(1f),
                        enabled = state.proxyType != ProxyType.NONE,
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.proxyPass,
                        onValueChange = { onEvent(Event.Settings.UpdateProxyPass(it)) },
                        label = { Text("Password (Optional)") },
                        modifier = Modifier.weight(1f),
                        enabled = state.proxyType != ProxyType.NONE,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }

                FormControlLabel(
                    onClick = { onEvent(Event.Settings.ToggleProxyOnStartup(!state.proxyEnabledOnStartup)) },
                    control = {
                        Switch(
                            checked = state.proxyEnabledOnStartup,
                            onCheckedChange = { onEvent(Event.Settings.ToggleProxyOnStartup(it)) },
                            enabled = state.proxyType != ProxyType.NONE
                        )
                    },
                    label = { Text("Connect to proxy on startup (Kill Switch)") }
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onEvent(Event.Settings.VerifyProxy) },
                        enabled = state.proxyType != ProxyType.NONE && state.proxyHost.isNotBlank() && state.proxyPort.isNotBlank() && state.proxyStatus != ProxyStatus.VERIFYING
                    ) {
                        if (state.proxyStatus == ProxyStatus.VERIFYING) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Verify")
                    }
                    Button(
                        onClick = { onEvent(Event.Settings.CheckIpAddress) },
                        enabled = state.proxyStatus != ProxyStatus.VERIFYING && !state.isCheckingIp
                    ) {
                        if (state.isCheckingIp) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Check IP")
                    }
                    when (state.proxyStatus) {
                        ProxyStatus.CONNECTED -> Icon(Icons.Default.CheckCircle, "Connected", tint = Color(0xFF4CAF50))
                        ProxyStatus.FAILED -> Icon(Icons.Default.Error, "Failed", tint = MaterialTheme.colors.error)
                        else -> {}
                    }
                    state.proxyVerificationMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.caption,
                            color = if (state.proxyStatus == ProxyStatus.FAILED) MaterialTheme.colors.error else LocalContentColor.current
                        )
                    }
                }
                AnimatedVisibility(visible = state.isCheckingIp || state.ipInfoResult != null || state.ipCheckError != null) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (state.isCheckingIp) {
                            Text("Checking IP address...", style = MaterialTheme.typography.caption)
                        }
                        state.ipInfoResult?.let { info ->
                            Text("Public IP: ${info.ip ?: "N/A"}", fontWeight = FontWeight.Bold)
                            Text("Location: ${listOfNotNull(info.city, info.region, info.country).joinToString(", ")}", style = MaterialTheme.typography.body2)
                            Text("ISP: ${info.org ?: "N/A"}", style = MaterialTheme.typography.caption)
                        }
                        state.ipCheckError?.let { error ->
                            Text(error, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
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
                    Box(
                        modifier = Modifier.tooltipHoverFix()
                    ) {
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
