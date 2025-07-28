package com.mangacombiner.di

import com.mangacombiner.data.SettingsRepository
import com.mangacombiner.service.BackgroundDownloader
import com.mangacombiner.service.DesktopDownloader
import com.mangacombiner.service.EpubReaderService
import com.mangacombiner.service.ReadingProgressRepository
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.util.ClipboardManager
import com.mangacombiner.util.DesktopPlatformProvider
import com.mangacombiner.util.FileMover
import com.mangacombiner.util.PlatformProvider
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    factory { ClipboardManager() }
    factory<PlatformProvider> { DesktopPlatformProvider() }
    single { SettingsRepository() }
    factory { FileMover() }

    // Provide the Desktop-specific implementation for the common interface
    singleOf(::DesktopDownloader).bind<BackgroundDownloader>()
    single { EpubReaderService() }
    single { ReadingProgressRepository() }

    // ViewModel for Desktop - updated with proxy services
    factory {
        MainViewModel(
            downloadService = get(),
            scraperService = get(),
            webDavService = get(),
            clipboardManager = get(),
            platformProvider = get(),
            cacheService = get(),
            settingsRepository = get(),
            queuePersistenceService = get(),
            fileMover = get(),
            backgroundDownloader = get(),
            proxyMonitorService = get(),
            networkInterceptor = get(),
            epubReaderService = get(),
            readingProgressRepository = get()
        )
    }
}
