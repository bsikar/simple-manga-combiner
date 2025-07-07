package com.mangacombiner.util.pointer

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon

expect fun Modifier.pointerIcon(icon: PointerIcon): Modifier

expect val resizeCursor: PointerIcon
