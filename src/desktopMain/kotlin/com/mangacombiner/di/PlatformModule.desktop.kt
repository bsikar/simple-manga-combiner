package com.mangacombiner.di

import com.mangacombiner.data.NsfwRepository
import com.mangacombiner.data.SafeOverrideRepository
import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.service.BackgroundDownloader
import com.mangacombiner.service.DesktopDownloader
import com.mangacombiner.service.EpubReaderService
import com.mangacombiner.service.ReadingProgressRepository
import com.mangacombiner.ui.viewmodel.*
import com.mangacombiner.util.ClipboardManager
import com.mangacombiner.util.DesktopPlatformProvider
import com.mangacombiner.util.FileMover
import com.mangacombiner.util.PlatformProvider
import com.mangacombiner.util.UpdateDownloader
import com.mangacombiner.util.getAppVersionProvider
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    factory { ClipboardManager() }
    factory<PlatformProvider> { DesktopPlatformProvider() }
    single { SettingsRepository() }
    singleOf(::NsfwRepository)
    singleOf(::SafeOverrideRepository)
    factory { FileMover() }
    factory { UpdateDownloader() }

    single { getAppVersionProvider() }

    // Provide the Desktop-specific implementation for the common interface
    singleOf(::DesktopDownloader).bind<BackgroundDownloader>()
    singleOf(::EpubReaderService).bind<EpubReaderService>()
    single { ReadingProgressRepository() }

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

    // ViewModel for Desktop - using dependency groups
    factory {
        MainViewModel(
            downloadDependencies = get(),
            repositoryDependencies = get(),
            networkDependencies = get(),
            platformDependencies = get()
        )
    }
}
