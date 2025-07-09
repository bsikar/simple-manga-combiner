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
                implementation(libs.kotlinx.cli)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.plugins)
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
                implementation("androidx.activity:activity-compose:1.9.0")
                api(libs.koin.android)
                implementation(libs.ktor.client.cio)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                api(libs.koin.jvm)
                implementation(libs.ktor.client.cio)
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
        versionCode = 2
        versionName = "2.0"
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
            packageVersion = "2.0.0"
            description = "A tool to download and combine manga chapters."
            vendor = "MangaCombiner"
        }
    }
}

// This single block replaces the two old ones.
tasks.register<ShadowJar>("cliJar") {
    group = "build"
    description = "Assembles the CLI fat JAR"

    archiveBaseName.set("manga-combiner-cli")
    archiveClassifier.set("")
    archiveVersion.set("2.0.0")

    manifest {
        attributes["Main-Class"] = "com.mangacombiner.desktop.CliRunnerKt"
    }

    from(kotlin.targets.getByName("desktop").compilations.getByName("main").output)
    from(project.configurations.named("desktopRuntimeClasspath"))

    mergeServiceFiles()
}

