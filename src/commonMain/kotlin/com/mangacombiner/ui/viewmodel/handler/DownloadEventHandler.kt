package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.ChapterSource
import com.mangacombiner.ui.viewmodel.state.FilePickerRequest
import com.mangacombiner.ui.viewmodel.state.RangeAction
import com.mangacombiner.util.titlecase
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun MainViewModel.handleDownloadEvent(event: Event.Download) {
    when (event) {
        is Event.Download.UpdateUrl -> onUpdateUrl(event.url)
        is Event.Download.UpdateCustomTitle -> onUpdateCustomTitle(event.title)
        is Event.Download.UpdateFormat -> onUpdateFormat(event.format)
        is Event.Download.ToggleDryRun -> _state.update { it.copy(dryRun = event.isEnabled) }
        is Event.Download.ToggleChapterSelection -> onToggleChapterSelection(event.chapterUrl, event.select)
        is Event.Download.ToggleChapterRedownload -> onToggleChapterRedownload(event.chapterUrl)
        is Event.Download.UpdateChapterSource -> onUpdateChapterSource(event.chapterUrl, event.source)
        is Event.Download.UpdateChapterRange -> onUpdateChapterRange(event.start, event.end, event.action)
        is Event.Download.UpdateOutputPath -> onUpdateOutputPath(event.path)
        Event.Download.FetchChapters -> fetchChapters()
        Event.Download.ClearInputs -> onClearDownloadInputs()
        Event.Download.PickLocalFile -> onPickLocalFile()
        Event.Download.PickOutputPath -> onPickOutputPath()
        Event.Download.ConfirmChapterSelection -> _state.update { it.copy(showChapterDialog = false) }
        Event.Download.CancelChapterSelection -> _state.update { it.copy(showChapterDialog = false, fetchedChapters = emptyList()) }
        Event.Download.SelectAllChapters -> onSelectAllChapters(true)
        Event.Download.DeselectAllChapters -> onSelectAllChapters(false)
        Event.Download.UseAllLocal -> onBulkUpdateChapterSource(ChapterSource.LOCAL, ChapterSource.LOCAL)
        Event.Download.IgnoreAllLocal -> onBulkUpdateChapterSource(ChapterSource.LOCAL, null)
        Event.Download.RedownloadAllLocal -> onBulkUpdateChapterSource(ChapterSource.LOCAL, ChapterSource.WEB)
        Event.Download.UseAllCached -> onBulkUpdateChapterSource(ChapterSource.CACHE, ChapterSource.CACHE)
        Event.Download.IgnoreAllCached -> onBulkUpdateChapterSource(ChapterSource.CACHE, null)
        Event.Download.RedownloadAllCached -> onBulkUpdateChapterSource(ChapterSource.CACHE, ChapterSource.WEB)
        Event.Download.UseAllBroken -> onBulkUpdateBrokenChapterSource(ChapterSource.CACHE)
        Event.Download.IgnoreAllBroken -> onBulkUpdateBrokenChapterSource(null)
        Event.Download.RedownloadAllBroken -> onBulkUpdateBrokenChapterSource(ChapterSource.WEB)
    }
}

private fun MainViewModel.onUpdateUrl(newUrl: String) {
    val newTitle = if (newUrl.isNotBlank() && newUrl.contains("/manga/")) {
        newUrl.substringAfterLast("/manga/", "")
            .substringBefore('/')
            .replace('-', ' ')
            .titlecase()
    } else {
        ""
    }
    _state.update {
        it.copy(
            seriesUrl = newUrl,
            customTitle = newTitle,
            sourceFilePath = null,
            localChaptersForSync = emptyMap(),
            fetchedChapters = emptyList()
        )
    }
    checkOutputFileExistence()
}

private fun MainViewModel.onUpdateCustomTitle(title: String) {
    _state.update { it.copy(customTitle = title) }
    checkOutputFileExistence()
}

private fun MainViewModel.onUpdateFormat(format: String) {
    _state.update { it.copy(outputFormat = format) }
    checkOutputFileExistence()
}

