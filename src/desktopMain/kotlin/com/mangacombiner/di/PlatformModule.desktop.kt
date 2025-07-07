package com.mangacombiner.di

import com.mangacombiner.util.ClipboardManager
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    factory { ClipboardManager() }
}
