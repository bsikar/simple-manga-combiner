package com.mangacombiner.ui.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun PlatformTooltip(
    tooltipText: String,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    // Tooltips are a desktop-only feature in this app.
    // On Android, just display the content without the tooltip wrapper.
    content()
}
