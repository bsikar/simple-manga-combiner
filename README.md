# Manga Combiner

[](https://github.com/bsikar/simple-manga-combiner)
[](https://opensource.org/licenses/MIT)

A powerful and efficient command-line tool written in Kotlin for downloading, syncing, and managing digital manga archives. This tool can fetch entire manga series from supported websites or update your local `.cbz` files with new chapters, ensuring your collection is always complete. It can output to both `.cbz` and `.epub` formats and is designed to be respectful to host servers.

## Features

- **Download to CBZ or EPUB**: Provide a URL to a manga series and download all its chapters into a single, organized archive.
- **Convert Local Files**: Easily convert your existing `.cbz` files to `.epub` (or vice-versa).
- **Batch Processing**: Convert an entire folder of archives from one format to another using a glob pattern (e.g., `*.cbz`).
- **Sync Local Archives**: Compare a local `.cbz` file against its online source and download only the missing chapters.
- **Server-Friendly**: Downloads chapters sequentially with built-in delays, automatic retries, and polite headers to avoid overwhelming servers.
- **Efficient & Parallelized**: Utilizes Kotlin Coroutines to download a chapter's images in parallel for speed.
- **Metadata Generation**: Automatically creates `ComicInfo.xml` for CBZ and a navigation TOC for EPUB.
- **Cross-Platform**: Runs on any system with a Java Virtual Machine (JVM).

## Requirements

- [JDK 11](https://www.oracle.com/java/technologies/javase/jdk11-archive-downloads.html) or newer.

## Building the Tool

The project includes a Gradle wrapper, so you don't need to install Gradle manually.

1.  **Clone the repository:**

    ```sh
    git clone https://github.com/bsikar/simple-manga-combiner
    cd simple-manga-combiner
    ```

2.  **Build the executable JAR:**
    This command compiles the code and packages it with all necessary dependencies into a single executable JAR.

    - On macOS/Linux:
      ```sh
      ./gradlew clean shadowJar
      ```
    - On Windows:
      ```sh
      .\gradlew.bat clean shadowJar
      ```

3.  After a successful build, the executable "fat" JAR will be located at `build/libs/simple-manga-combiner-1.0.0-all.jar`.

## Running the Application

There are two primary ways to run the application:

### 1\. For Development (Recommended)

Using the Gradle `run` task is the fastest way to test changes. All application arguments must be passed within a single quoted string using the `--args` flag.

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
java -jar build/libs/simple-manga-combiner-1.0.0-all.jar <source> [options...]
```

## Usage Details

### Argument

| Argument | Description                                                                                                                     |
| :------- | :------------------------------------------------------------------------------------------------------------------------------ |
| `source` | **(Required)** The source to operate on. This can be a manga series URL, a path to a single `.cbz`/`.epub` file, or a glob pattern (e.g., `"*.cbz"`). |

### Options

| Option                        | Description                                                                                         | Default |
| :---------------------------- | :-------------------------------------------------------------------------------------------------- | :------ |
| `--update <file>`             | Path to a local CBZ file to update with missing chapters (source must be a URL).                    | `null`  |
| `-t`, `--title <name>`        | Provide a custom title for the output file, overriding the default.                                 | `null`  |
| `--format <type>`             | The output format for new files or conversions (`cbz` or `epub`).                                   | `cbz`   |
| `-e`, `--exclude <slug>`      | A chapter slug to exclude. Can be used multiple times (e.g. `--exclude ch-1 --exclude ch-2`).       | `none`  |
| `-w`, `--workers <num>`       | Number of concurrent image downloads per chapter. Higher values are faster but less server-friendly.| `2`     |
| `--chapter-workers <num>`     | **(DEPRECATED)** This flag is ignored. Chapter downloads are now sequential to be server-friendly.    | `1`     |
| `--batch-workers <num>`       | Number of local files to process concurrently when using a glob pattern for `source`.               | `4`     |
| `--skip-if-target-exists`     | In batch mode, skip conversion if the target file (e.g., the `.epub`) already exists.               | `false` |
| `--delete-original`           | Delete the original source file(s) after a successful conversion.                                   | `false` |
| `-f`, `--force`               | Force overwrite of the output file if it already exists.                                            | `false` |
| `--debug`                     | Enable detailed debug logging for troubleshooting.                                                  | `false` |
| **Storage-Saving Modes** |                                                                                                     |         |
| `--low-storage-mode`          | Uses less RAM during conversion at the cost of speed. Implies `--delete-original`.                  | `false` |
| `--ultra-low-storage-mode`    | More aggressive streaming for very low memory. Implies `--delete-original`.                         | `false` |
| `--true-dangerous-mode`       | **DANGER\!** Modifies the source file directly during conversion. Any interruption will corrupt it.  | `false` |

-----

## Examples

#### 1\. Download a New Series as an EPUB

This command downloads the series and packages it as `My-Awesome-Manga.epub`.

- **Using Gradle:**
  ```sh
  ./gradlew run --args='"https://mangasite.com/manga/my-awesome-manga" --format epub'
  ```
- **Using JAR:**
  ```sh
  java -jar build/libs/simple-manga-combiner-1.0.0-all.jar "https://mangasite.com/manga/my-awesome-manga" --format epub
  ```

#### 2\. Sync a Local CBZ with its Online Source

This compares your local `One-Punch-Man.cbz` with the source URL and downloads only the chapters you are missing.

- **Using Gradle:**
  ```sh
  ./gradlew run --args='"https://mangasite.com/manga/one-punch-man" --update "path/to/your/One-Punch-Man.cbz"'
  ```
- **Using JAR:**
  ```sh
  java -jar build/libs/simple-manga-combiner-1.0.0-all.jar "https://mangasite.com/manga/one-punch-man" --update "path/to/your/One-Punch-Man.cbz"
  ```

#### 3\. Download a Series, Excluding Certain Chapters

Use the `--exclude` flag multiple times to skip specific chapters.

- **Using Gradle:**
  ```sh
  ./gradlew run --args='"https://mangasite.com/manga/a-certain-manga" --exclude chapter-0 --exclude chapter-99-5'
  ```
- **Using JAR:**
  ```sh
  java -jar build/libs/simple-manga-combiner-1.0.0-all.jar "https://mangasite.com/manga/a-certain-manga" --exclude chapter-0 --exclude chapter-99-5
  ```

#### 4\. Batch Convert All CBZ Files in a Folder to EPUB

This command finds all `.cbz` files in the `my-comics/` directory and converts each one to an `.epub` file.

- **Using Gradle:**
  ```sh
  ./gradlew run --args='"my-comics/*.cbz" --format epub'
  ```
- **Using JAR:**
  ```sh
  java -jar build/libs/simple-manga-combiner-1.0.0-all.jar "my-comics/*.cbz" --format epub
  ```

#### 5\. More Aggressive Downloading (Use with Caution)

If you are on a fast connection and trust the server, you can increase the number of parallel image downloads.

- **Using Gradle:**
  ```sh
  ./gradlew run --args='"https://mangasite.com/manga/another-manga" --workers 8'
  ```

## License

This project is licensed under the MIT License.