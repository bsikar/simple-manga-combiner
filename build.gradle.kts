import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.plugin)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow.jar)
}

val appVersionName: String by project
val appVersionCode: String by project

val kotlin: org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension by project

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
                freeCompilerArgs += "-Xexpect-actual-classes"
            }
        }
    }

    jvm("desktop") {
        compilations.all {
            kotlinOptions.jvmTarget = "17"
            kotlinOptions.freeCompilerArgs += "-Xexpect-actual-classes"
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(layout.buildDirectory.dir("generated/version/commonMain"))
            dependencies {
                api(compose.runtime)
                api(compose.foundation)
                api(compose.material)
                @OptIn(ExperimentalComposeLibrary::class)
                api(compose.materialIconsExtended)

                api(libs.koin.core)
                api("io.insert-koin:koin-compose:1.1.2")

                api(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.cli)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.plugins)
                implementation(libs.ktor.client.logging)
                implementation(libs.xmlutil.serialization)
                implementation(libs.jsoup)
                implementation(libs.zip4j)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            dependencies {
                // Using BOM reference from libs.versions.toml to avoid deprecation warning
                implementation(libs.androidx.compose.bom)
                implementation("androidx.activity:activity-compose")

                implementation(libs.google.material)
                api(libs.koin.android)
                implementation(libs.ktor.client.cio)
                implementation(libs.androidx.documentfile)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                api(libs.koin.jvm)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.io.jvm)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json.jvm)
                implementation(libs.imageio.webp)
                implementation("org.slf4j:slf4j-simple:2.0.13")
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

android {
    namespace = "com.mangacombiner"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mangacombiner"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode.toInt()
        versionName = appVersionName
    }
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        disable.add("ComposableNaming")
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.mangacombiner.desktop.DesktopAppKt"
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            // Removed TargetFormat.Rpm to focus on Debian/Ubuntu support only
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "MangaCombiner"
            packageVersion = appVersionName
            description = "A tool to download and combine manga chapters."
            vendor = "MangaCombiner"

            // Configure platform-specific icons and metadata
            macOS {
                bundleID = "com.mangacombiner"
                iconFile.set(project.file("src/desktopMain/resources/icon_desktop.icns"))
                // Additional macOS-specific configuration for better compatibility
                signing {
                    sign.set(false) // Set to true if you have developer certificates
                }
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icon_desktop.ico"))
                // Windows-specific configuration for better MSI generation
                dirChooser = true
                perUserInstall = true
                menuGroup = "MangaCombiner"
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon_desktop.png"))
                // Linux-specific configuration optimized for Debian compatibility
                packageName = "manga-combiner"
            }
        }
    }
}

/**
 * Creates a platform-independent CLI JAR file containing all runtime dependencies.
 * This JAR can be executed on any system with Java 17+ installed using:
 * java -jar manga-combiner-cli-{version}.jar [CLI_ARGUMENTS]
 */
tasks.register<ShadowJar>("cliJar") {
    group = "build"
    description = "Assembles the CLI fat JAR with all dependencies included for cross-platform execution"

    archiveBaseName.set("manga-combiner-cli")
    archiveClassifier.set("")
    archiveVersion.set(appVersionName)

    manifest {
        attributes["Main-Class"] = "com.mangacombiner.desktop.CliRunnerKt"
        attributes["Implementation-Title"] = "MangaCombiner CLI"
        attributes["Implementation-Version"] = appVersionName
        attributes["Implementation-Vendor"] = "MangaCombiner"
        // Add proper module path handling for Java 17+
        attributes["Add-Opens"] = "java.base/java.lang=ALL-UNNAMED java.base/java.util=ALL-UNNAMED"
    }

    from(kotlin.targets.getByName("desktop").compilations.getByName("main").output)
    from(project.configurations.named("desktopRuntimeClasspath"))

    // Merge service files to prevent conflicts with multiple JARs containing the same services
    mergeServiceFiles()

    // Exclude duplicate files and conflicting metadata
    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
    exclude("META-INF/DEPENDENCIES")
    exclude("META-INF/LICENSE*")
    exclude("META-INF/NOTICE*")

    // Optimize the JAR by removing unnecessary files
    minimize {
        // Keep specific libraries that might be dynamically loaded
        exclude(dependency("org.slf4j:slf4j-simple"))
        exclude(dependency("io.ktor:ktor-client-cio"))
    }
}

