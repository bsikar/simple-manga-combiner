# Manga Combiner

[![GitHub Repo](https://img.shields.io/badge/repo-bsikar%2Fsimple--manga--combiner-blue)](https://github.com/bsikar/simple-manga-combiner)  [![License: MIT](https://img.shields.io/badge/License-MIT-green)](https://opensource.org/licenses/MIT)

A powerful and efficient cross‑platform tool written in Kotlin for downloading, syncing, and managing digital manga archives. You get:

* **Command‑Line Interface (CLI)**: a standalone fat‑JAR (`manga‑combiner‑cli`) that runs on any JVM
* **Desktop GUI**: a Compose for Desktop application (`manga‑combiner‑kmp‑desktop.jar`)
* **Android App**: an installable APK you can build via Gradle

---

## Features

* **Download to CBZ or EPUB**: fetch entire series from supported sites into a single archive.
* **Convert Local Files**: `.cbz` ↔️ `.epub` conversions with streaming options for low‑memory mode.
* **Batch Processing**: process globs (e.g. `*.cbz`) concurrently.
* **Sync Local Archives**: compare a `.cbz` against its source URL, download only missing chapters.
* **Server‑Friendly**: sequential chapter downloads with delays, retries, and polite headers.
* **Parallel Image Fetching**: Kotlin Coroutines for intra‑chapter concurrency.
* **Metadata Generation**: `ComicInfo.xml` for CBZ, full TOC for EPUB.
* **Multi‑Target**: CLI JAR, Desktop GUI, and Android APK (debug & release).

---

## Requirements

* **JDK 11+**
* **Android SDK** (for building the Android app)

---

## Getting Started

### Clone & Enter

```sh
git clone https://github.com/bsikar/simple-manga-combiner.git
cd simple-manga-combiner
```

### Build All Artifacts

This assembles:

1. **CLI fat‑JAR** (`manga-combiner-cli-1.0.0.jar`)
2. **Desktop JAR** (`manga-combiner-kmp-desktop.jar`)
3. **Android debug APK** (`app-debug.apk`)

```sh
./gradlew clean allArtifacts
```

* **CLI JAR** → `build/libs/manga-combiner-cli-1.0.0.jar`
* **Desktop JAR** → `build/libs/manga-combiner-kmp-desktop.jar`
* **Android APK** → `build/outputs/apk/debug/app-debug.apk`

> On Windows use `gradlew.bat` instead of `./gradlew`.

---

## Running the CLI

```sh
java -jar build/libs/manga-combiner-cli-1.0.0.jar <source> [options]
```

### Common Options

| Flag                       | Description                                                     | Default   |
| -------------------------- | --------------------------------------------------------------- |-----------|
| `<source>`                 | URL, local file path (`.cbz`/`.epub`), or glob (e.g. `"*.cbz"`) | **req’d** |
| `--format <cbz\|epub>`     | Output format                                                   | `epub`    |
| `--update <file>`          | Local CBZ to sync against URL                                   | `none`    |
| `-t, --title <name>`       | Override inferred manga title                                   | `none`    |
| `-e, --exclude <slug>`     | Chapter slug to skip (multi‑use)                                | `none`    |
| `-w, --workers <n>`        | Parallel image downloads per chapter                            | `2`       |
| `-f, --force`              | Overwrite existing output                                       | `false`   |
| `--delete-original`        | Delete source on success                                        | `false`   |
| `--batch-workers <n>`      | Concurrent files for glob processing                            | `4`       |
| `--skip-if-target-exists`  | In batch mode, skip if output exists                            | `false`   |
| `--low-storage-mode`       | Memory‑saving streaming conversion                              | `false`   |
| `--ultra-low-storage-mode` | Aggressive low‑memory streaming                                 | `false`   |
| `--true-dangerous-mode`    | **DANGEROUS** in‑place transforms                               | `false`   |
| `--debug`                  | Verbose debug logging                                           | `false`   |

---

## Running the Desktop GUI

```sh
java -jar build/libs/manga-combiner-kmp-desktop.jar
```

---

## Installing the Android App (Debug)

After building:

```sh
ls build/outputs/apk/debug/app-debug.apk
# → app-debug.apk
```

Use `adb` to install (or install via Android Studio):

```sh
# if adb on PATH
adb install -r build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.mangacombiner/.android.MainActivity
```

Or, if you need to install `adb`, use Homebrew:

```sh
brew install android-platform-tools
```

## Examples

1. **Download new series to EPUB**

   ```sh
   java -jar .../manga-combiner-cli.jar "https://example.com/manga/awesome" --format epub
   ```

2. **Sync local CBZ**

   ```sh
   java -jar .../manga-combiner-cli.jar "https://site.com/manga/opm" --update "One-Punch-Man.cbz"
   ```

3. **Batch convert folder**

   ```sh
   java -jar .../manga-combiner-cli.jar "folder/*.cbz" --format epub
   ```

---

## License

MIT © [bsikar](https://github.com/bsikar)
