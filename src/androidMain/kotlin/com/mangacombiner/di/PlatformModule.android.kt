package com.mangacombiner.di

import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.service.BackgroundDownloader
import com.mangacombiner.service.BackgroundDownloaderService
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.util.AndroidPlatformProvider
import com.mangacombiner.util.ClipboardManager
import com.mangacombiner.util.FileMover
import com.mangacombiner.util.PlatformProvider
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    factory { ClipboardManager(androidContext()) }
    factory<PlatformProvider> { AndroidPlatformProvider(androidContext()) }
    single { SettingsRepository(androidContext()) }
    factory { FileMover(androidContext()) }

    // Provide the Android-specific service as the implementation for the common interface
    single<BackgroundDownloader> { BackgroundDownloaderService() }

    // ViewModel for Android
    viewModelOf(::MainViewModel)
}