/**
 * Creates the CLI wrapper script that will be installed system-wide.
 * This script provides a clean command-line interface without requiring users to run java -jar directly.
 */
tasks.register("createCliScript") {
    group = "build"
    description = "Creates the CLI wrapper script for system installation"
    dependsOn("cliJar")

    val scriptFile = layout.buildDirectory.file("scripts/manga-combiner-cli")
    outputs.file(scriptFile)

    doLast {
        scriptFile.get().asFile.parentFile.mkdirs()
        scriptFile.get().asFile.writeText("""#!/bin/bash
# MangaCombiner CLI wrapper script
# This script allows running the CLI without directly invoking java -jar

# Determine script location for relative JAR path resolution
SCRIPT_DIR="${'$'}(cd "${'$'}(dirname "${'$'}{BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="/usr/share/manga-combiner/manga-combiner-cli-${appVersionName}.jar"

# Verify Java installation and version compatibility
if ! command -v java >/dev/null 2>&1; then
    echo "Error: Java is not installed or not in PATH" >&2
    echo "Please install Java 17 or higher: sudo apt-get install default-jre" >&2
    exit 1
fi

# Check Java version (requires Java 17+)
JAVA_VERSION=${'$'}(java -version 2>&1 | head -n1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "${'$'}{JAVA_VERSION}" -lt 17 ]; then
    echo "Error: Java 17 or higher is required (found Java ${'$'}{JAVA_VERSION})" >&2
    echo "Please upgrade Java: sudo apt-get install default-jre" >&2
    exit 1
fi

# Verify JAR file exists
if [ ! -f "${'$'}{JAR_PATH}" ]; then
    echo "Error: CLI JAR not found at ${'$'}{JAR_PATH}" >&2
    echo "Please reinstall the manga-combiner-cli package" >&2
    exit 1
fi

# Execute the CLI JAR with all provided arguments
exec java -jar "${'$'}{JAR_PATH}" "${'$'}@"
""")
        // Make the script executable
        scriptFile.get().asFile.setExecutable(true)
    }
}

/**
 * Creates a DEB package for the CLI application that installs system-wide.
 * This allows users to run 'manga-combiner-cli' from anywhere without java -jar.
 * Note: This task requires dpkg-deb (Linux) or can be installed on macOS via Homebrew.
 */
