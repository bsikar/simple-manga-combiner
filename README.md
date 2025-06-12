# CBZ/CBR Manga Tool

A command-line (CLI) utility built in Kotlin to help you manage and organize your digital manga collection. This tool can scan directories for `.cbz` files, list the manga series it finds, and combine individual chapter files into a single, consolidated `.cbz` file for a seamless reading experience.

---
## Features

* **Scan & Discover**: Recursively scans a given directory to find all `.cbz` files.
* **Manga Grouping**: Intelligently groups chapter files by parsing filenames to identify the manga title, volume, and chapter number.
* **List Series**: Provides a clean, sorted list of all unique manga series found in your collection, along with the chapter count for each.
* **Combine Chapters**: Merges all chapters of a specific manga series into a single, ordered `.cbz` file.
* **Gap Detection**: Warns you about potential missing chapters by checking for non-sequential chapter or volume numbers.
* **Dry Run Mode**: Lets you preview the combining process, showing which files would be merged and in what order, without actually creating a new file.
* **Custom Output**: Specify a different directory for your combined files.

---
## Prerequisites

* **Java Development Kit (JDK)**: Version 11 or higher must be installed on your system.

---
## Installation & Building

The project is built using Gradle.

1.  **Clone the repository or download the source code.**
2.  **Navigate to the project's root directory** in your terminal.
3.  **Build the application** by running the Gradle wrapper command. This will compile the code and create a runnable "fat" JAR (a JAR file containing all its dependencies).

    * On macOS or Linux:
        ```bash
        ./gradlew build
        ```
    * On Windows:
        ```bash
        gradlew.bat build
        ```
    After a successful build, the runnable JAR file will be located at `build/libs/cbz-manga-tool-1.0-SNAPSHOT.jar`.

---
## Usage

You can run the application either directly through Gradle or by executing the JAR file. All commands require specifying the directory to scan with the `-d` or `--directory` flag.

### Listing Manga Series

To see all the manga series in a directory:

```bash
# Using Gradle
./gradlew run --args="-d /path/to/your/manga -l"

# Using the JAR
java -jar build/libs/cbz-manga-tool-1.0-SNAPSHOT.jar -d /path/to/your/manga --list
```

### Combining a Manga Series

To combine all chapters for a specific manga into one `.cbz` file:

```bash
# The manga title must match the output from the --list command
./gradlew run --args='-d /path/to/your/manga -c "Name of Manga"'

# Using the JAR
java -jar build/libs/cbz-manga-tool-1.0-SNAPSHOT.jar -d /path/to/manga --combine "Name of Manga"
```

### Specifying an Output Directory

Use the `-o` or `--output` flag to save the combined file to a different location.

```bash
./gradlew run --args='-d /path/to/manga -c "Name of Manga" -o /path/to/output/folder'
```

### Performing a Dry Run

To see what the tool *would* do without creating any files, use the `--dry-run` flag. This is useful for checking the file order and verifying that the correct chapters are being targeted.

```bash
./gradlew run --args='-d /path/to/manga -c "Name of Manga" --dry-run'
```

---
## Filename Convention

For the tool to correctly identify and sort your manga, your `.cbz` files should follow a consistent naming convention. The tool's parser is designed to recognize formats like these:

* `Manga Title - v01 c001.cbz`
* `Manga Title - v1 c1.cbz`
* `Manga Title c01.cbz` (assumes volume 1 if not specified)

The key components are:
* The **manga title**.
* A `v` followed by the **volume number**.
* A `c` followed by the **chapter number**.

These components can be separated by spaces or hyphens.

---
## License

This project is licensed under the MIT License. See the `LICENSE` file for details.
