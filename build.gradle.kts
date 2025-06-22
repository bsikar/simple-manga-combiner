import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.file.DuplicatesStrategy

plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "com.mangacombiner" 
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // kotlinx-cli for parsing command line arguments
    implementation("org.jetbrains.kotlinx:kotlinx-cli-jvm:0.3.6")
    // Zip4j for handling zip/cbz files
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    // Kotlin Coroutines for parallel processing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0")
    // Junrar for handling cbr (RAR) files
    implementation("com.github.junrar:junrar:7.5.3")
    
    testImplementation(kotlin("test"))
}

// Use a Java toolchain to ensure both Java and Kotlin compilers use the same JDK version.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

tasks.test {
    useJUnitPlatform()
}

application {
    // Updated to the new fully qualified name of your main class
    mainClass.set("com.mangacombiner.MainKt")
}

// This task creates a fat JAR that includes all dependencies.
tasks.jar {
    manifest {
        // Updated here as well for the runnable JAR manifest
        attributes["Main-Class"] = "com.mangacombiner.MainKt"
    }
    // Add a duplicates strategy to handle duplicate files from dependencies.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    configurations["runtimeClasspath"].forEach { file ->
        from(zipTree(file.absoluteFile))
    }
}
