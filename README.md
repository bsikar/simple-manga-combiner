# Manga Combiner

[](https://github.com/bsikar/simple-manga-combiner) [](https://opensource.org/licenses/MIT)

A powerful and efficient cross-platform tool written in Kotlin for downloading, syncing, and managing digital manga archives. You get:

* **Command-Line Interface (CLI)**: a standalone fat-JAR (`manga-combiner-cli`) that runs on any JVM
* **Desktop GUI**: a Compose for Desktop application with native installers (`.dmg`, `.msi`, `.deb`)
* **Android App**: an installable APK you can build via Gradle

-----

## Features

* **Download to CBZ or EPUB**: fetch entire series from supported sites into a single archive.
* **Convert Local Files**: `.cbz` ↔️ `.epub` conversions with streaming options for low-memory mode.
* **Batch Processing**: process globs (e.g. `*.cbz`) concurrently.
* **Sync Local Archives**: compare a `.cbz` against its source URL, download only missing chapters.
* **Server-Friendly**: sequential chapter downloads with delays, retries, and polite headers.
* **Parallel Image Fetching**: Kotlin Coroutines for intra-chapter concurrency.
* **Metadata Generation**: `ComicInfo.xml` for CBZ, full TOC for EPUB.
* **Multi-Target**: CLI JAR, Desktop GUI, and Android APK (debug & release).

-----

## Requirements

* **JDK 17+**
* **Android SDK** (for building the Android app)

-----

## Getting Started

First, clone the repository and navigate into the directory:

```sh
git clone https://github.com/bsikar/simple-manga-combiner.git
cd simple-manga-combiner
```

> On Windows, use `gradlew.bat` instead of `./gradlew` for all commands.

-----

## How to Build and Run

You can build and run the Manga Combiner for the command line, as a desktop application, or on an Android device.

### 1\. Command-Line Interface (CLI)

The CLI is a self-contained "fat-JAR" that can run on any system with a Java 17+ runtime.

#### Build the CLI

Run the following Gradle task to create the CLI artifact:

```sh
./gradlew cliJar
```

The output will be located at `build/libs/manga-combiner-cli-2.0.0.jar`.

#### Run the CLI

Execute the JAR from your terminal using the following format:

```sh
java -jar build/libs/manga-combiner-cli-2.0.0.jar <source> [options]
```

**Example:** Download a series as an EPUB.

```sh
java -jar build/libs/manga-combiner-cli-2.0.0.jar "https://example.com/manga/awesome-series" --format epub
```

-----

### 2\. Desktop Application

The desktop application provides a full graphical user interface. You can run it directly from a JAR or install it using a native package.

#### Build the Desktop App

You have two options for building the desktop app:

* **Runnable JAR**: To create a single, executable JAR file, run:

  ```sh
  ./gradlew packageUberJarForCurrentOS
  ```

  The output will be in `build/compose/jars/`.

* **Native Installer**: To create a native installer for your operating system (`.dmg` for macOS, `.msi` for Windows, `.deb` for Debian/Ubuntu), run:

  ```sh
  ./gradlew packageDistributionForCurrentOS
  ```

  The installer will be located in `build/compose/binaries/`.

#### Run the Desktop App

* **From JAR**: Execute the JAR file directly.
  ```sh
  # Replace with the actual JAR name from build/compose/jars/
  java -jar build/compose/jars/manga-combiner-kmp-desktop-2.0.0.jar
  ```
* **From Installer**: Simply run the native installer and launch the application as you would any other desktop program.

-----

### 3\. Android Application

You can build a standard Android APK to install on a device or emulator.

#### Build the Android App

Run the following Gradle task to assemble a debug APK:

```sh
./gradlew assembleDebug
```

The output will be located at `build/outputs/apk/debug/app-debug.apk`.

#### Install and Run the Android App

You can install the app using the Android Debug Bridge (`adb`).

1.  **Install the APK**:

    ```sh
    adb install -r build/outputs/apk/debug/app-debug.apk
    ```

2.  **Start the app**:

    ```sh
    adb shell am start -n com.mangacombiner/.android.MainActivity
    ```

Alternatively, you can open the project in Android Studio and run it directly on a connected device or emulator.

-----

## CLI Options

| Flag                       | Description                                      | Default   |
| -------------------------- | ------------------------------------------------ | --------- |
| `<source>`                 | URL, local file path, or glob (`"*.cbz"`)        | **req’d** |
| `--format <cbz\|epub>`     | Output format                                    | `epub`    |
| `--update <file>`          | Local CBZ to sync against URL                    | `none`    |
| `-t, --title <name>`       | Override inferred manga title                    | `none`    |
| `-e, --exclude <slug>`     | Chapter slug to skip (multi-use)                 | `none`    |
| `-w, --workers <n>`        | Parallel image downloads per chapter             | `4`       |
| `-f, --force`              | Overwrite existing output                        | `false`   |
| `--delete-original`        | Delete source on success                         | `false`   |
| `--batch-workers <n>`      | Concurrent files for glob processing             | `4`       |
| `--skip-if-target-exists`  | In batch mode, skip if output exists             | `false`   |
| `--low-storage-mode`       | Memory-saving streaming conversion               | `false`   |
| `--ultra-low-storage-mode` | Aggressive low-memory streaming                  | `false`   |
| `--true-dangerous-mode`    | **DANGEROUS** in-place transforms                | `false`   |
| `--debug`                  | Verbose debug logging                            | `false`   |

-----

## License

MIT © [bsikar](https://github.com/bsikar)
