package com.mangacombiner.util.pointer

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.ExperimentalComposeUiApi

actual fun Modifier.pointerIcon(icon: PointerIcon): Modifier = this

actual val resizeCursor: PointerIcon = PointerIcon.Default

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.tooltipHoverFix(): Modifier = this