tasks.register<Exec>("packageCliDeb") {
    group = "distribution"
    description = "Creates a DEB package for system-wide CLI installation"
    dependsOn("createCliScript", "cliJar")

    val packageDir = layout.buildDirectory.dir("cli-package")
    val debFile = layout.buildDirectory.file("distributions/manga-combiner-cli-${appVersionName}.deb")

    inputs.files(tasks.named("cliJar"), tasks.named("createCliScript"))
    outputs.file(debFile)

    // Only run on systems that have dpkg-deb available
    onlyIf {
        try {
            val process = ProcessBuilder("which", "dpkg-deb")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            process.waitFor() == 0
        } catch (e: Exception) {
            logger.warn("dpkg-deb not found. Skipping CLI DEB package creation.")
            logger.warn("To install on macOS: brew install dpkg")
            false
        }
    }

    doFirst {
        // Create package directory structure
        val baseDir = packageDir.get().asFile
        val usrShareDir = File(baseDir, "usr/share/manga-combiner")
        val usrBinDir = File(baseDir, "usr/bin")
        val debianDir = File(baseDir, "DEBIAN")

        // Clean and create directories
        baseDir.deleteRecursively()
        usrShareDir.mkdirs()
        usrBinDir.mkdirs()
        debianDir.mkdirs()

        // Copy CLI JAR to package
        val cliJarFile = layout.buildDirectory.file("libs/manga-combiner-cli-${appVersionName}.jar").get().asFile
        if (!cliJarFile.exists()) {
            throw GradleException("CLI JAR file not found: ${cliJarFile.absolutePath}. Run 'cliJar' task first.")
        }
        cliJarFile.copyTo(File(usrShareDir, "manga-combiner-cli-${appVersionName}.jar"))
        logger.lifecycle("Copied CLI JAR: ${cliJarFile.name} -> ${usrShareDir}/manga-combiner-cli-${appVersionName}.jar")

        // Copy CLI script to package - this was missing!
        val cliScriptFile = layout.buildDirectory.file("scripts/manga-combiner-cli").get().asFile
        if (!cliScriptFile.exists()) {
            throw GradleException("CLI script file not found: ${cliScriptFile.absolutePath}. Run 'createCliScript' task first.")
        }
        cliScriptFile.copyTo(File(usrBinDir, "manga-combiner-cli"))
        logger.lifecycle("Copied CLI script: ${cliScriptFile.name} -> ${usrBinDir}/manga-combiner-cli")

        // Ensure the CLI script is executable in the package
        File(usrBinDir, "manga-combiner-cli").setExecutable(true)

        // Create control file for DEB package
        File(debianDir, "control").writeText("""Package: manga-combiner-cli
Version: ${appVersionName}
Section: utils
Priority: optional
Architecture: all
Depends: default-jre | openjdk-17-jre | openjdk-18-jre | openjdk-19-jre | openjdk-20-jre | openjdk-21-jre
Maintainer: MangaCombiner <bsikar@tuta.io>
Description: MangaCombiner Command Line Interface
 A command-line tool for downloading and combining manga chapters.
 This package provides the 'manga-combiner-cli' command for system-wide use.
 .
 Usage: manga-combiner-cli [options]
 .
 Requires Java 17 or higher to run.
Homepage: https://github.com/bsikar/simple-manga-combiner
""")

        // Create postinstall script with proper permissions
        val postinstFile = File(debianDir, "postinst")
        postinstFile.writeText("""#!/bin/bash
set -e

# Verify the CLI script was installed correctly
if [ ! -f "/usr/bin/manga-combiner-cli" ]; then
    echo "Error: CLI script not found at /usr/bin/manga-combiner-cli" >&2
    exit 1
fi

# Ensure CLI script is executable
chmod +x /usr/bin/manga-combiner-cli

# Verify JAR file exists
if [ ! -f "/usr/share/manga-combiner/manga-combiner-cli-${appVersionName}.jar" ]; then
    echo "Error: CLI JAR not found at /usr/share/manga-combiner/manga-combiner-cli-${appVersionName}.jar" >&2
    exit 1
fi

# Update command database
if command -v update-command-not-found >/dev/null 2>&1; then
    update-command-not-found || true
fi

echo "MangaCombiner CLI installed successfully. Use: manga-combiner-cli --help"
exit 0
""")
        // Set correct permissions for maintainer scripts (0755)
        ProcessBuilder("chmod", "755", postinstFile.absolutePath).start().waitFor()

        // Create preremove script with proper permissions
        val prermFile = File(debianDir, "prerm")
        prermFile.writeText("""#!/bin/bash
set -e
# Cleanup before removal
echo "Removing MangaCombiner CLI..."
exit 0
""")
        // Set correct permissions for maintainer scripts (0755)
        ProcessBuilder("chmod", "755", prermFile.absolutePath).start().waitFor()

        // Create postremove script to clean up
        val postrmFile = File(debianDir, "postrm")
        postrmFile.writeText("""#!/bin/bash
set -e
# Update command database after removal
if command -v update-command-not-found >/dev/null 2>&1; then
    update-command-not-found || true
fi
echo "MangaCombiner CLI removed successfully"
exit 0
""")
        ProcessBuilder("chmod", "755", postrmFile.absolutePath).start().waitFor()

        logger.lifecycle("DEB package structure created successfully")
        logger.lifecycle("Package contents:")
        baseDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                logger.lifecycle("  ${file.relativeTo(baseDir)} (${if (file.canExecute()) "executable" else "regular file"})")
            }
        }
    }

    // Build the DEB package using dpkg-deb
    workingDir = packageDir.get().asFile
    commandLine = listOf("dpkg-deb", "--build", ".", debFile.get().asFile.absolutePath)

    // Ensure distributions directory exists
    doFirst {
        debFile.get().asFile.parentFile.mkdirs()
    }

    doLast {
        logger.lifecycle("DEB package created successfully: ${debFile.get().asFile.absolutePath}")
        logger.lifecycle("Install with: sudo dpkg -i ${debFile.get().asFile.name}")
    }
}

/**
 * Creates a Windows CLI executable and installer using jpackage.
 * This creates a native Windows .exe that doesn't require users to have Java installed.
 */
