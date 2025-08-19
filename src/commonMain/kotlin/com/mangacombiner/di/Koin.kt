package com.mangacombiner.di

import com.mangacombiner.service.CacheService
import com.mangacombiner.service.DownloadService
import com.mangacombiner.service.FileConverter
import com.mangacombiner.service.IpLookupService
import com.mangacombiner.service.NetworkInterceptor
import com.mangacombiner.service.ProcessorService
import com.mangacombiner.service.ProxyMonitorService
import com.mangacombiner.service.QueuePersistenceService
import com.mangacombiner.service.ScrapeCacheService
import com.mangacombiner.service.ScraperService
import com.mangacombiner.service.UpdateService
import com.mangacombiner.service.WebDavService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
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
    single {
        HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
        }
    }
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
    single { UpdateService(get()) }

    // ViewModels and other platform specifics are defined in platformModule()
}

/**
 * An expect function that requires each platform to provide its own module
 * containing platform-specific dependencies.
 */
expect fun platformModule(): Module
