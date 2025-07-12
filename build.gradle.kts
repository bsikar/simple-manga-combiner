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

                implementation(libs.kotlinx.coroutines.core)
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
                // This is the correct way to import the AndroidX BOM for this KMP setup.
                // The deprecation warning is a known issue with the Kotlin Gradle Plugin
                // and can be safely ignored until a new syntax is provided in a future version.
                implementation(platform("androidx.compose:compose-bom:2024.06.00"))
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
                implementation(libs.ktor.io.jvm) // This is the required dependency
                implementation(libs.imageio.webp)
                implementation("org.slf4j:slf4j-simple:2.0.13")
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
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "MangaCombiner"
            packageVersion = appVersionName
            description = "A tool to download and combine manga chapters."
            vendor = "MangaCombiner"

            // Add icon configuration
            macOS {
                iconFile.set(project.file("src/desktopMain/resources/icon_desktop.png"))
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icon_desktop.png"))
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon_desktop.png"))
            }
        }
    }
}

tasks.register<ShadowJar>("cliJar") {
    group = "build"
    description = "Assembles the CLI fat JAR"

    archiveBaseName.set("manga-combiner-cli")
    archiveClassifier.set("")
    archiveVersion.set(appVersionName)

    manifest {
        attributes["Main-Class"] = "com.mangacombiner.desktop.CliRunnerKt"
    }

    from(kotlin.targets.getByName("desktop").compilations.getByName("main").output)
    from(project.configurations.named("desktopRuntimeClasspath"))

    mergeServiceFiles()
}
val generateVersionFile by tasks.register("generateVersionFile") {
    val outputDir = project.layout.buildDirectory.get().dir("generated/version/commonMain/com/mangacombiner/util")
    val outputFile = outputDir.file("AppVersion.kt")
    outputs.file(outputFile)
    group = "build"
    description = "Generates a file with the app version."

    doLast {
        outputDir.asFile.mkdirs()
        outputFile.asFile.writeText("""
        package com.mangacombiner.util

        /**
         * An object that holds the application's version name, generated at build time.
         */
        object AppVersion {
            const val NAME = "$appVersionName"
        }
        """.trimIndent())
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateVersionFile)
}
