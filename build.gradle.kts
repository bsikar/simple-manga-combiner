import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.file.DuplicatesStrategy

plugins {
    kotlin("jvm") version "2.0.0"
    application
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"
}

group = "com.mangacombiner"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Command-line argument parsing
    implementation("org.jetbrains.kotlinx:kotlinx-cli-jvm:0.3.6")

    // ZIP file handling
    implementation("net.lingala.zip4j:zip4j:2.11.5")

    // Coroutines for concurrent operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.1")

    // XML serialization for ComicInfo.xml
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.1")
    implementation("io.github.pdvrieze.xmlutil:serialization-jvm:0.90.2")

    // HTML parsing for web scraping
    implementation("org.jsoup:jsoup:1.18.1")

    // HTTP client for downloading
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")

    // WebP image support
    implementation("org.sejda.imageio:webp-imageio:0.1.6")

    testImplementation(kotlin("test"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("com.mangacombiner.MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.mangacombiner.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
