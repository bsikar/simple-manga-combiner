package com.mangacombiner.util

import com.mangacombiner.model.IconTheme

/**
 * An abstraction for changing the application icon at runtime.
 * This is primarily for Android, but defined in common code.
 */
expect class IconChanger {
    fun setIcon(iconTheme: IconTheme)
}
