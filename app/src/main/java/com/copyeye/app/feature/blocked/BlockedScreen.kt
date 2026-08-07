package com.copyeye.app.feature.blocked

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.copyeye.app.remote.RemoteAdmin
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/**
 * What a suspended install sees instead of the app.
 *
 * It shows the reason and the reference code the admin chose, and when the block lifts if it is
 * timed. It deliberately does not show the install id: the person reading this screen does not
 * need to know which row to go and argue about.
 */
@Composable
fun BlockedScreen(status: RemoteAdmin.Status) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .systemBarsPadding()
            .padding(horizontal = 36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        Spacer(Modifier.height(22.dp))
        Text(
            text = "Access suspended",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = status.reason ?: "This install has been suspended.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(18.dp))
        Text(
            text = formatUntil(status.until)
                ?.let { "Access returns on $it." }
                ?: "This suspension does not expire on its own.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (status.code != null) {
            Spacer(Modifier.height(26.dp))
            Text(
                text = "Reference ${status.code}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun formatUntil(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return runCatching {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date.from(Instant.parse(iso)))
    }.getOrNull()
}
