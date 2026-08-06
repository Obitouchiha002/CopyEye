package com.copyeye.app.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copyeye.app.AppContainer
import com.copyeye.app.clipboard.ClipItem
import com.copyeye.app.data.preferences.AppSettings
import com.copyeye.app.ui.components.DetailScaffold
import kotlinx.coroutines.launch

/**
 * The history list.
 *
 * When history is off — which is the default — this screen says so and offers nothing else. There
 * is no empty list to browse and no gentle nudge to turn it on, because a feature that stores
 * everything the user copies should stay off until they specifically want it.
 */
@Composable
fun HistoryScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by container.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    val items by container.historyRepository.items.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(settings.historyEnabled, settings.historyRetention) {
        if (settings.historyEnabled) container.historyRepository.load(settings.historyRetention)
    }

    DetailScaffold(title = "Clipboard history", onBack = onBack, scrollable = false) {
        if (!settings.historyEnabled) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("History is off", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "CopyEye is not keeping a record of what you copy. You can turn " +
                            "this on in Scan settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@DetailScaffold
        }

        val visible = remember(items, query) { container.historyRepository.search(query) }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { scope.launch { container.historyRepository.clearAll() } },
                enabled = items.isNotEmpty(),
            ) {
                Text("Clear all")
            }
        }

        if (visible.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (query.isBlank()) "Nothing copied yet." else "No matches.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@DetailScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visible, key = { it.id }) { item ->
                HistoryRow(
                    item = item,
                    onCopy = { container.clipboardWriter.copy(item.text) },
                    onTogglePin = {
                        scope.launch {
                            container.historyRepository.setPinned(item.id, !item.pinned)
                        }
                    },
                    onDelete = { scope.launch { container.historyRepository.delete(item.id) } },
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: ClipItem,
    onCopy: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.preview,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onTogglePin) {
                Icon(
                    Icons.Rounded.PushPin,
                    contentDescription = if (item.pinned) "Unpin" else "Pin",
                    tint = if (item.pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy again")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete")
            }
        }
    }
}
