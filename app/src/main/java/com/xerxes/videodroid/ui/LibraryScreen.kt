package com.xerxes.videodroid.ui

import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xerxes.videodroid.LibraryFormat
import com.xerxes.videodroid.ui.theme.LocalVdTones
import com.xerxes.videodroid.ui.theme.vdCardBorder

/** One finished download as the Library sees it. [id] is the MediaStore row id. */
data class LibraryItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val durationMs: Long,
    val dateAdded: Long,
) {
    val id: Long get() = uri.lastPathSegment?.toLongOrNull() ?: -1L
}

/**
 * The Library, in Compose.
 *
 * Every side effect stays in the Activity (MediaStore query, biometric gate, share/move/delete,
 * duplicate quarantine). Nothing here deletes anything: the selection actions only report
 * intent, and the Activity confirms first. While the lock gate is pending ([ready] = false) no
 * thumbnail or name is rendered at all.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    items: List<LibraryItem>,
    ready: Boolean,
    listMode: Boolean,
    sort: LibrarySort,
    selectedIds: Set<Long>,
    storageText: String,
    storagePercent: Int,
    loadThumb: (LibraryItem, ImageView) -> Unit,
    onOpen: (LibraryItem) -> Unit,
    onToggleSelect: (LibraryItem) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onToggleView: () -> Unit,
    onFindDuplicates: () -> Unit,
    onShareSelected: () -> Unit,
    onMoveSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onClearSelection: () -> Unit,
    onGoDownload: () -> Unit,
) {
    val tones = LocalVdTones.current
    var sortMenuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "Library",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (storageText.isNotBlank()) {
            Text(
                storageText,
                style = MaterialTheme.typography.bodySmall,
                color = tones.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
            LinearProgressIndicator(
                progress = { storagePercent.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp),
                color = tones.success,
                trackColor = MaterialTheme.colorScheme.outline,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onFindDuplicates) { Text("Find duplicates") }
            TextButton(onClick = onToggleView) {
                Text(LibraryCopy.viewToggleLabel(listMode), color = tones.muted)
            }
            Box {
                TextButton(onClick = { sortMenuOpen = true }) {
                    Text(sort.label, color = tones.muted)
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    LibrarySort.entries.forEach { entry ->
                        DropdownMenuItem(
                            text = { Text(entry.label) },
                            onClick = { sortMenuOpen = false; onSort(entry) },
                        )
                    }
                }
            }
        }

        if (selectedIds.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    LibraryCopy.selectionCount(selectedIds.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onShareSelected) { Text("Share") }
                TextButton(onClick = onMoveSelected) { Text("Move", color = tones.muted) }
                TextButton(onClick = onDeleteSelected) { Text("Delete", color = tones.danger) }
                TextButton(onClick = onClearSelection) { Text("✕", color = tones.muted) }
            }
        }

        if (!ready) {
            // Lock gate pending: the biometric prompt is system UI; render no content here.
            Box(Modifier.fillMaxSize())
            return@Column
        }

        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    LibraryCopy.EMPTY_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    LibraryCopy.EMPTY_HINT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tones.muted,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onGoDownload, shape = MaterialTheme.shapes.small) {
                    Text(LibraryCopy.EMPTY_ACTION)
                }
            }
            return@Column
        }

        val tap: (LibraryItem) -> Unit = { item ->
            if (selectedIds.isNotEmpty()) onToggleSelect(item) else onOpen(item)
        }

        if (listMode) {
            LazyColumn(
                Modifier.fillMaxSize().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    LibraryRow(item, selectedIds.contains(item.id), loadThumb, tap, onToggleSelect)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    LibraryCard(item, selectedIds.contains(item.id), loadThumb, tap, onToggleSelect)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryCard(
    item: LibraryItem,
    selected: Boolean,
    loadThumb: (LibraryItem, ImageView) -> Unit,
    onTap: (LibraryItem) -> Unit,
    onLongPress: (LibraryItem) -> Unit,
) {
    val tones = LocalVdTones.current
    Card(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { onTap(item) },
            onLongClick = { onLongPress(item) },
        ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = vdCardBorder,
    ) {
        Column {
            Box {
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    },
                    update = { iv -> loadThumb(item, iv) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f),
                )
                if (selected) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    )
                    Text(
                        "✓",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
            }
            Column(Modifier.padding(8.dp)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    badges(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = tones.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(
    item: LibraryItem,
    selected: Boolean,
    loadThumb: (LibraryItem, ImageView) -> Unit,
    onTap: (LibraryItem) -> Unit,
    onLongPress: (LibraryItem) -> Unit,
) {
    val tones = LocalVdTones.current
    Card(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { onTap(item) },
            onLongClick = { onLongPress(item) },
        ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                },
                update = { iv -> loadThumb(item, iv) },
                modifier = Modifier.size(96.dp, 60.dp).clip(MaterialTheme.shapes.extraSmall),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    badges(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = tones.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Text(
                    "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** Size + duration badges, using the tested formatter (0 → omitted, never "0 MB"). */
private fun badges(item: LibraryItem): String =
    listOf(LibraryFormat.size(item.size), LibraryFormat.duration(item.durationMs))
        .filter { it.isNotBlank() }
        .joinToString(" · ")