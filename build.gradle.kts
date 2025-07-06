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
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow.jar)
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
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }

        // Configure ShadowJar for CLI within the desktop target
        tasks.withType<ShadowJar>().configureEach {
            archiveClassifier.set("")
            manifest {
                attributes["Main-Class"] = "com.mangacombiner.desktop.CliRunnerKt"
            }
            mergeServiceFiles()
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.koin.core)
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
                implementation("com.google.android.material:material:1.12.0")
                implementation("androidx.appcompat:appcompat:1.6.1")
                implementation("androidx.activity:activity-compose:1.9.0")
                api(libs.koin.android)
                implementation(libs.ktor.client.cio)
                implementation(compose.ui)
                implementation(compose.uiTooling)
                implementation(compose.foundation)
                implementation(compose.material)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                api(libs.koin.jvm)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.io.jvm)
                implementation(libs.imageio.webp)
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

// Desktop JAR (plain classes)
val desktopJarTask = tasks.named<org.gradle.jvm.tasks.Jar>("desktopJar")

// CLI Fat JAR: includes desktop classes + dependencies
tasks.register<ShadowJar>("cliJar") {
    group = "build"
    description = "Assembles the CLI fat JAR including desktop code and runtime dependencies"
    archiveBaseName.set("manga-combiner-cli")
    archiveVersion.set("1.0.0")
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "com.mangacombiner.desktop.CliRunnerKt"
    }

    // Include compiled desktop classes
    from(desktopJarTask.map { zipTree(it.archiveFile.get().asFile) })

    // Include runtime JARs from desktop
    val runtimeClasspath = project.configurations.getByName("desktopRuntimeClasspath")
    from(
        runtimeClasspath.files
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    )

    mergeServiceFiles()
}

// Task to build both desktop and CLI jars
tasks.register("allJars") {
    group = "build"
    description = "Builds both the desktop and CLI JARs"
    dependsOn("desktopJar", "cliJar")
}

// Task to build Android debug APK
tasks.register("apkDebug") {
    group = "build"
    description = "Assembles the Android debug APK"
    dependsOn("assembleDebug")
}

// Task to build all artifacts (desktop JARs, CLI JAR, Android APK)
tasks.register("allArtifacts") {
    group = "build"
    description = "Builds desktop JARs, CLI JAR, and Android debug APK"
    dependsOn("desktopJar", "cliJar", "assembleDebug")
}
