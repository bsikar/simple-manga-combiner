package com.mangacombiner.di

import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.util.ClipboardManager
import com.mangacombiner.util.DesktopPlatformProvider
import com.mangacombiner.util.PlatformProvider
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    factory { ClipboardManager() }
    factory<PlatformProvider> { DesktopPlatformProvider() }

    // ViewModel for Desktop
    factoryOf(::MainViewModel)
}
