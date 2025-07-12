package com.mangacombiner.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mangacombiner.di.appModule
import com.mangacombiner.di.platformModule
import com.mangacombiner.model.IconTheme
import com.mangacombiner.ui.MainScreen
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.FilePickerRequest
import com.mangacombiner.ui.widget.AboutDialog
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale

fun main() {
    startKoin {
        modules(appModule, platformModule())
    }

    val viewModel: MainViewModel = get(MainViewModel::class.java)
    val os = System.getProperty("os.name").lowercase(Locale.US)
    val isMac = "mac" in os

    // Set macOS-specific properties and handlers
    if (isMac) {
        System.setProperty("apple.awt.application.appearance", "system")
        System.setProperty("apple.awt.application.name", "Manga Combiner")
        Desktop.getDesktop().setAboutHandler {
            viewModel.onEvent(Event.ToggleAboutDialog(true))
        }
    }

    application {
        val state by viewModel.state.collectAsState()
        val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))

        // Determine which icon to use based on the current state
        val windowIcon = painterResource(
            when (state.iconTheme) {
                IconTheme.COLOR -> "icon_desktop_color.png"
                IconTheme.MONO -> "icon_desktop_mono.png"
            }
        )

        Window(
            onCloseRequest = ::exitApplication,
            title = "Manga Combiner",
            state = windowState,
            icon = windowIcon, // Use the dynamic icon
            onKeyEvent = {
                if (it.type == KeyEventType.KeyDown) {
                    val isModifierPressed = if (isMac) it.isMetaPressed else it.isCtrlPressed
                    if (isModifierPressed) {
                        when (it.key) {
                            Key.Equals, Key.Plus -> {
                                viewModel.onEvent(Event.Settings.ZoomIn)
                                true
                            }
                            Key.Minus -> {
                                viewModel.onEvent(Event.Settings.ZoomOut)
                                true
                            }
                            Key.Zero -> {
                                viewModel.onEvent(Event.Settings.ZoomReset)
                                true
                            }
                            else -> false
                        }
                    } else false
                } else false
            }
        ) {
            // Add a LaunchedEffect to handle file picker dialogs from the ViewModel
            LaunchedEffect(Unit) {
                viewModel.filePickerRequest.collect { request ->
                    when (request) {
                        is FilePickerRequest.OpenFile -> {
                            val path = showFilePickerAwt("Select EPUB or CBZ File") { _, name ->
                                name.endsWith(".cbz", true) || name.endsWith(".epub", true)
                            }
                            path?.let { viewModel.onFileSelected(it) }
                        }
                        is FilePickerRequest.OpenFolder -> {
                            val path = showFolderPickerAwt("Select Folder")
                            path?.let { viewModel.onFolderSelected(it, request.forPath) }
                        }
                    }
                }
            }

            MenuBar {
                Menu("Help", mnemonic = 'H') {
                    if (!isMac) {
                        Item(
                            "About Manga Combiner",
                            onClick = { viewModel.onEvent(Event.ToggleAboutDialog(true)) }
                        )
                    }
                }
            }

            val density = LocalDensity.current
            val fontMultiplier = when (state.fontSizePreset) {
                "XX-Small" -> 0.5f
                "X-Small" -> 0.75f
                "Small" -> 0.90f
                "Large" -> 1.15f
                "X-Large" -> 1.30f
                "XX-Large" -> 1.45f
                else -> 1.0f // Medium
            }
            val newDensity = Density(
                density.density * state.zoomFactor,
                density.fontScale * state.zoomFactor * fontMultiplier
            )

            CompositionLocalProvider(LocalDensity provides newDensity) {
                AppTheme(settingsTheme = state.theme) {
                    MainScreen(viewModel)
                }
            }

            if (state.showAboutDialog) {
                AboutDialog(
                    onDismissRequest = { viewModel.onEvent(Event.ToggleAboutDialog(false)) }
                )
            }
        }
    }
}

private fun showFilePickerAwt(title: String, filter: ((File, String) -> Boolean)? = null): String? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
        if (filter != null) {
            setFilenameFilter(filter)
        }
        isVisible = true
    }
    return if (dialog.directory != null && dialog.file != null) {
        File(dialog.directory, dialog.file).absolutePath
    } else {
        null
    }
}

private fun showFolderPickerAwt(title: String): String? {
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    val path = showFilePickerAwt(title)
    System.setProperty("apple.awt.fileDialogForDirectories", "false")
    return path
}
