package com.xerxes.videodroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xerxes.videodroid.R
import com.xerxes.videodroid.ui.theme.LocalVdTones

/**
 * The Download (Main) screen, in Compose.
 *
 * Every side effect stays in the Activity — this file only renders state and reports intent, so
 * the download contracts (duplicate refusal BEFORE the intent, pause keeps the partial file,
 * MP4/MKV pipeline, failure reason in the state line) cannot be changed by a layout edit.
 */
@Composable
fun MainScreen(
    ui: MainUiState,
    url: String,
    settings: DownloadSettings,
    clipboardOffer: String?,
    crashBanner: Boolean,
    updateChip: String?,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    onGo: () -> Unit,
    onCancel: () -> Unit,
    onOpenResult: () -> Unit,
    onOpenFailedPage: () -> Unit,
    onOptions: () -> Unit,
    onScanPage: () -> Unit,
    onSettings: () -> Unit,
    onQuality: (String) -> Unit,
    onPreset: (SocialPreset) -> Unit,
    onClipboardDownload: () -> Unit,
    onClipboardDismiss: () -> Unit,
    onCrashView: () -> Unit,
    onCrashDismiss: () -> Unit,
    onUpdateChip: () -> Unit,
) {
    val tones = LocalVdTones.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // ── Header ──────────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "VideoDroid",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (updateChip != null) {
                    AssistChip(onClick = onUpdateChip, label = { Text(updateChip) })
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    MainCopy.statePill(ui.busy, ui.queueCount, ui.waiting, ui.progressVisible),
                    style = MaterialTheme.typography.bodySmall,
                    color = tones.muted,
                )
                IconButton(onClick = onSettings) {
                    Icon(
                        painterResource(R.drawable.ic_settings),
                        contentDescription = "Settings",
                        tint = tones.muted,
                    )
                }
            }
        }

        // ── Paste / URL bar ─────────────────────────────────────────────────────
        Card(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = url,
                    onValueChange = onUrlChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Paste a video link", color = tones.muted) },
                    maxLines = 2,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { onGo() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                IconButton(onClick = onPaste) {
                    Icon(
                        painterResource(R.drawable.ic_paste),
                        contentDescription = "Paste from clipboard",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ── Clipboard offer: explicit consent, never an auto-start ─────────────
        if (clipboardOffer != null) {
            Card(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Download this?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            clipboardOffer,
                            style = MaterialTheme.typography.bodySmall,
                            color = tones.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onClipboardDownload) { Text("Download") }
                    TextButton(onClick = onClipboardDismiss) { Text("✕", color = tones.muted) }
                }
            }
        }

        // ── One-time crash banner: the log is on disk, never uploaded ──────────
        if (crashBanner) {
            Card(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "VideoDroid crashed last time — view log?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCrashView) { Text("View") }
                    TextButton(onClick = onCrashDismiss) { Text("✕", color = tones.muted) }
                }
            }
        }

        // ── Primary action ──────────────────────────────────────────────────────
        Button(
            onClick = onGo,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(54.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(MainCopy.goLabel(ui.busy, settings.exportOn), style = MaterialTheme.typography.labelLarge)
        }
        if (MainCopy.showCancel(ui.busy)) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                Text("Cancel current", color = tones.danger)
            }
        }

        // ── Quality pills ───────────────────────────────────────────────────────
        Text(
            "QUALITY",
            style = MaterialTheme.typography.labelSmall,
            color = tones.muted,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MainCopy.QUALITIES.forEach { q ->
                FilterChip(
                    selected = settings.quality == q,
                    onClick = { onQuality(q) },
                    label = { Text(q.removeSuffix("p")) },
                )
            }
        }

        // ── Job card: the state line carries the reason, never the title ───────
        if (MainCopy.showJobCard(ui.statusText, ui.progressVisible)) {
            Card(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        ui.statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (ui.resultUri != null) {
                            Modifier.fillMaxWidth().clickable { onOpenResult() }
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    )
                    if (ui.progressVisible) {
                        Spacer(Modifier.height(12.dp))
                        val pct = ui.progressPercent
                        if (ui.progressIndeterminate || pct == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { pct.coerceIn(0, 100) / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        if (ui.openFailedVisible) {
            TextButton(
                onClick = onOpenFailedPage,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) { Text("Open page") }
        }

        // ── Options + page scan ─────────────────────────────────────────────────
        OutlinedButton(
            onClick = onOptions,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(48.dp),
            shape = MaterialTheme.shapes.small,
        ) { Text("Options") }
        TextButton(
            onClick = onScanPage,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) { Text("Scan page for videos") }

        // ── Social presets ──────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MainCopy.PRESETS.forEach { preset ->
                AssistChip(
                    onClick = { onPreset(preset) },
                    label = { Text(preset.name) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}