package com.mangacombiner.util

import java.io.File

actual fun restartApp(updatePath: String) {
    val currentJar = File(object {}.javaClass.protectionDomain.codeSource.location.toURI())
    val javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"

    // Copy the new JAR over the old one
    ProcessBuilder("cp", updatePath, currentJar.absolutePath).start().waitFor()

    // Restart the application
    ProcessBuilder(javaBin, "-jar", currentJar.absolutePath).start()
    kotlin.system.exitProcess(0)
}