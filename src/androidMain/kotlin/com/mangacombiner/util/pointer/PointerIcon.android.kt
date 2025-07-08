package com.mangacombiner.util.pointer

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon

actual fun Modifier.pointerIcon(icon: PointerIcon): Modifier = this

actual val resizeCursor: PointerIcon = PointerIcon.Default
