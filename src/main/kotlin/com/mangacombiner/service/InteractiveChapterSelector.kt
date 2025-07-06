package com.mangacombiner.service

import com.mangacombiner.util.Logger
import org.springframework.stereotype.Component
import java.io.IOException

/**
 * Interactive TUI for selecting chapters to include in the output archive.
 * Simplified version for better macOS compatibility.
 */
@Component
class InteractiveChapterSelector {

    /**
     * Represents a chapter that can be selected/deselected.
     */
    data class SelectableChapter(
        val url: String,
        val title: String,
        val pageCount: Int? = null,
        var selected: Boolean = true
    )

    /**
     * Terminal control sequences for cursor movement and formatting.
     */
    private object Terminal {
        const val CLEAR_SCREEN = "\u001B[2J"
        const val CURSOR_HOME = "\u001B[H"
        const val RESET_COLOR = "\u001B[0m"
        const val BOLD = "\u001B[1m"
        const val GREEN = "\u001B[32m"
        const val RED = "\u001B[31m"
        const val YELLOW = "\u001B[33m"
        const val CYAN = "\u001B[36m"
        const val GRAY = "\u001B[90m"
    }

    private companion object {
        const val DEFAULT_TERMINAL_HEIGHT = 20
        const val DEFAULT_TERMINAL_WIDTH = 80
        const val PAGE_SIZE = 15
        const val MAX_LINE_WIDTH = 78
        const val BORDER_PADDING = 4
        const val TRUNCATE_OFFSET = 7
        const val RANGE_COMMAND_PREFIX_LENGTH = 6
        const val TOGGLE_COMMAND_PREFIX_LENGTH = 2
    }

    private var terminalHeight = DEFAULT_TERMINAL_HEIGHT
    private var terminalWidth = DEFAULT_TERMINAL_WIDTH

    init {
        detectTerminalSize()
    }

    private fun detectTerminalSize() {
        try {
            val process = ProcessBuilder("stty", "size").start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val parts = output.split(" ")
            if (parts.size == 2) {
                terminalHeight = parts[0].toIntOrNull() ?: DEFAULT_TERMINAL_HEIGHT
                terminalWidth = parts[1].toIntOrNull() ?: DEFAULT_TERMINAL_WIDTH
            }
        } catch (e: IOException) {
            Logger.logDebug { "Could not detect terminal size, using defaults: ${e.message}" }
        }
    }

    /**
     * Displays the interactive chapter selection interface using line-based input.
     */
    fun selectChapters(chapters: List<SelectableChapter>, title: String): List<SelectableChapter>? {
        if (chapters.isEmpty()) {
            println("No chapters available for selection.")
            return emptyList()
        }

        return if (!isInteractiveTerminal()) {
            println("Interactive mode requires a TTY terminal. Proceeding with all chapters selected.")
            chapters
        } else {
            runLineBasedInterface(chapters.toMutableList(), title)
        }
    }

    private fun isInteractiveTerminal(): Boolean {
        return try {
            System.console() != null && System.getenv("TERM") != null
        } catch (e: SecurityException) {
            Logger.logDebug { "Security exception checking terminal: ${e.message}" }
            false
        }
    }

    private fun runLineBasedInterface(
        chapters: MutableList<SelectableChapter>,
        title: String
    ): List<SelectableChapter>? {
        var currentPage = 0
        val maxPage = (chapters.size - 1) / PAGE_SIZE

        while (true) {
            drawPagedInterface(chapters, title, currentPage)

            print("\nCommand: ")
            val input = readLine()?.trim()?.lowercase() ?: ""
            if (input.isEmpty()) continue

            val result = processCommand(input, chapters, currentPage, maxPage)

            when (result.action) {
                CommandResult.QUIT -> return null
                CommandResult.CONFIRM -> return chapters.filter { it.selected }
                CommandResult.NEXT_PAGE -> currentPage = minOf(currentPage + 1, maxPage)
                CommandResult.PREV_PAGE -> currentPage = maxOf(currentPage - 1, 0)
                CommandResult.CONTINUE -> { /* Continue loop */ }
            }
        }
    }

    private data class ProcessResult(
        val action: CommandResult,
        val newPage: Int? = null
    )

    private enum class CommandResult {
        QUIT, CONFIRM, NEXT_PAGE, PREV_PAGE, CONTINUE
    }

    private fun processCommand(
        input: String,
        chapters: MutableList<SelectableChapter>,
        currentPage: Int,
        maxPage: Int
    ): ProcessResult {
        return when {
            isQuitCommand(input) -> handleQuitCommand()
            isConfirmCommand(input) -> ProcessResult(CommandResult.CONFIRM)
            isSelectAllCommand(input) -> handleSelectAllCommand(chapters)
            isSelectNoneCommand(input) -> handleSelectNoneCommand(chapters)
            isNextPageCommand(input) -> handleNextPageCommand(currentPage, maxPage)
            isPrevPageCommand(input) -> handlePrevPageCommand(currentPage)
            isToggleCommand(input) -> handleToggleCommand(input, chapters)
            isRangeCommand(input) -> handleRangeCommand(input, chapters)
            isHelpCommand(input) -> handleHelpCommand()
            else -> handleUnknownCommand()
        }
    }

