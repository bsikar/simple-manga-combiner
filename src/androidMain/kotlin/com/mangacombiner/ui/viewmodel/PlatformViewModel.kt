package com.mangacombiner.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope as androidViewModelScope
import kotlinx.coroutines.CoroutineScope

/**
 * Android implementation of PlatformViewModel.
 * Inherits from AndroidX ViewModel and provides its lifecycle-aware viewModelScope.
 */
actual open class PlatformViewModel : ViewModel() {
    actual val viewModelScope: CoroutineScope
        get() = androidViewModelScope
}
