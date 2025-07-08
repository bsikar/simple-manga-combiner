package com.mangacombiner.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.mangacombiner.ui.MainScreen
import com.mangacombiner.ui.theme.AppTheme
import com.mangacombiner.ui.viewmodel.MainViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModel<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            val density = LocalDensity.current
            val fontMultiplier = when (state.fontSizePreset) {
                "Small" -> 0.85f
                "Large" -> 1.15f
                else -> 1.0f
            }
            val newDensity = Density(
                density.density, // On Android, we respect the system's density
                density.fontScale * fontMultiplier
            )

            CompositionLocalProvider(LocalDensity provides newDensity) {
                AppTheme(
                    settingsTheme = state.theme,
                    systemLightTheme = state.systemLightTheme,
                    systemDarkTheme = state.systemDarkTheme
                ) {
                    MainScreen(viewModel)
                }
            }
        }
    }
}
