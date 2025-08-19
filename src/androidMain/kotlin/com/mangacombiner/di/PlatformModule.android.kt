package com.mangacombiner.di

import com.mangacombiner.data.NsfwRepository
import com.mangacombiner.data.SafeOverrideRepository
import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.service.AndroidBackgroundDownloader
import com.mangacombiner.service.BackgroundDownloader
import com.mangacombiner.service.EpubReaderService
import com.mangacombiner.service.ReadingProgressRepository
import com.mangacombiner.ui.viewmodel.*
import com.mangacombiner.util.AndroidPlatformProvider
import com.mangacombiner.util.ClipboardManager
import com.mangacombiner.util.FileMover
import com.mangacombiner.util.PlatformProvider
import com.mangacombiner.util.UpdateDownloader
import com.mangacombiner.util.getAppVersionProvider
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
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
    single { NsfwRepository(androidContext()) }
    single { SafeOverrideRepository(androidContext()) }

    // File operations using Android's Storage Access Framework
    factory { FileMover(androidContext()) }

    factory { UpdateDownloader(androidContext()) }

    single { getAppVersionProvider() }

    // Background download service implementation for Android
    single<BackgroundDownloader> { AndroidBackgroundDownloader(androidContext(), get(), get()) }

    // Epub reader service
    singleOf(::EpubReaderService)
    single { ReadingProgressRepository(androidContext()) }

    // Dependency groups for MainViewModel
    factory {
        DownloadDependencies(
            downloadService = get(),
            scraperService = get(),
            backgroundDownloader = get(),
            cacheService = get(),
            queuePersistenceService = get()
        )
    }
    
    factory {
        RepositoryDependencies(
            settingsRepository = get(),
            readingProgressRepository = get(),
            nsfwRepository = get(),
            safeOverrideRepository = get()
        )
    }
    
    factory {
        NetworkDependencies(
            proxyMonitorService = get(),
            networkInterceptor = get(),
            webDavService = get()
        )
    }
    
    factory {
        PlatformDependencies(
            clipboardManager = get(),
            platformProvider = get(),
            fileMover = get(),
            epubReaderService = get(),
            updateService = get(),
            updateDownloader = get(),
            appVersionProvider = get()
        )
    }

    // ViewModel registration using dependency groups
    viewModelOf(::MainViewModel)
}
