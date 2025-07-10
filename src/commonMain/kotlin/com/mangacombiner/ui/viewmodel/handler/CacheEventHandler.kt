package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.RangeAction
import com.mangacombiner.ui.viewmodel.state.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun MainViewModel.handleCacheEvent(event: Event.Cache) {
    when (event) {
        is Event.Cache.SelectAllChapters -> onSelectAllCachedChapters(event.seriesPath, event.select)
        is Event.Cache.SetSort -> _state.update {
            val newSortMap = it.cacheSortState.toMutableMap()
            if (event.sortState == null) newSortMap.remove(event.seriesPath) else newSortMap[event.seriesPath] = event.sortState
            it.copy(cacheSortState = newSortMap)
        }
        is Event.Cache.ToggleItemForDeletion -> _state.update {
            val newSet = it.cacheItemsToDelete.toMutableSet()
            if (newSet.contains(event.path)) newSet.remove(event.path) else newSet.add(event.path)
            it.copy(cacheItemsToDelete = newSet)
        }
        is Event.Cache.ToggleSeries -> _state.update {
            val newSet = it.expandedCacheSeries.toMutableSet()
            if (newSet.contains(event.seriesPath)) newSet.remove(event.seriesPath) else newSet.add(event.seriesPath)
            it.copy(expandedCacheSeries = newSet)
        }
        is Event.Cache.UpdateChapterRange -> onUpdateCachedChapterRange(event.seriesPath, event.start, event.end, event.action)
        is Event.Cache.LoadCachedSeries -> onLoadCachedSeries(event.seriesPath)
        Event.Cache.CancelClearAll -> _state.update { it.copy(showClearCacheDialog = false) }
        Event.Cache.CancelDeleteSelected -> _state.update { it.copy(showDeleteCacheConfirmationDialog = false) }
        Event.Cache.ConfirmClearAll -> onConfirmClearAllCache()
        Event.Cache.ConfirmDeleteSelected -> onConfirmDeleteSelectedCacheItems()
        Event.Cache.DeselectAll -> _state.update { it.copy(cacheItemsToDelete = emptySet()) }
        Event.Cache.RefreshView -> onRefreshCacheView()
        Event.Cache.RequestClearAll -> _state.update { it.copy(showClearCacheDialog = true) }
        Event.Cache.RequestDeleteSelected -> _state.update { it.copy(showDeleteCacheConfirmationDialog = true) }
        Event.Cache.SelectAll -> _state.update {
            val allChapterPaths = it.cacheContents.flatMap { series -> series.chapters.map { chapter -> chapter.path } }
            it.copy(cacheItemsToDelete = allChapterPaths.toSet())
        }
    }
}

private fun MainViewModel.onSelectAllCachedChapters(seriesPath: String, select: Boolean) {
    _state.update { uiState ->
        val series = uiState.cacheContents.find { it.path == seriesPath } ?: return@update uiState
        val chapterPaths = series.chapters.map { it.path }.toSet()
        val currentSelection = uiState.cacheItemsToDelete.toMutableSet()
        if (select) {
            currentSelection.addAll(chapterPaths)
        } else {
            currentSelection.removeAll(chapterPaths)
        }
        uiState.copy(cacheItemsToDelete = currentSelection)
    }
}

private fun MainViewModel.onUpdateCachedChapterRange(seriesPath: String, start: Int, end: Int, action: RangeAction) {
    _state.update { uiState ->
        val series = uiState.cacheContents.find { it.path == seriesPath } ?: return@update uiState
        val chaptersToUpdate = series.chapters.slice((start - 1) until end).map { it.path }
        val currentSelection = uiState.cacheItemsToDelete.toMutableSet()
        when (action) {
            RangeAction.SELECT -> currentSelection.addAll(chaptersToUpdate)
            RangeAction.DESELECT -> currentSelection.removeAll(chaptersToUpdate.toSet())
            RangeAction.TOGGLE -> chaptersToUpdate.forEach { path ->
                if (currentSelection.contains(path)) currentSelection.remove(path) else currentSelection.add(path)
            }
        }
        uiState.copy(cacheItemsToDelete = currentSelection)
    }
}

private fun MainViewModel.onLoadCachedSeries(seriesPath: String) {
    val series = _state.value.cacheContents.find { it.path == seriesPath } ?: return
    val selectedPaths = _state.value.cacheItemsToDelete
    val selectedChapterNames = series.chapters
        .filter { it.path in selectedPaths }
        .map { it.name }
        .toSet()

    _state.update {
        it.copy(
            seriesUrl = series.seriesUrl ?: "",
            customTitle = series.seriesName,
            chaptersToPreselect = selectedChapterNames,
            // Clear fields from any previous operations
            sourceFilePath = null,
            localChaptersForSync = emptyMap(),
            failedItemsForSync = emptyMap()
        )
    }
    onEvent(Event.Navigate(Screen.DOWNLOAD))
    onEvent(Event.Download.FetchChapters)
}

private fun MainViewModel.onConfirmClearAllCache() {
    _state.update { it.copy(showClearCacheDialog = false) }
    viewModelScope.launch(Dispatchers.IO) {
        cacheService.clearAllAppCache()
        onRefreshCacheView()
    }
}

private fun MainViewModel.onConfirmDeleteSelectedCacheItems() {
    viewModelScope.launch(Dispatchers.IO) {
        cacheService.deleteCacheItems(_state.value.cacheItemsToDelete.toList())
        _state.update { it.copy(cacheItemsToDelete = emptySet(), showDeleteCacheConfirmationDialog = false) }
        onRefreshCacheView()
    }
}

private fun MainViewModel.onRefreshCacheView() {
    viewModelScope.launch(Dispatchers.IO) {
        _state.update { it.copy(cacheContents = cacheService.getCacheContents()) }
    }
}
