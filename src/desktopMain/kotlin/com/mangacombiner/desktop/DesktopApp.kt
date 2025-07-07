package com.mangacombiner.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.mangacombiner.di.appModule
import com.mangacombiner.di.platformModule
import com.mangacombiner.ui.MainScreen
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.ui.viewmodel.MainViewModel
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get

fun main() {
    startKoin {
        modules(appModule, platformModule())
    }

    val viewModel: MainViewModel = get(MainViewModel::class.java)

    application {
        val state by viewModel.state.collectAsState()
        Window(onCloseRequest = ::exitApplication, title = "Manga Combiner") {
            AppTheme(theme = state.theme) {
                MainScreen(viewModel)
            }
        }
    }
}
