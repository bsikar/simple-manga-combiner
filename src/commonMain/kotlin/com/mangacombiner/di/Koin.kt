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
    single { IpLookupService() } // Add the new service

    // Proxy monitoring and network interception
    single { ProxyMonitorService(get()) } // Inject IpLookupService
    single {
        NetworkInterceptor(
            proxyMonitor = get(),
            isProxyRequired = {
                val settings = get<com.mangacombiner.data.SettingsRepository>().loadSettings()
                settings.proxyEnabledOnStartup && settings.proxyType != com.mangacombiner.model.ProxyType.NONE
            }
        )
    }

    // Download service with network interceptor
    single { DownloadService(get(), get(), get()) }

    single { CacheService(get(), get()) }
    single { QueuePersistenceService(get()) }
    single { ScrapeCacheService(get()) }
    single { WebDavService() }

    // ViewModels and other platform specifics are defined in platformModule()
}

/**
 * An expect function that requires each platform to provide its own module
 * containing platform-specific dependencies.
 */
expect fun platformModule(): Module