    private fun isQuitCommand(input: String) = input == "q" || input == "quit"
    private fun isConfirmCommand(input: String) = input == "c" || input == "confirm"
    private fun isSelectAllCommand(input: String) = input == "a" || input == "all"
    private fun isSelectNoneCommand(input: String) = input == "n" || input == "none"
    private fun isNextPageCommand(input: String) = input == "next" || input == ">"
    private fun isPrevPageCommand(input: String) = input == "prev" || input == "<"
    private fun isToggleCommand(input: String) = input.startsWith("t ")
    private fun isRangeCommand(input: String) = input.startsWith("range ")
    private fun isHelpCommand(input: String) = input == "help" || input == "h"

    private fun handleQuitCommand(): ProcessResult {
        println("Selection cancelled.")
        return ProcessResult(CommandResult.QUIT)
    }

    private fun handleSelectAllCommand(chapters: MutableList<SelectableChapter>): ProcessResult {
        chapters.forEach { it.selected = true }
        println("All chapters selected.")
        return ProcessResult(CommandResult.CONTINUE)
    }

    private fun handleSelectNoneCommand(chapters: MutableList<SelectableChapter>): ProcessResult {
        chapters.forEach { it.selected = false }
        println("All chapters deselected.")
        return ProcessResult(CommandResult.CONTINUE)
    }

    private fun handleNextPageCommand(currentPage: Int, maxPage: Int): ProcessResult {
        return if (currentPage >= maxPage) {
            println("Already on last page.")
            ProcessResult(CommandResult.CONTINUE)
        } else {
            ProcessResult(CommandResult.NEXT_PAGE)
        }
    }

    private fun handlePrevPageCommand(currentPage: Int): ProcessResult {
        return if (currentPage <= 0) {
            println("Already on first page.")
            ProcessResult(CommandResult.CONTINUE)
        } else {
            ProcessResult(CommandResult.PREV_PAGE)
        }
    }

    private fun handleToggleCommand(input: String, chapters: MutableList<SelectableChapter>): ProcessResult {
        handleToggleChapter(input, chapters)
        return ProcessResult(CommandResult.CONTINUE)
    }

    private fun handleRangeCommand(input: String, chapters: MutableList<SelectableChapter>): ProcessResult {
        handleChapterRange(input, chapters)
        return ProcessResult(CommandResult.CONTINUE)
    }

    private fun handleHelpCommand(): ProcessResult {
        showHelp()
        return ProcessResult(CommandResult.CONTINUE)
    }

    private fun handleUnknownCommand(): ProcessResult {
        println("Unknown command. Type 'help' for available commands.")
        return ProcessResult(CommandResult.CONTINUE)
    }

    private fun handleToggleChapter(input: String, chapters: MutableList<SelectableChapter>) {
        val chapterNumber = input.substring(TOGGLE_COMMAND_PREFIX_LENGTH).toIntOrNull()
        if (chapterNumber != null && chapterNumber in 1..chapters.size) {
            val chapter = chapters[chapterNumber - 1]
            chapter.selected = !chapter.selected
            val status = if (chapter.selected) "selected" else "deselected"
            println("Chapter $chapterNumber $status.")
        } else {
            println("Invalid chapter number. Use 't <number>' (1-${chapters.size})")
        }
    }

    private fun handleChapterRange(input: String, chapters: MutableList<SelectableChapter>) {
        try {
            val rangePart = input.substring(RANGE_COMMAND_PREFIX_LENGTH).trim()
            val parts = rangePart.split("-")
            if (parts.size != 2) {
                println("Invalid range format. Use 'range <start>-<end>' (e.g., 'range 5-10')")
                return
            }

            val start = parts[0].trim().toInt()
            val end = parts[1].trim().toInt()

            if (start !in 1..chapters.size || end !in 1..chapters.size || start > end) {
                println("Invalid range. Use 'range <start>-<end>' (1-${chapters.size})")
                return
            }

            for (i in start - 1 until end) {
                chapters[i].selected = !chapters[i].selected
            }
            println("Toggled chapters $start-$end.")
        } catch (e: NumberFormatException) {
            println("Invalid range format. Use 'range <start>-<end>' with numbers.")
        }
    }

    private fun drawPagedInterface(chapters: List<SelectableChapter>, title: String, currentPage: Int) {
        print(Terminal.CLEAR_SCREEN + Terminal.CURSOR_HOME)

        val line = "═".repeat(minOf(terminalWidth - 2, MAX_LINE_WIDTH))
        println("╭─${Terminal.CYAN}Interactive Chapter Selection${Terminal.RESET_COLOR}─$line╮")
        println("│ ${Terminal.YELLOW}$title${Terminal.RESET_COLOR}")
        println("├─$line─┤")

        drawChapterList(chapters, currentPage)
        drawFooter(chapters, currentPage, line)
        showCommands()
    }

