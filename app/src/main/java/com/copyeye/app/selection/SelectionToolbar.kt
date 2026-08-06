package com.copyeye.app.selection

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.copyeye.app.ocr.SmartAction

/**
 * The floating bar the user actually acts through.
 *
 * Copy is a filled button and everything else is an icon, because copying is the entire product and
 * the rest is convenience. The bar scrolls horizontally rather than wrapping: a second row would
 * cover more of the text the user is trying to read.
 */
@Composable
fun SelectionToolbar(
    hasSelection: Boolean,
    mode: SelectionMode,
    regionMode: Boolean,
    smartActions: List<SmartAction>,
    onModeChange: (SelectionMode) -> Unit,
    onCopy: () -> Unit,
    onCopyAll: () -> Unit,
    onEdit: () -> Unit,
    onToggleRegion: () -> Unit,
    onRescan: () -> Unit,
    onShare: () -> Unit,
    onSmartAction: (SmartAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {

            if (smartActions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    smartActions.forEach { action ->
                        AssistChip(
                            onClick = { onSmartAction(action) },
                            label = { Text(labelFor(action)) },
                            leadingIcon = {
                                Icon(iconFor(action), contentDescription = null)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionMode.entries.forEach { candidate ->
                    FilterChip(
                        selected = mode == candidate && !regionMode,
                        onClick = { onModeChange(candidate) },
                        label = { Text(candidate.label) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onCopy,
                    enabled = hasSelection,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy")
                }

                Spacer(Modifier.width(6.dp))

                ToolbarIcon(Icons.Rounded.SelectAll, "Copy all", onCopyAll)
                ToolbarIcon(Icons.Rounded.Edit, "Edit before copying", onEdit)
                ToolbarIcon(
                    icon = Icons.Rounded.Crop,
                    description = if (regionMode) "Cancel region select" else "Select a region",
                    onClick = onToggleRegion,
                )
                ToolbarIcon(Icons.Rounded.Refresh, "Rescan", onRescan)
                ToolbarIcon(Icons.Rounded.Share, "Share", onShare)
                ToolbarIcon(Icons.Rounded.Close, "Close", onClose)
            }
        }
    }
}

@Composable
private fun ToolbarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        // 48dp is the accessible minimum and also what a thumb actually hits on a moving bar.
        modifier = Modifier.height(48.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) {
        Icon(icon, contentDescription = description)
    }
}

private val SelectionMode.label: String
    get() = when (this) {
        SelectionMode.Word -> "Word"
        SelectionMode.Line -> "Line"
        SelectionMode.Paragraph -> "Paragraph"
    }

private fun labelFor(action: SmartAction): String = when (action) {
    is SmartAction.OpenUrl -> "Open link"
    is SmartAction.Call -> "Call"
    is SmartAction.Email -> "Email"
    is SmartAction.Map -> "Maps"
}

private fun iconFor(action: SmartAction): ImageVector = when (action) {
    is SmartAction.OpenUrl -> Icons.Rounded.Language
    is SmartAction.Call -> Icons.Rounded.Call
    is SmartAction.Email -> Icons.Rounded.Email
    is SmartAction.Map -> Icons.Rounded.Place
}
