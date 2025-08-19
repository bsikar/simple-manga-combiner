package com.mangacombiner.util

expect class UpdateDownloader {
    fun downloadAndInstall(url: String, onDownloaded: (String) -> Unit)
}
