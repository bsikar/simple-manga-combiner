package com.mangacombiner.di

import com.mangacombiner.service.CacheService
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.FileConverter
import com.mangacombiner.service.ProcessorService
import com.mangacombiner.service.ScraperService
import org.koin.dsl.module

val appModule = module {
    // Services
    single { ScraperService() }
    single { FileConverter() }
    single { ProcessorService(get()) }
    single { DownloadService(get(), get()) }
    single { CacheService(get()) } // Add CacheService as a class with its dependency

    // ViewModel is now defined in platform-specific modules
}
