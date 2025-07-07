package com.mangacombiner.util.pointer

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Cursor

actual fun Modifier.pointerIcon(icon: PointerIcon): Modifier = this.pointerHoverIcon(icon)

actual val resizeCursor: PointerIcon = PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))
