package com.mangacombiner.android

import android.app.Application
import com.mangacombiner.di.appModule
import com.mangacombiner.di.platformModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class AndroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@AndroidApp)
            modules(appModule, platformModule())
        }
    }
}
