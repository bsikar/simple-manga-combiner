package com.mangacombiner.util

class DesktopPlatformProvider : PlatformProvider {
    override fun getTmpDir(): String = System.getProperty("java.io.tmpdir")
}
