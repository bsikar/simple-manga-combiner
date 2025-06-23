# Manga Combiner

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)](https://github.com/YOUR_USERNAME/YOUR_REPOSITORY)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A powerful and efficient command-line tool written in Kotlin for downloading, syncing, and managing digital manga archives. This tool can fetch entire manga series from supported websites or update your local `.cbz` files with new chapters, ensuring your collection is always complete. It can output to both `.cbz` and `.epub` formats.

## Features

- **Download to CBZ or EPUB**: Provide a URL to a manga series and download all its chapters into a single, organized archive.
- **Convert CBZ to EPUB**: Easily convert your existing `.cbz` files to `.epub` for better Table of Contents support on e-readers.
- **Sync Local Archives**: Compare a local `.cbz` file against its online source and download only the missing chapters.
- **Metadata Generation**: Automatically creates `ComicInfo.xml` for CBZ and a navigation TOC for EPUB.
- **Concurrent Operations**: Utilizes Kotlin Coroutines to download chapters and images in parallel for maximum speed.
- **Cross-Platform**: Runs on any system with a Java Virtual Machine (JVM).

## Requirements

- [JDK 11](https://www.oracle.com/java/technologies/javase/jdk11-archive-downloads.html) or newer.

## Building the Tool

The project includes a Gradle wrapper, so you don't need to install Gradle manually.

1.  **Clone the repository:**
    ```sh
    git clone [https://github.com/bsikar/simple-manga-combiner](https://github.com/bsikar/simple-manga-combiner)
    cd simple-manga-combiner
    ```

2.  **Build the executable JAR:**
    - On macOS/Linux:
      ```sh
      ./gradlew clean shadowJar
      ```
    - On Windows:
      ```sh
      .\gradlew.bat clean shadowJar
      ```

3.  After a successful build, the executable "fat" JAR will be located at `build/libs/MangaCombiner-1.0.0.jar`.

## Running the Application

There are two primary ways to run the application:

### 1. For Development (Recommended)

Using the Gradle `run` task is the fastest way to run the application. All application arguments must be passed within a single string using the `--args` flag.

- **On macOS/Linux:**
  ```sh
  ./gradlew run --args='<arguments>'
```

- **On Windows:**
  ```sh
  .\gradlew.bat run --args='<arguments>'
  ```

### 2\. From the Executable JAR

Run the final JAR file from anywhere. This is ideal for "production" use.

```sh
java -jar build/libs/MangaCombiner-1.0.0.jar <source> [options...]
```

## Usage Details

### Arguments

| Argument | Description                                                                                |
| :------- | :----------------------------------------------------------------------------------------- |
| `source` | **(Required)** The source to operate on. This can be a manga series URL, a path to a single `.cbz` file, or a glob pattern (e.g., `"*.cbz"`). |

### Options

| Option                | Description                                                                    | Default |
| :-------------------- | :----------------------------------------------------------------------------- | :------ |
| `--update <file>`     | Path to a local CBZ file to update with missing chapters (CBZ only).           | `null`  |
| `-t`, `--title`       | Provide a custom title for the manga series.                                   | `null`  |
| `--format <type>`     | The output format for new files (`cbz` or `epub`).                             | `cbz`   |
| `-e`, `--exclude <slugs>`| A single string of space-separated chapter slugs to exclude.                  | `null`  |
| `-f`, `--force`       | Force overwrite of existing `ComicInfo.xml` when updating CBZ metadata.        | `false` |
| `-w`, `--workers`     | Number of concurrent image downloads per chapter.                              | `10`    |
| `--chapter-workers`   | Number of concurrent chapters to download during a download or sync operation. | `4`     |
| `--batch-workers`     | Number of local files to process concurrently in batch mode.                   | `4`     |
| `--debug`             | Enable detailed debug logging for troubleshooting.                             | `false` |

-----

## Examples

#### 1\. Download a Series as an EPUB

- **Using Gradle:**
  ```sh
  ./gradlew run --args='"[https://mangasite.com/manga/my-awesome-manga](https://mangasite.com/manga/my-awesome-manga)" --format epub'
  ```
- **Using JAR:**
  ```sh
  java -jar build/libs/MangaCombiner-1.0.0.jar "[https://mangasite.com/manga/my-awesome-manga](https://mangasite.com/manga/my-awesome-manga)" --format epub
  ```

#### 2\. Convert an Existing CBZ to EPUB

- **Using Gradle:**
  ```sh
  ./gradlew run --args='"path/to/my/comics/My-Manga.cbz" --format epub'
  ```
- **Using JAR:**
  ```sh
  java -jar build/libs/MangaCombiner-1.0.0.jar "path/to/my/comics/My-Manga.cbz" --format epub
  ```

#### 3\. Update the TOC on a CBZ File

This updates the `ComicInfo.xml` inside a CBZ, overwriting any existing metadata.

- **Using Gradle:**
  ```sh
  ./gradlew run --args='"path/to/My-Manga.cbz" --force'
  ```
- **Using JAR:**
  ```sh
  java -jar build/libs/MangaCombiner-1.0.0.jar "path/to/My-Manga.cbz" --force
  ```

#### 4\. Sync a CBZ with its Online Source

Note: The sync feature only works with the CBZ format.

- **Using Gradle:**
  ```sh
  ./gradlew run --args='"[https://mangasite.com/manga/one-punch-man](https://mangasite.com/manga/one-punch-man)" --update "path/to/One-Punch Man.cbz"'
  ```
- **Using JAR:**
  ```sh
  java -jar build/libs/MangaCombiner-1.0.0.jar "[https://mangasite.com/manga/one-punch-man](https://mangasite.com/manga/one-punch-man)" --update "path/to/One-Punch Man.cbz"
  ```

## License

This project is licensed under the MIT License.