import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.plugin)
    alias(libs.plugins.kotlin.serialization) // <-- THIS IS THE NEWLY ADDED PLUGIN
    // Make the shadow plugin available but don't apply it to the root project yet.
    alias(libs.plugins.shadow.jar) apply false
}

val mainClassName = "com.mangacombiner.desktop.DesktopAppKt"

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    jvm("desktop") {
        // Now, apply the shadow plugin to this specific target.
        plugins.apply("com.github.johnrengelman.shadow")

        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }

        // Configure the shadowJar task, which is now correctly in scope.
        tasks.withType<ShadowJar>().configureEach {
            archiveClassifier.set("cli-executable")
            manifest {
                attributes["Main-Class"] = "com.mangacombiner.desktop.CliRunnerKt"
            }
            mergeServiceFiles()
        }
    }

    // Define the source sets and their dependencies
    sourceSets {
        val commonMain by getting {
            dependencies {
                // Koin for Dependency Injection
                api(libs.koin.core)

                // Kotlinx libraries
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.kotlinx.cli)

                // Ktor for HTTP
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.plugins)

                // Parsing & Serialization
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
                implementation("com.google.android.material:material:1.12.0")
                implementation("androidx.appcompat:appcompat:1.6.1")
                implementation("androidx.activity:activity-compose:1.9.0")

                // Koin for Android
                api(libs.koin.android)

                // Ktor engine for Android
                implementation(libs.ktor.client.cio)

                // Compose UI dependencies for Android
                implementation(compose.ui)
                implementation(compose.uiTooling)
                implementation(compose.foundation)
                implementation(compose.material)
            }
        }

        val desktopMain by getting {
            dependencies {
                // Compose for Desktop
                implementation(compose.desktop.currentOs)

                // Koin for JVM
                api(libs.koin.jvm)

                // Ktor engine for Desktop
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.io.jvm)

                // ImageIO for WebP support
                implementation(libs.imageio.webp)
            }
        }
    }
}

// Android-specific configuration
android {
    namespace = "com.mangacombiner"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mangacombiner"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.13"
    }
}

// Compose Desktop configuration for the GUI app
compose.desktop {
    application {
        mainClass = mainClassName
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "MangaCombiner"
            packageVersion = "1.1.0"
            description = "A tool to download and combine manga chapters."
            vendor = "MangaCombiner"
        }
    }
}
