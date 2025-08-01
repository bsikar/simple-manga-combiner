package com.mangacombiner.ui.viewmodel.handler

import com.mangacombiner.model.SearchResult
import com.mangacombiner.service.NetworkException
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.MainViewModel
import com.mangacombiner.ui.viewmodel.state.Screen
import com.mangacombiner.ui.viewmodel.state.SearchSortOption
import com.mangacombiner.util.Logger
import com.mangacombiner.util.UserAgent
import com.mangacombiner.util.createHttpClient
import com.mangacombiner.util.titlecase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

internal fun MainViewModel.handleSearchEvent(event: Event.Search) {
    when (event) {
        is Event.Search.UpdateQuery -> _state.update { it.copy(searchQuery = event.query) }
        is Event.Search.UpdateSource -> _state.update { it.copy(searchSource = event.source) }
        is Event.Search.SelectResult -> onSelectSearchResult(event.url)
        is Event.Search.SortResults -> onSortSearchResults(event.sortOption)
        is Event.Search.ToggleResultExpansion -> onToggleSearchResultExpansion(event.url)
        Event.Search.Perform -> performSearch()
        Event.Search.Cancel -> onCancelSearch()
        Event.Search.ClearResults -> {
            _state.update {
                it.copy(
                    searchResults = emptyList(),
                    originalSearchResults = emptyList()
                )
            }
            Logger.logInfo("Search results cleared.")
        }
    }
}
private fun MainViewModel.onCancelSearch() {
    searchJob?.cancel()
}

private fun MainViewModel.onSelectSearchResult(url: String) {
    _state.update {
        it.copy(
            // Navigate to Download screen
            currentScreen = Screen.DOWNLOAD,

            // Set new manga context
            seriesUrl = url,
            customTitle = url.substringAfterLast("/manga/", "")
                .substringBefore('/')
                .replace('-', ' ')
                .titlecase(),

            // Clear state from any previous local file operation
            sourceFilePath = null,
            localChaptersForSync = emptyMap(),
            failedItemsForSync = emptyMap()
        )
    }
    checkOutputFileExistence()
}

private fun MainViewModel.onSortSearchResults(sortOption: SearchSortOption) {
    val originalList = state.value.originalSearchResults
    val currentExpansionState = state.value.searchResults.associate { it.url to it.isExpanded }

    val sortedList = when (sortOption) {
        SearchSortOption.CHAPTER_COUNT -> originalList.sortedByDescending { it.chapterCount }
        SearchSortOption.ALPHABETICAL -> originalList.sortedBy { it.title }
        SearchSortOption.DEFAULT -> originalList
    }.map { resultFromOriginal ->
        resultFromOriginal.copy(isExpanded = currentExpansionState.getOrDefault(resultFromOriginal.url, false))
    }

    _state.update { it.copy(searchResults = sortedList, searchSortOption = sortOption) }
}

private fun MainViewModel.onToggleSearchResultExpansion(url: String) {
    val updatedResults = state.value.searchResults.map {
        if (it.url == url) it.copy(isExpanded = !it.isExpanded) else it
    }
    _state.update { it.copy(searchResults = updatedResults) }
}

private fun MainViewModel.performSearch() {
    val query = _state.value.searchQuery
    if (query.isBlank()) {
        Logger.logInfo("Search query is empty.")
        return
    }

    searchJob?.cancel()
    _state.update { it.copy(isSearching = true, searchResults = emptyList(), originalSearchResults = emptyList()) }

    searchJob = viewModelScope.launch(Dispatchers.IO) {
        val s = _state.value
        val source = s.searchSource
        Logger.logInfo("Searching for '$query' on '$source'...")

        val userAgent = UserAgent.browsers[s.userAgentName] ?: UserAgent.browsers.values.first()
        val client = createHttpClient(s.proxyUrl)

        try {
            val initialResults = scraperService.search(client, query, userAgent, source, s.allowNsfw)

            if (initialResults.isEmpty()) {
                Logger.logInfo("No results found for '$query'.")
                return@launch
            }

            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        searchResults = initialResults.map { sr -> sr.copy(isFetchingDetails = true) }
                    )
                }
            }

            coroutineScope {
                val detailedResults = initialResults.map { searchResult ->
                    async {
                        val chapters = scraperService.findChapterUrlsAndTitles(client, searchResult.url, userAgent)
                        val chapterRange = getChapterRange(chapters)
                        searchResult.copy(
                            isFetchingDetails = false,
                            chapters = chapters,
                            chapterCount = chapters.size,
                            chapterRange = chapterRange
                        )
                    }
                }.awaitAll()
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            searchResults = detailedResults,
                            originalSearchResults = detailedResults,
                            searchSortOption = SearchSortOption.DEFAULT
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            Logger.logInfo("Search was cancelled.")
            throw e
        } catch (e: NetworkException) {
            Logger.logError("Search failed due to network error", e)
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        showNetworkErrorDialog = true,
                        networkErrorMessage = "Search failed. Please check your network connection."
                    )
                }
            }
        } catch (e: Exception) {
            Logger.logError("An unexpected error occurred during search", e)
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        showNetworkErrorDialog = true,
                        networkErrorMessage = "An unexpected error occurred."
                    )
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                _state.update { it.copy(isSearching = false) }
                client.close()
            }
        }
    }
}

private fun getChapterRange(chapters: List<Pair<String, String>>): String {
    if (chapters.isEmpty()) return "N/A"
    val firstChapter = chapters.first().second
    val lastChapter = chapters.last().second

    fun getDisplayNumber(title: String): String {
        val chapterPrefixRegex = """(?i)(?:chapter|ch)\s*([\d]+(?:\.\d+)?)""".toRegex()
        val prefixMatch = chapterPrefixRegex.find(title)
        if (prefixMatch != null) {
            return prefixMatch.groupValues[1]
        }

        val genericNumberRegex = """([\d]+(?:\.\d+)?)""".toRegex()
        val genericMatch = genericNumberRegex.find(title)
        return genericMatch?.groupValues?.get(1) ?: ""
    }

    val firstNum = getDisplayNumber(firstChapter)
    val lastNum = getDisplayNumber(lastChapter)

    return if (firstNum.isNotBlank() && lastNum.isNotBlank()) {
        if (firstNum == lastNum) "Ch. $firstNum" else "Ch. $firstNum - $lastNum"
    } else {
        "N/A"
    }
}
