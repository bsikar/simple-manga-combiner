package com.mangacombiner.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.widget.AboutDialog
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get
import java.awt.Desktop
import java.awt.desktop.AboutEvent
import java.awt.desktop.AboutHandler
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
            viewModel.onEvent(MainViewModel.Event.ToggleAboutDialog(true))
        }
    }

    application {
        val state by viewModel.state.collectAsState()
        val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))

        Window(
            onCloseRequest = ::exitApplication,
            title = "Manga Combiner",
            state = windowState,
            onKeyEvent = {
                if (it.type == KeyEventType.KeyDown) {
                    val isModifierPressed = if (isMac) it.isMetaPressed else it.isCtrlPressed
                    if (isModifierPressed) {
                        when (it.key) {
                            Key.Equals, Key.Plus -> {
                                viewModel.onEvent(MainViewModel.Event.ZoomIn)
                                true
                            }
                            Key.Minus -> {
                                viewModel.onEvent(MainViewModel.Event.ZoomOut)
                                true
                            }
                            Key.Zero -> {
                                viewModel.onEvent(MainViewModel.Event.ZoomReset)
                                true
                            }
                            else -> false
                        }
                    } else false
                } else false
            }
        ) {
            MenuBar {
                Menu("Help", mnemonic = 'H') {
                    // On non-macOS, the "About" item goes in the Help menu.
                    // On macOS, it's handled by the system via setAboutHandler.
                    if (!isMac) {
                        Item(
                            "About Manga Combiner",
                            onClick = { viewModel.onEvent(MainViewModel.Event.ToggleAboutDialog(true)) }
                        )
                    }
                }
            }

            val density = LocalDensity.current
            val fontMultiplier = when (state.fontSizePreset) {
                "Small" -> 0.85f
                "Large" -> 1.15f
                else -> 1.0f
            }
            val newDensity = Density(
                density.density * state.zoomFactor,
                density.fontScale * state.zoomFactor * fontMultiplier
            )

            CompositionLocalProvider(LocalDensity provides newDensity) {
                AppTheme(
                    settingsTheme = state.theme,
                    systemLightTheme = state.systemLightTheme,
                    systemDarkTheme = state.systemDarkTheme,
                ) {
                    MainScreen(viewModel)
                }
            }

            if (state.showAboutDialog) {
                AboutDialog(
                    onDismissRequest = { viewModel.onEvent(MainViewModel.Event.ToggleAboutDialog(false)) }
                )
            }
        }
    }
}
