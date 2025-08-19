package com.mangacombiner.util

actual fun restartApp(updatePath: String) {
    // On Android, the installation is handled by the system package installer,
    // which will restart the app automatically.
}
