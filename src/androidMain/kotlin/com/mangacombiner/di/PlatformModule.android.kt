package com.mangacombiner.di

import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.service.AndroidBackgroundDownloader
import com.mangacombiner.service.BackgroundDownloader
import com.mangacombiner.service.EpubReaderService
import com.mangacombiner.service.ReadingProgressRepository
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.util.AndroidPlatformProvider
import com.mangacombiner.util.ClipboardManager
import com.mangacombiner.util.FileMover
import com.mangacombiner.util.PlatformProvider
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Android-specific dependency injection module providing platform implementations
 * for file handling, background operations, and UI components.
 */
actual fun platformModule(): Module = module {

    // Platform-specific clipboard operations using Android Context
    factory { ClipboardManager(androidContext()) }

    // Android implementation of platform provider for system interactions
    factory<PlatformProvider> { AndroidPlatformProvider(androidContext()) }

    // Settings repository with Android SharedPreferences backing
    single { SettingsRepository(androidContext()) }

    // File operations using Android's Storage Access Framework
    factory { FileMover(androidContext()) }

    // Background download service implementation for Android
    single<BackgroundDownloader> { AndroidBackgroundDownloader(androidContext(), get(), get()) }

    // Epub reader service
    single { EpubReaderService() }
    single { ReadingProgressRepository(androidContext()) }

    // ViewModel registration using updated Koin DSL - let it use constructor injection
    viewModelOf(::MainViewModel)
}
