package com.mangacombiner.util.pointer

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon

// No-op on Android, as there is no traditional cursor
actual fun Modifier.pointerIcon(icon: PointerIcon): Modifier = this

// Provide a default icon for the Android target
actual val resizeCursor: PointerIcon = PointerIcon.Default
