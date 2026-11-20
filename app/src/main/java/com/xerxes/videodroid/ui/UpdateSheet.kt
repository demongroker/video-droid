package com.xerxes.videodroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xerxes.videodroid.ui.theme.LocalVdTones

/**
 * The updater progress sheet, in Compose.
 *
 * Rendered inside the Activity's own window (not a dialog), so the FLAG_SECURE window flag
 * already covers it — update versions and file sizes never reach a screenshot or the Recents
 * thumbnail. All actions report intent; the Activity owns the single-flight start, the cancel
 * (which keeps the `.part` file for resume) and the fail-closed install path.
 */
@Composable
fun UpdateSheet(
    model: UpdateSheetModel,
    onDismiss: () -> Unit,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
    onLater: () -> Unit,
    onToggleWifi: () -> Unit,
) {
    val tones = LocalVdTones.current
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
    ) {
        Card(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
                // Consume taps so the scrim behind the sheet does not dismiss it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    model.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                val toneColor = when (model.line.tone) {
                    UpdateSheetTone.GOOD -> tones.success
                    UpdateSheetTone.WARN -> tones.warning
                    UpdateSheetTone.BAD -> tones.danger
                    UpdateSheetTone.BUSY -> MaterialTheme.colorScheme.primary
                    UpdateSheetTone.NEUTRAL -> tones.muted
                }
                Text(
                    model.line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (model.line.tone == UpdateSheetTone.BAD) FontWeight.SemiBold else FontWeight.Normal,
                    color = toneColor,
                )
                if (model.showProgress) {
                    Spacer(Modifier.height(12.dp))
                    val pct = model.progressPercent
                    if (pct == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { pct.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (model.sizeText.isNotBlank() || model.speedText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            model.sizeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = tones.muted,
                        )
                        Text(
                            model.speedText,
                            style = MaterialTheme.typography.bodySmall,
                            color = tones.muted,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (model.primary != null) {
                        Button(onClick = onPrimary, shape = MaterialTheme.shapes.small) {
                            Text(model.primaryLabel)
                        }
                    }
                    if (model.showCancel) {
                        TextButton(onClick = onCancel) { Text("Cancel", color = tones.danger) }
                    }
                    if (model.showLater) {
                        TextButton(onClick = onLater) { Text("Later") }
                    }
                }
                if (model.showWifiToggle) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Wi-Fi only",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Switch(checked = model.wifiOnly, onCheckedChange = { onToggleWifi() })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}