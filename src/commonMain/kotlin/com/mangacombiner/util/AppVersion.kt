package com.mangacombiner.util

interface AppVersionProvider {
    fun getAppVersion(): String
}

expect fun getAppVersionProvider(): AppVersionProvider

