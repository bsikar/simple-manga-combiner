# Manga Combiner KMP

[![Build and Release Packages](https://github.com/bsikar/simple-manga-combiner/actions/workflows/release.yml/badge.svg)](https://github.com/bsikar/simple-manga-combiner/actions/workflows/release.yml)

Manga Combiner is a cross-platform application built with Kotlin Multiplatform for downloading manga, managing local comic archives, and packaging chapters into EPUB files. It offers both a user-friendly graphical interface (GUI) for Desktop (Windows, macOS, Linux) and Android, and a powerful command-line interface (CLI) for automation and advanced use.

---

## ✨ Features

### 🖥️ GUI (Desktop & Android)

* **Intuitive Search**: Easily find manga series from online sources.
* **Powerful Download Queue**: Manage all your downloads with pause, resume, edit, and re-order capabilities.
* **Comprehensive Settings**: Customize themes, default output locations, network proxies, download workers, and more.
* **Cache Management**: View, manage, and delete cached series and chapters to save space.
* **WebDAV Browser**: Connect to your WebDAV server to browse and download files directly.
* **Local File Sync**: Update existing EPUB files with new chapters from the web or modify their contents.

### ⚡ CLI (Windows, macOS, Linux)

* **Feature-Rich**: A powerful command-line tool with extensive options for scripting and automation.
* **Batch Operations**: Scrape entire genre or list pages to download all series in one command.
* **Image Optimization**: Control image quality, resize images, and use presets (`fast`, `quality`, `small-size`) to balance speed and file size.
* **Advanced Cache Control**: Force re-downloads, clean up temporary files automatically, or surgically delete specific cached series.
* **Flexible & Scriptable**: Designed for power users who want to automate their manga archival process.

---

## 📦 Installation & Downloads

You can find the latest installers and packages on the [**Releases Page**](https://github.com/bsikar/simple-manga-combiner/releases).

### File Descriptions

| File Type                               | Platform              | Purpose & Installation                             |
| --------------------------------------- |-----------------------| -------------------------------------------------- |
| `MangaCombiner-*.msi`                   | Windows               | Desktop GUI installer.                             |
| `MangaCombiner-*.dmg`                   | macOS                 | Desktop GUI installer.                             |
| `MangaCombiner-*.deb`                   | Debian/Ubuntu         | Desktop GUI installer.                             |
| `manga-combiner-kmp-release.apk`        | Android               | Direct installation (sideloading).                 |
| `manga-combiner-cli-*.jar`              | Windows, macOS, Linux | Cross-platform CLI (requires Java 17+).            |
| `manga-combiner-cli-*.msi`              | Windows               | CLI installer for system-wide use.                 |
| `manga-combiner-cli-*.deb`              | Debian/Ubuntu         | CLI installer for system-wide use.                 |
| `manga-combiner-cli-portable-*.zip`     | Windows               | Portable CLI with bundled Java runtime.            |

### Installation Instructions

**CLI - System Installation (Debian/Ubuntu):**
```bash
sudo dpkg -i manga-combiner-cli-*.deb
sudo apt-get install -f  # Install dependencies if needed
manga-combiner-cli --help
````

**Desktop - System Installation (Debian/Ubuntu):**

```bash
sudo dpkg -i MangaCombiner-*.deb
sudo apt-get install -f  # Install dependencies if needed
# Launch from applications menu or command line
```

**CLI - System Installation (Windows):**

```cmd
# Double-click the CLI MSI installer or run:
msiexec /i manga-combiner-cli-*.msi
# Then use from anywhere:
manga-combiner-cli --help
```

**CLI - Portable (Windows):**

```cmd
# Extract the portable zip file and run:
.\manga-combiner-cli\manga-combiner-cli.exe --help
```

**CLI - Portable JAR (Any OS with Java):**

```bash
java -jar manga-combiner-cli-*.jar --help
```

### System Requirements

* **Desktop Apps**: Platform-specific (includes bundled Java runtime).
* **CLI JAR**: Java 17 or higher (any operating system).
* **CLI DEB**: Java 17+ (automatically installed as a dependency on Debian/Ubuntu).
* **CLI Windows**: No Java required (bundled runtime included).
* **Android**: Android 8.0 (API level 26) or higher.
* **Linux DEB**: Debian 10+ or Ubuntu 18.04+ (tested on Debian 12 bookworm).

-----

## 🚀 Usage

### GUI

Simply launch the application and use the navigation rail on the left to switch between **Search**, **Download**, **Queue**, **Cache**, and **Settings**.

### CLI

The CLI offers a wide range of options for automation.

#### Basic Examples

```bash
# Download a single series to your Downloads folder
manga-combiner-cli --source https://example.com/manga/one-piece

# Search for a series and download all results, optimizing for small file sizes
manga-combiner-cli --source "attack on titan" --search --download-all --preset small-size

# Batch download all series from a list page with 3 concurrent series downloads
manga-combiner-cli --source https://example.com/genre/action --scrape --batch-workers 3

# Delete cached files for 'naruto' to save space
manga-combiner-cli --delete-cache --remove "naruto"
```

#### Full CLI Options

```text
manga-combiner-cli v1.1.6

USAGE:
  manga-combiner [OPTIONS] --source <URL|FILE|QUERY>...

INPUT OPTIONS:
  -s, --source <URL|FILE|QUERY>    Source URL, local EPUB file, or search query (can be used multiple times)

DISCOVERY & SEARCH:
  --search                         Search for manga by name and display results
  --scrape                         Batch download all series from a list/genre page
  --download-all                   Download all search results (use with --search)

OUTPUT OPTIONS:
  --format <epub>                  Output format (default: epub)
  -t, --title <NAME>               Custom title for output file
  -o, --output <DIR>               Output directory (default: Downloads)

DOWNLOAD BEHAVIOR:
  -f, --force                      Force overwrite existing files
  --redownload-existing            Alias for --force
  --skip-existing                  Skip if output file exists (good for batch)
  --update                         Update an existing EPUB with new chapters
  -e, --exclude <SLUG>             Exclude chapters by slug (e.g., 'chapter-4.5'). Can be used multiple times.
  --delete-original                Delete source file after successful conversion (local files only)

CACHE MANAGEMENT:
  --ignore-cache                   Force re-download all chapters
  --clean-cache                    Delete temp files after successful download to save disk space
  --refresh-cache                  Force refresh scraped series list (with --scrape)
  --delete-cache                   Delete cached downloads and exit. Use with --keep or --remove for selective deletion.
  --keep <PATTERN>                 Keep matching series when deleting cache
  --remove <PATTERN>               Remove matching series when deleting cache
  --cache-dir <DIR>                Custom cache directory

IMAGE OPTIMIZATION:
  --optimize                       Enable image optimization (slower but smaller files)
  --max-image-width <PIXELS>       Resize images to max width
  --jpeg-quality <1-100>           JPEG compression quality
  --preset <NAME>                  Use preset: fast, quality, small-size

PERFORMANCE:
  -w, --workers <N>                Concurrent image downloads per series (default: 4)
  -bw, --batch-workers <N>         Concurrent series downloads (default: 1)

NETWORK:
  -ua, --user-agent <NAME>         Browser to impersonate (see --list-user-agents)
  --per-worker-ua                  Random user agent per worker
  --proxy <URL>                    HTTP proxy (e.g., http://localhost:8080)

SORTING:
  --sort-by <METHOD>               Sort order for batch downloads (see --list-sort-options)

UTILITY:
  --dry-run                        Preview actions without downloading
  --debug                          Enable verbose logging
  --list-user-agents               Show available user agents
  --list-sort-options              Show available sort methods
  -v, --version                    Show version information and exit
  --help                           Show this help message
```

-----

## 🛠️ Building from Source

**Prerequisites:**

* JDK 17 or higher

This project uses the Gradle wrapper. Use the `gradlew` script in the root directory for all commands.

* **Run Desktop App**: `./gradlew run`
* **Build Desktop Installer**: `./gradlew packageDistributionForCurrentOS`
* **Build CLI JAR**: `./gradlew cliJar`
* **Build CLI Windows Packages**: `./gradlew packageCliWindows packageCliWindowsPortable` (requires Windows)
* **Build CLI Linux Package**: `./gradlew packageCliDeb` (requires `dpkg-deb`)
* **Build Android App**: `./gradlew assembleRelease`

-----

## 💻 Tech Stack

* **Core**: Kotlin Multiplatform
* **UI**: Compose Multiplatform
* **Networking**: Ktor Client
* **HTML Parsing**: Jsoup
* **Dependency Injection**: Koin
* **Serialization**: Kotlinx Serialization
* **Build System**: Gradle
* **CI/CD**: GitHub Actions
