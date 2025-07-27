package com.mangacombiner.ui.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangacombiner.model.SearchResult
import androidx.compose.material.IconButton

@Composable
fun SearchResultItem(
    result: SearchResult,
    onExpandToggle: () -> Unit,
    onSelect: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpandToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(result.title)
                // Show chapter count only after details are fetched
                if (!result.isFetchingDetails && result.chapterCount != null) {
                    Text("${result.chapterCount} chapters (${result.chapterRange})")
                }
            }
            if (result.isFetchingDetails) {
                // Use a Spacer to hold the place of the icon to prevent layout shift
                Spacer(modifier = Modifier.size(24.dp))
            } else {
                Icon(
                    imageVector = if (result.isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand/Collapse"
                )
            }
            Spacer(Modifier.width(8.dp))
            PlatformTooltip("Select this series") {
                IconButton(onClick = onSelect, enabled = !result.isFetchingDetails) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Select Series")
                }
            }
        }
        AnimatedVisibility(visible = result.isExpanded && result.chapters.isNotEmpty()) {
            Column(modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) {
                result.chapters.take(5).forEach {
                    Text("- ${it.second}")
                }
                if (result.chapters.size > 5) {
                    Text("...and ${result.chapters.size - 5} more.")
                }
            }
        }
    }
}
