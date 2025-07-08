package com.mangacombiner.di

import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.FileConverter
import com.mangacombiner.service.ProcessorService
import com.mangacombiner.service.ScraperService
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.util.getTmpDir
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

val appModule = module {
    // Services
    single { ScraperService() }
    single { FileConverter() }
    single { ProcessorService(get()) }
    single { DownloadService(get(), get()) }

    // Platform-specific dependencies
    single { getTmpDir() }

    // ViewModel is now defined in platform-specific modules
}
