package com.mangacombiner.di

import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.FileConverter
import com.mangacombiner.service.ProcessorService
import com.mangacombiner.service.ScraperService
import org.koin.dsl.module

// This module defines how to create instances of our services.
// It's pure Kotlin and can be used on any platform.
val appModule = module {
    single { ScraperService() }
    single { FileConverter() }
    single { ProcessorService(get()) }
    single { DownloadService(get(), get()) }
}
