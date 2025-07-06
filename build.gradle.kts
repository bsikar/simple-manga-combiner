import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

val mainClassName = "com.mangacombiner.MangaCombinerApplicationKt"

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.spring)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.shadow.jar)
    alias(libs.plugins.compose.plugin)

    // Corrected Detekt version to be compatible with Kotlin 1.9.23
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
}

// This block configures Detekt to use your custom settings.
detekt {
    config.setFrom(files("detekt.yml"))
    buildUponDefaultConfig = true
}

group = "com.mangacombiner"
version = "1.1.0"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    // Spring Boot
    implementation(libs.spring.boot.starter)

    // Compose for Desktop UI
    implementation(compose.desktop.currentOs)

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
    implementation(libs.ktor.io.jvm)


    // WebP image support (using TwelveMonkeys library)
    implementation(libs.imageio.webp)

    // Detekt Formatting Rules
    detektPlugins(libs.detekt.formatting)

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

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// The main class is explicitly configured for Spring Boot tasks.
springBoot {
    mainClass.set(mainClassName)
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("cli-executable")
    manifest {
        attributes["Main-Class"] = mainClassName
    }
}

compose.desktop {
    application {
        mainClass = mainClassName
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "MangaCombiner"
            packageVersion = project.version.toString()
            description = "A tool to download and combine manga chapters."
            vendor = "MangaCombiner"
        }
    }
}
