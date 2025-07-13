package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.model.DownloadJob
import com.mangacombiner.model.QueuedOperation
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.RangeAction
import com.mangacombiner.ui.viewmodel.state.Screen
import com.mangacombiner.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal fun MainViewModel.handleCacheEvent(event: Event.Cache) {
    when (event) {
        is Event.Cache.SelectAllChapters -> onSelectAllCachedChapters(event.seriesPath, event.select)
        is Event.Cache.SetSort -> _state.update {
            val newSortMap = it.cacheSortState.toMutableMap()
            if (event.sortState == null) newSortMap.remove(event.seriesPath) else newSortMap[event.seriesPath] = event.sortState
            it.copy(cacheSortState = newSortMap)
        }
        is Event.Cache.SetItemForDeletion -> _state.update {
            val newSet = it.cacheItemsToDelete.toMutableSet()
            if (event.select) {
                newSet.add(event.path)
            } else {
                newSet.remove(event.path)
            }
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
            val allPaths = it.cacheContents.flatMap { series ->
                if (series.chapters.isEmpty()) {
                    listOf(series.path)
                } else {
                    series.chapters.map { chapter -> chapter.path }
                }
            }
            it.copy(cacheItemsToDelete = allPaths.toSet())
        }
        Event.Cache.RequeueSelected -> onRequeueSelected()
    }
}

private fun MainViewModel.onSelectAllCachedChapters(seriesPath: String, select: Boolean) {
    _state.update { uiState ->
        val series = uiState.cacheContents.find { it.path == seriesPath } ?: return@update uiState
        val currentSelection = uiState.cacheItemsToDelete.toMutableSet()
        val chapterPaths = series.chapters.map { it.path }.toSet()

        if (select) {
            if (series.chapters.isEmpty()) {
                currentSelection.add(series.path)
            } else {
                currentSelection.addAll(chapterPaths)
            }
        } else {
            // When deselecting, remove both the series path and all its chapter paths.
            currentSelection.remove(series.path)
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
        }
        uiState.copy(cacheItemsToDelete = currentSelection)
    }
}

private fun MainViewModel.onLoadCachedSeries(seriesPath: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val originalOp = queuePersistenceService.loadOperationMetadata(seriesPath)

        if (originalOp == null) {
            Logger.logError("Could not load metadata for cached series at path: $seriesPath")
            return@launch
        }

        // Get the chapters the user selected in the cache view
        val selectedChapterPaths = _state.value.cacheItemsToDelete
        val selectedChapterNames = _state.value.cacheContents
            .find { it.path == seriesPath }
            ?.chapters
            ?.filter { it.path in selectedChapterPaths }
            ?.map { it.name }
            ?.toSet() ?: emptySet()

        if (selectedChapterNames.isEmpty()) {
            Logger.logInfo("No chapters selected from cache to edit.")
            return@launch
        }

        withContext(Dispatchers.Main) {
            _state.update {
                it.copy(
                    seriesUrl = originalOp.seriesUrl,
                    customTitle = originalOp.customTitle,
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
    }
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
        val allPossiblePaths = _state.value.cacheContents.flatMap { series ->
            if (series.chapters.isEmpty()) listOf(series.path) else series.chapters.map { it.path }
        }.toSet()
        val selectedPaths = _state.value.cacheItemsToDelete.toSet()

        if (selectedPaths == allPossiblePaths) {
            Logger.logInfo("All cache items are selected. Clearing all cache.")
            cacheService.clearAllAppCache()
        } else {
            cacheService.deleteCacheItems(_state.value.cacheItemsToDelete.toList())
        }
        _state.update { it.copy(cacheItemsToDelete = emptySet(), showDeleteCacheConfirmationDialog = false) }
        onRefreshCacheView()
    }
}

private fun MainViewModel.onRefreshCacheView() {
    viewModelScope.launch(Dispatchers.IO) {
        _state.update { it.copy(cacheContents = cacheService.getCacheContents()) }
    }
}

private fun MainViewModel.onRequeueSelected() {
    viewModelScope.launch(Dispatchers.IO) {
        // Get the set of unique PARENT series paths from the selection.
        val seriesPathsToRequeue = _state.value.cacheItemsToDelete.mapNotNull { pathString ->
            val file = File(pathString)
            if (file.name.startsWith("manga-dl-")) {
                file.absolutePath
            } else {
                file.parentFile?.absolutePath
            }
        }.toSet()

        if (seriesPathsToRequeue.isEmpty()) {
            Logger.logInfo("No cached series selected to re-queue.")
            return@launch
        }

        var requeuedCount = 0
        val newJobs = mutableListOf<DownloadJob>()
        val newOps = mutableMapOf<String, QueuedOperation>()

        for (path in seriesPathsToRequeue) {
            val originalOp = queuePersistenceService.loadOperationMetadata(path) ?: continue

            val newJobId = UUID.randomUUID().toString()
            // Create a new operation with the new ID
            val newOp = originalOp.copy(jobId = newJobId)
            val newJob = DownloadJob(
                id = newJobId,
                title = newOp.customTitle,
                progress = 0f,
                status = "Queued",
                totalChapters = newOp.chapters.count { it.selectedSource != null },
                downloadedChapters = 0
            )
            newJobs.add(newJob)
            newOps[newJobId] = newOp
            requeuedCount++
        }

        if (requeuedCount > 0) {
            // Update state in one go on the main thread
            withContext(Dispatchers.Main) {
                newOps.forEach { (id, op) -> queuedOperationContext[id] = op }
                _state.update {
                    it.copy(
                        downloadQueue = it.downloadQueue + newJobs,
                        cacheItemsToDelete = emptySet(), // Clear selection after requeueing
                        completionMessage = "$requeuedCount series re-added to the queue."
                    )
                }
                Logger.logInfo("$requeuedCount series have been added to the download queue.")
            }
        } else {
            Logger.logError("Could not find metadata for any of the selected items to re-queue.")
        }
    }
}
