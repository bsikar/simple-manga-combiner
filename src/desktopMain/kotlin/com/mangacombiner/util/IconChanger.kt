package com.mangacombiner.util

import com.mangacombiner.model.IconTheme

actual class IconChanger {
    actual fun setIcon(iconTheme: IconTheme) {
        // Dynamic app icons are not applicable on desktop in the same way.
        // This can be left as a no-op or adapted to change the window icon if desired.
        Logger.logDebug { "Icon theme changed to ${iconTheme.name} (desktop no-op)." }
    }
}
