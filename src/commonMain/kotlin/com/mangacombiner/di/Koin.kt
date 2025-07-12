package com.mangacombiner.di

import com.mangacombiner.service.*
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The main Koin module for common, platform-agnostic dependencies.
 */
val appModule = module {
    // Services
    single { ScraperService() }
    single { FileConverter() }
    single { ProcessorService(get()) }
    single { DownloadService(get(), get()) }
    single { CacheService(get(), get()) }
    single { QueuePersistenceService(get()) }

    // ViewModels and other platform specifics are defined in platformModule()
}

/**
 * An expect function that requires each platform to provide its own module
 * containing platform-specific dependencies.
 */
expect fun platformModule(): Module
