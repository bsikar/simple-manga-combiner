package com.mangacombiner.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Desktop implementation of PlatformViewModel.
 * Provides a CoroutineScope that lives until the app is closed.
 */
actual open class PlatformViewModel {
    actual val viewModelScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * On desktop, this would be called if the ViewModel had a clear "destroy" event.
     * For this application, the scope lives until the application exits.
     */
    fun onClear() {
        viewModelScope.cancel()
    }
}
