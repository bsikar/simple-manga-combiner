package com.mangacombiner.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangacombiner.util.pointer.pointerIcon
import com.mangacombiner.util.pointer.resizeCursor
import java.awt.Cursor

private const val DEFAULT_RATIO = 0.65f
private const val MIN_RATIO = 0.1f
private const val MAX_RATIO = 0.9f
private const val COLLAPSED_RATIO = 0.9f

class SplitterState {
    var isExpanded by mutableStateOf(true)
    var ratio: MutableState<Float> = mutableStateOf(DEFAULT_RATIO)
    private var lastRatio by mutableStateOf(DEFAULT_RATIO)

    fun toggle() {
        isExpanded = !isExpanded
        if (isExpanded) {
            ratio.value = lastRatio
        } else {
            lastRatio = ratio.value
            ratio.value = COLLAPSED_RATIO
        }
    }

    fun reset() {
        isExpanded = true
        ratio.value = DEFAULT_RATIO
    }
}

@Composable
fun rememberSplitterState(): SplitterState = remember { SplitterState() }

@Composable
fun VerticalSplitter(
    splitterState: SplitterState,
    topContent: @Composable () -> Unit,
    bottomContent: @Composable () -> Unit,
) {
    BoxWithConstraints {
        val height = constraints.maxHeight.toFloat()
        val split = height * splitterState.ratio.value
        val draggableState = rememberDraggableState { delta ->
            // If user drags, always expand the panel
            if (!splitterState.isExpanded) {
                splitterState.isExpanded = true
            }
            val newRatio = (split + delta) / height
            splitterState.ratio.value = newRatio.coerceIn(MIN_RATIO, MAX_RATIO)
        }

        Column {
            Box(modifier = Modifier.height(split.dp)) {
                topContent()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(MaterialTheme.colors.onSurface.copy(alpha = 0.12f))
                    .pointerIcon(resizeCursor)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = draggableState
                    )
            )
            Box(modifier = Modifier.weight(1f)) {
                bottomContent()
            }
        }
    }
}
