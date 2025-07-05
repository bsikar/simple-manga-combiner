import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.spring)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.shadow.jar)
    application
}

group = "com.mangacombiner"
version = "1.1.0"

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    // Spring Boot
    implementation(libs.spring.boot.starter)

    // Command-line argument parsing
    implementation(libs.kotlinx.cli)

    // ZIP file handling
    implementation(libs.zip4j)

    // Coroutines for concurrent operations
    implementation(libs.kotlinx.coroutines.core)

    // XML serialization for ComicInfo.xml
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.xmlutil.serialization)

    // HTML parsing for web scraping
    implementation(libs.jsoup)

    // HTTP client for downloading
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.plugins)

    // WebP image support
    implementation(libs.webp.imageio)

    // Testing
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation(libs.kotlin.test)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// FIX: Use the modern compilerOptions DSL instead of the deprecated kotlinOptions
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

application {
    mainClass.set("com.mangacombiner.MangaCombinerApplicationKt")
}

// Configure the shadow jar to be executable
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("executable")
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}
