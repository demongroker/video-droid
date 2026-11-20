package com.xerxes.videodroid.ui

import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xerxes.videodroid.QueuedJob
import com.xerxes.videodroid.ui.theme.LocalVdTones

/**
 * The queue, in Compose.
 *
 * Every side effect stays in the Activity — this file only renders state and reports intent, so
 * the download contracts (pause keeps the partial file, cancel asks first, retry only when the
 * error is retryable) cannot be changed by a layout edit.
 *
 * Swipe gestures are replaced by explicit buttons: share and remove are now visible actions
 * instead of hidden gestures, and a mis-swipe can no longer remove a row by accident.
 */
@Composable
fun QueueScreen(
  jobs: List<QueuedJob>,
  pausedCount: Int,
  online: Boolean,
  isClaimed: (String) -> Boolean,
  liveDetail: (String) -> String,
  loadThumb: (QueuedJob, ImageView) -> Unit,
  onOpen: (QueuedJob) -> Unit,
  onRetry: (QueuedJob) -> Unit,
  onCancel: (QueuedJob) -> Unit,
  onShare: (QueuedJob) -> Unit,
  onPauseToggle: (QueuedJob) -> Unit,
  onPauseAll: () -> Unit,
  onRetryFailed: () -> Unit,
  onClearFailed: () -> Unit,
  onClearAll: () -> Unit,
  onGoDownload: () -> Unit,
) {
  val tones = LocalVdTones.current
  val failedCount = jobs.count { it.failed }
  var menuOpen by remember { mutableStateOf(false) }

  Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
    Row(
      Modifier.fillMaxWidth().padding(vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "Queue",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
      )
      if (jobs.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          TextButton(onClick = onPauseAll) { Text(QueueCopy.bulkPauseLabel(pausedCount)) }
          Box {
            TextButton(onClick = { menuOpen = true }) { Text("More") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
              if (failedCount > 0) {
                DropdownMenuItem(
                  text = { Text("Retry failed ($failedCount)") },
                  onClick = { menuOpen = false; onRetryFailed() },
                )
                DropdownMenuItem(
                  text = { Text("Clear failed ($failedCount)") },
                  onClick = { menuOpen = false; onClearFailed() },
                )
              }
              DropdownMenuItem(
                text = { Text("Clear all") },
                onClick = { menuOpen = false; onClearAll() },
              )
            }
          }
        }
      }
    }

    if (jobs.isEmpty()) {
      Column(
        Modifier.fillMaxSize().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          "Nothing in the queue",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
          "Paste a link on the Download tab to start.",
          style = MaterialTheme.typography.bodyMedium,
          color = tones.muted,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onGoDownload, shape = MaterialTheme.shapes.small) {
          Text("Go to Download")
        }
      }
      return@Column
    }

    LazyColumn(Modifier.fillMaxSize()) {
      items(jobs, key = { it.id }) { job ->
        val running = isClaimed(job.id)
        val line = QueueCopy.state(
          failed = job.failed,
          failReason = job.failReason,
          url = job.url,
          paused = job.paused,
          running = running,
          online = online,
          progress = job.progress,
        )
        val toneColor: Color = when (line.tone) {
          QueueTone.GOOD -> tones.success
          QueueTone.WARN -> tones.warning
          QueueTone.BAD -> tones.danger
          QueueTone.BUSY -> MaterialTheme.colorScheme.primary
          QueueTone.NEUTRAL -> tones.muted
        }
        val detail = QueueCopy.detail(job.opts.dlQuality, if (running) liveDetail(job.id) else "")

        Card(
          Modifier.fillMaxWidth().padding(vertical = 5.dp),
          shape = MaterialTheme.shapes.medium,
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
          ),
        ) {
          Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              AndroidView(
                factory = { ctx ->
                  ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                },
                update = { iv -> loadThumb(job, iv) },
                modifier = Modifier.size(64.dp, 40.dp).clip(MaterialTheme.shapes.extraSmall),
              )
              Spacer(Modifier.width(10.dp))
              Text(
                QueueCopy.title(job.displayName, job.url),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
              )
            }
            Spacer(Modifier.height(8.dp))
            Text(
              line.text,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = if (line.tone == QueueTone.BAD) FontWeight.SemiBold else FontWeight.Normal,
              color = toneColor,
            )
            if (detail.isNotBlank()) {
              Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = tones.muted,
              )
            }
            if (QueueCopy.showProgress(job.progress, running)) {
              Spacer(Modifier.height(8.dp))
              LinearProgressIndicator(
                progress = { job.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
              )
            }
            Spacer(Modifier.height(4.dp))
            Row(
              Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(2.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (QueueCopy.showRetry(job.failed, job.failReason)) {
                Button(
                  onClick = { onRetry(job) },
                  shape = MaterialTheme.shapes.small,
                ) { Text("Retry") }
              }
              if (QueueCopy.showPause(job.failed)) {
                TextButton(onClick = { onPauseToggle(job) }) {
                  Text(QueueCopy.pauseLabel(job.paused))
                }
              }
              TextButton(onClick = { onShare(job) }) { Text("Share") }
              TextButton(onClick = { onCancel(job) }) {
                Text(
                  QueueCopy.cancelLabel(job.failed),
                  color = tones.danger,
                )
              }
              Spacer(Modifier.weight(1f))
              if (!job.failed && job.localPath.isNotBlank()) {
                TextButton(onClick = { onOpen(job) }) { Text("Open") }
              }
            }
          }
        }
      }
    }
  }
}
