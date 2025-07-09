package com.mangacombiner.ui.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangacombiner.model.DownloadJob
import com.mangacombiner.ui.viewmodel.Event
import com.mangacombiner.ui.viewmodel.state.UiState

@Composable
fun DownloadQueueScreen(state: UiState, onEvent: (Event) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text("Download Queue", style = MaterialTheme.typography.h5)
        Spacer(Modifier.height(16.dp))

        if (state.downloadQueue.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("The download queue is empty.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.downloadQueue, key = { it.id }) { job ->
                    DownloadJobItem(job)
                }
            }
        }
    }
}

@Composable
private fun DownloadJobItem(job: DownloadJob) {
    val animatedProgress by animateFloatAsState(
        targetValue = job.progress,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = job.title,
                style = MaterialTheme.typography.h6,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${job.downloadedChapters} / ${job.totalChapters} Chapters",
                    style = MaterialTheme.typography.body2
                )
                Text(
                    text = job.status,
                    style = MaterialTheme.typography.body2,
                    color = when (job.status) {
                        "Downloading" -> MaterialTheme.colors.primary
                        "Paused" -> MaterialTheme.colors.secondary
                        "Completed" -> MaterialTheme.colors.primary.copy(alpha = 0.7f)
                        else -> LocalContentColor.current
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            if (job.progress < 1f) {
                LinearProgressIndicator(
                    progress = animatedProgress,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