tasks.register<Exec>("packageCliWindows") {
    group = "distribution"
    description = "Creates Windows CLI executable and MSI installer (Windows only)"
    dependsOn("cliJar")

    val windowsOutputDir = layout.buildDirectory.dir("cli-windows")

    inputs.files(tasks.named("cliJar"))
    outputs.dir(windowsOutputDir)

    // Only run on Windows
    onlyIf {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        if (!isWindows) {
            logger.warn("Windows CLI packaging can only run on Windows. Skipping on ${System.getProperty("os.name")}")
            return@onlyIf false
        }

        try {
            val process = ProcessBuilder("jpackage", "--version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val available = process.waitFor() == 0
            if (!available) {
                logger.warn("jpackage not found. Ensure you're using Java 17+ JDK")
            }
            available
        } catch (e: Exception) {
            logger.warn("jpackage not found. Ensure you're using Java 17+ JDK")
            false
        }
    }

    doFirst {
        // Clean output directory
        windowsOutputDir.get().asFile.deleteRecursively()
        windowsOutputDir.get().asFile.mkdirs()
    }

    // Create Windows executable and MSI installer
    workingDir = layout.buildDirectory.get().asFile
    commandLine = listOf(
        "jpackage",
        "--input", "libs",
        "--name", "manga-combiner-cli",
        "--main-jar", "manga-combiner-cli-${appVersionName}.jar",
        "--type", "msi",
        "--dest", "cli-windows",
        "--app-version", appVersionName,
        "--vendor", "MangaCombiner",
        "--description", "MangaCombiner Command Line Interface",
        "--win-console",
        "--win-dir-chooser",
        "--win-menu",
        "--win-shortcut"
    )
}

/**
 * Creates a Windows CLI executable only (no installer) using jpackage.
 * This creates a portable Windows .exe that includes the Java runtime.
 */
tasks.register<Exec>("packageCliWindowsPortable") {
    group = "distribution"
    description = "Creates portable Windows CLI executable (Windows only)"
    dependsOn("cliJar")

    val windowsPortableDir = layout.buildDirectory.dir("cli-windows-portable")

    inputs.files(tasks.named("cliJar"))
    outputs.dir(windowsPortableDir)

    // Only run on Windows
    onlyIf {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        if (!isWindows) {
            logger.warn("Windows CLI packaging can only run on Windows. Skipping on ${System.getProperty("os.name")}")
            return@onlyIf false
        }

        try {
            val process = ProcessBuilder("jpackage", "--version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    doFirst {
        // Clean output directory
        windowsPortableDir.get().asFile.deleteRecursively()
        windowsPortableDir.get().asFile.mkdirs()
    }

    // Create portable Windows application directory
    workingDir = layout.buildDirectory.get().asFile
    commandLine = listOf(
        "jpackage",
        "--input", "libs",
        "--name", "manga-combiner-cli",
        "--main-jar", "manga-combiner-cli-${appVersionName}.jar",
        "--type", "app-image",
        "--dest", "cli-windows-portable",
        "--app-version", appVersionName,
        "--vendor", "MangaCombiner",
        "--description", "MangaCombiner Command Line Interface",
        "--win-console"
    )
}

/**
 * Generates a Kotlin source file containing the application version at build time.
 * This ensures version consistency across all build artifacts and runtime components.
 */
val generateVersionFile by tasks.register("generateVersionFile") {
    val outputDir = project.layout.buildDirectory.get().dir("generated/version/commonMain/com/mangacombiner/util")
    val outputFile = outputDir.file("AppVersion.kt")
    outputs.file(outputFile)
    group = "build"
    description = "Generates a compile-time version file for consistent version reporting across platforms"

    doLast {
        outputDir.asFile.mkdirs()
        outputFile.asFile.writeText("""
        package com.mangacombiner.util

        /**
         * An object that holds the application's version name, generated at build time.
         * This ensures consistent versioning across CLI, desktop, and Android builds.
         * 
         * @property NAME The semantic version string (e.g., "1.1.0")
         */
        object AppVersion {
            const val NAME = "$appVersionName"
            const val CODE = $appVersionCode
            const val BUILD_TIME = "${System.currentTimeMillis()}"
        }
        """.trimIndent())
    }
}

// Ensure version file generation occurs before Kotlin compilation tasks
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateVersionFile)
}

// Configure clean task to remove generated files
tasks.named("clean") {
    doLast {
        project.layout.buildDirectory.get().dir("generated").asFile.deleteRecursively()
        project.layout.buildDirectory.get().dir("scripts").asFile.deleteRecursively()
    }
}
