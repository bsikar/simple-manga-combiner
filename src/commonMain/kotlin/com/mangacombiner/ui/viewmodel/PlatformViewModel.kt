package com.mangacombiner.ui.viewmodel

import kotlinx.coroutines.CoroutineScope

/**
 * An expect class for a platform-specific ViewModel that provides
 * a lifecycle-aware CoroutineScope.
 */
expect open class PlatformViewModel() {
    val viewModelScope: CoroutineScope
}