    private fun drawChapterList(chapters: List<SelectableChapter>, currentPage: Int) {
        val startIndex = currentPage * PAGE_SIZE
        val endIndex = minOf(startIndex + PAGE_SIZE, chapters.size)
        val pageChapters = chapters.subList(startIndex, endIndex)

        pageChapters.forEachIndexed { index, chapter ->
            val globalIndex = startIndex + index + 1
            val checkbox = if (chapter.selected) {
                "${Terminal.GREEN}[●]${Terminal.RESET_COLOR}"
            } else {
                "${Terminal.RED}[ ]${Terminal.RESET_COLOR}"
            }
            val pageInfo = chapter.pageCount?.let { " ${Terminal.GRAY}(${it}p)${Terminal.RESET_COLOR}" } ?: ""

            val chapterLine = "$checkbox $globalIndex. ${chapter.title}$pageInfo"
            val truncated = if (chapterLine.length > terminalWidth - BORDER_PADDING) {
                chapterLine.take(terminalWidth - TRUNCATE_OFFSET) + "..."
            } else {
                chapterLine
            }
            println("│ $truncated")
        }

        // Fill remaining space if needed
        repeat(PAGE_SIZE - pageChapters.size) {
            println("│")
        }
    }

    private fun drawFooter(chapters: List<SelectableChapter>, currentPage: Int, line: String) {
        println("├─$line─┤")

        val selectedCount = chapters.count { it.selected }
        val totalCount = chapters.size
        val pageInfo = "Page ${currentPage + 1}/${(totalCount - 1) / PAGE_SIZE + 1}"
        val statusLine = "${Terminal.BOLD}Selected: $selectedCount/$totalCount${Terminal.RESET_COLOR} | $pageInfo"
        println("│ $statusLine")

        println("╰─$line─╯")
    }

    private fun showCommands() {
        println("\n${Terminal.YELLOW}Commands:${Terminal.RESET_COLOR}")
        println("  ${Terminal.GREEN}t <num>${Terminal.RESET_COLOR}     - Toggle chapter <num> (e.g., 't 5')")
        println("  ${Terminal.GREEN}range <start>-<end>${Terminal.RESET_COLOR} - Toggle range (e.g., 'range 1-10')")
        println("  ${Terminal.GREEN}a/all${Terminal.RESET_COLOR}      - Select all chapters")
        println("  ${Terminal.GREEN}n/none${Terminal.RESET_COLOR}     - Deselect all chapters")
        println("  ${Terminal.GREEN}next / >${Terminal.RESET_COLOR}    - Next page")
        println("  ${Terminal.GREEN}prev / <${Terminal.RESET_COLOR}    - Previous page")
        println("  ${Terminal.GREEN}c/confirm${Terminal.RESET_COLOR}   - Confirm selection and proceed")
        println("  ${Terminal.GREEN}q/quit${Terminal.RESET_COLOR}      - Cancel and quit")
        println("  ${Terminal.GREEN}help${Terminal.RESET_COLOR}        - Show this help")
    }

    private fun showHelp() {
        println("\n${Terminal.CYAN}=== Interactive Chapter Selection Help ===${Terminal.RESET_COLOR}")
        println("Use the commands above to select which chapters to include.")
        println("Examples:")
        println("  't 1'        - Toggle chapter 1")
        println("  'range 5-15' - Toggle chapters 5 through 15")
        println("  'all'        - Select all chapters")
        println("  'none'       - Deselect all chapters")
        println("  'next'       - Go to next page")
        println("  'confirm'    - Proceed with selected chapters")
        println("Press Enter to continue...")
        readLine()
    }

    /**
     * Creates SelectableChapter instances from chapter URLs and titles.
     */
    fun createSelectableChapters(chaptersWithTitles: List<Pair<String, String>>): List<SelectableChapter> {
        return chaptersWithTitles.map { (url, title) ->
            SelectableChapter(
                url = url,
                title = title,
                pageCount = null,
                selected = true
            )
        }
    }

    /**
     * Creates SelectableChapter instances from local chapter folders.
     */
    fun createSelectableChaptersFromFolders(folders: List<java.io.File>): List<SelectableChapter> {
        return folders.map { folder ->
            val imageCount = folder.listFiles()?.count { file ->
                file.isFile && file.extension.lowercase() in ProcessorService.IMAGE_EXTENSIONS
            } ?: 0

            SelectableChapter(
                url = folder.absolutePath,
                title = folder.name.replace(Regex("[_-]"), " ")
                    .split(" ")
                    .joinToString(" ") { it.replaceFirstChar(Char::titlecase) },
                pageCount = imageCount,
                selected = true
            )
        }
    }
}
