package com.mangacombiner.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mangacombiner.di.appModule
import com.mangacombiner.di.platformModule
import com.mangacombiner.ui.MainScreen
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.FilePickerRequest
import com.mangacombiner.ui.widget.AboutDialog
import com.mangacombiner.util.Logger
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Taskbar
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Main entry point for the desktop application.
 * Handles Koin dependency injection, platform-specific configurations,
 * and provides file picker integration for desktop platforms.
 */
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
        Logger.logDebug { "Configured macOS-specific settings" }
    }

    application {
        val state by viewModel.state.collectAsState()
        val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))

        // Load the application icon using the modern approach
        val windowIcon = remember {
            try {
                val resourceStream = this::class.java.classLoader.getResourceAsStream("icon_desktop.png")
                if (resourceStream != null) {
                    val bytes = resourceStream.readBytes()
                    BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
                } else {
                    Logger.logError("Could not find icon_desktop.png resource")
                    null
                }
            } catch (e: Exception) {
                Logger.logError("Failed to load window icon", e)
                null
            }
        }

        // Set the taskbar/dock icon (platform-specific)
        LaunchedEffect(Unit) {
            if (Taskbar.isTaskbarSupported()) {
                try {
                    val iconStream = this::class.java.classLoader.getResourceAsStream("icon_desktop.png")
                    if (iconStream != null) {
                        Taskbar.getTaskbar().iconImage = ImageIO.read(iconStream)
                        Logger.logDebug { "Successfully set taskbar icon" }
                    } else {
                        Logger.logError("Could not find icon_desktop.png for taskbar")
                    }
                } catch (e: Exception) {
                    Logger.logError("Failed to set taskbar icon", e)
                }
            } else {
                Logger.logDebug { "Taskbar icon setting not supported on this platform" }
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = "Manga Combiner",
            state = windowState,
            icon = windowIcon, // Use the loaded icon or null if loading failed
            onKeyEvent = { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val isModifierPressed = if (isMac) keyEvent.isMetaPressed else keyEvent.isCtrlPressed
                    if (isModifierPressed) {
                        when (keyEvent.key) {
                            Key.Equals, Key.Plus -> {
                                viewModel.onEvent(Event.Library.ZoomIn)
                                true
                            }
                            Key.Minus -> {
                                viewModel.onEvent(Event.Library.ZoomOut)
                                true
                            }
                            Key.Zero -> {
                                viewModel.onEvent(Event.Library.ResetZoom)
                                true
                            }
                            else -> false
                        }
                    } else false
                } else false
            }
        ) {
            // Handle file picker dialogs from the ViewModel
            LaunchedEffect(Unit) {
                viewModel.filePickerRequest.collect { request ->
                    when (request) {
                        is FilePickerRequest.OpenFile -> {
                            val path = showFilePickerAwt("Select EPUB File") { _, name ->
                                name.endsWith(".epub", true)
                            }
                            path?.let {
                                viewModel.onFileSelected(it)
                                Logger.logDebug { "User selected file: $it" }
                            }
                        }
                        is FilePickerRequest.OpenFolder -> {
                            val path = showFolderPickerAwt("Select Folder")
                            path?.let {
                                viewModel.onFolderSelected(it, request.forPath)
                                Logger.logDebug { "User selected folder: $it" }
                            }
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

            // Calculate scaling factors for UI elements
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

            // Show about dialog when requested
            if (state.showAboutDialog) {
                AboutDialog(
                    onDismissRequest = { viewModel.onEvent(Event.ToggleAboutDialog(false)) }
                )
            }
        }
    }
}

/**
 * Shows a native file picker dialog using AWT FileDialog.
 * Provides cross-platform file selection with optional filtering.
 */
private fun showFilePickerAwt(title: String, filter: ((File, String) -> Boolean)? = null): String? {
    return try {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
            if (filter != null) {
                setFilenameFilter(filter)
            }
            isVisible = true
        }

        if (dialog.directory != null && dialog.file != null) {
            File(dialog.directory, dialog.file).absolutePath
        } else {
            null
        }
    } catch (e: Exception) {
        Logger.logError("Failed to show file picker dialog", e)
        null
    }
}

/**
 * Shows a native folder picker dialog using AWT FileDialog.
 * On macOS, uses the fileDialogForDirectories system property for folder selection.
 */
private fun showFolderPickerAwt(title: String): String? {
    return try {
        // Enable directory selection mode on macOS
        val originalValue = System.getProperty("apple.awt.fileDialogForDirectories")
        System.setProperty("apple.awt.fileDialogForDirectories", "true")

        val path = showFilePickerAwt(title)

        // Restore original property value
        if (originalValue != null) {
            System.setProperty("apple.awt.fileDialogForDirectories", originalValue)
        } else {
            System.clearProperty("apple.awt.fileDialogForDirectories")
        }

        path
    } catch (e: Exception) {
        Logger.logError("Failed to show folder picker dialog", e)
        null
    }
}