private fun MainViewModel.onClearDownloadInputs() {
    _state.update {
        it.copy(
            seriesUrl = "",
            customTitle = "",
            sourceFilePath = null,
            fetchedChapters = emptyList(),
            localChaptersForSync = emptyMap(),
            outputFileExists = false
        )
    }
}

private fun MainViewModel.onPickLocalFile() {
    viewModelScope.launch {
        _filePickerRequest.emit(FilePickerRequest.OpenFile)
    }
}

private fun MainViewModel.onPickOutputPath() {
    viewModelScope.launch {
        _filePickerRequest.emit(FilePickerRequest.OpenFolder(FilePickerRequest.PathType.DEFAULT_OUTPUT))
    }
}

private fun MainViewModel.onToggleChapterSelection(chapterUrl: String, select: Boolean) {
    _state.update {
        val updatedChapters = it.fetchedChapters.map { chapter ->
            if (chapter.url == chapterUrl) {
                val newSource = if (select) {
                    if (chapter.isBroken) ChapterSource.WEB else getChapterDefaultSource(chapter)
                } else {
                    null
                }
                chapter.copy(selectedSource = newSource)
            } else {
                chapter
            }
        }
        it.copy(fetchedChapters = updatedChapters)
    }
}

private fun MainViewModel.onToggleChapterRedownload(chapterUrl: String) {
    _state.update {
        val updatedChapters = it.fetchedChapters.map { chapter ->
            if (chapter.url == chapterUrl) {
                val newSource = if (chapter.selectedSource == ChapterSource.WEB) {
                    getChapterDefaultSource(chapter)
                } else {
                    ChapterSource.WEB
                }
                chapter.copy(selectedSource = newSource)
            } else {
                chapter
            }
        }
        it.copy(fetchedChapters = updatedChapters)
    }
}

private fun MainViewModel.onUpdateChapterSource(chapterUrl: String, source: ChapterSource?) {
    _state.update {
        val updated = it.fetchedChapters.map { ch ->
            if (ch.url == chapterUrl) ch.copy(selectedSource = source) else ch
        }
        it.copy(fetchedChapters = updated)
    }
}

private fun MainViewModel.onUpdateChapterRange(start: Int, end: Int, action: RangeAction) {
    if (start > end || start < 1 || end > _state.value.fetchedChapters.size) {
        return
    }
    _state.update {
        val updatedChapters = it.fetchedChapters.mapIndexed { index, chapter ->
            if (index + 1 in start..end) {
                when (action) {
                    RangeAction.SELECT -> chapter.copy(selectedSource = if (chapter.isBroken) ChapterSource.WEB else getChapterDefaultSource(chapter))
                    RangeAction.DESELECT -> chapter.copy(selectedSource = null)
                    RangeAction.TOGGLE -> chapter.copy(
                        selectedSource = if (chapter.selectedSource == null) {
                            if (chapter.isBroken) ChapterSource.WEB else getChapterDefaultSource(chapter)
                        } else {
                            null
                        }
                    )
                }
            } else {
                chapter
            }
        }
        it.copy(fetchedChapters = updatedChapters)
    }
}

private fun MainViewModel.onSelectAllChapters(select: Boolean) {
    _state.update {
        it.copy(fetchedChapters = it.fetchedChapters.map { ch ->
            ch.copy(selectedSource = if (select) {
                if (ch.isBroken) ChapterSource.WEB else getChapterDefaultSource(ch)
            } else {
                null
            })
        })
    }
}

private fun MainViewModel.onBulkUpdateChapterSource(
    sourceToFind: ChapterSource,
    sourceToSet: ChapterSource?
) {
    _state.update {
        it.copy(fetchedChapters = it.fetchedChapters.map { ch ->
            if (ch.availableSources.contains(sourceToFind)) ch.copy(selectedSource = sourceToSet) else ch
        })
    }
}

private fun MainViewModel.onBulkUpdateBrokenChapterSource(sourceToSet: ChapterSource?) {
    _state.update {
        it.copy(fetchedChapters = it.fetchedChapters.map { ch ->
            if (ch.isBroken) ch.copy(selectedSource = sourceToSet) else ch
        })
    }
}


private fun MainViewModel.onUpdateOutputPath(path: String) {
    _state.update { it.copy(outputPath = path) }
    checkOutputFileExistence()
}
