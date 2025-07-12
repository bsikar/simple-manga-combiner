package com.mangacombiner.di

import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.service.BackgroundDownloader
import com.mangacombiner.service.DesktopDownloader
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.util.ClipboardManager
import com.mangacombiner.util.DesktopPlatformProvider
import com.mangacombiner.util.FileMover
import com.mangacombiner.util.PlatformProvider
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    factory { ClipboardManager() }
    factory<PlatformProvider> { DesktopPlatformProvider() }
    single { SettingsRepository() }
    factory { FileMover() }

    // Provide the Desktop-specific implementation for the common interface
    single<BackgroundDownloader> { DesktopDownloader() }

    // ViewModel for Desktop
    factory {
        MainViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get())
    }
}
