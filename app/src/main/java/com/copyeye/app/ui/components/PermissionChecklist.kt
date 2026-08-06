package com.copyeye.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.copyeye.app.ui.theme.SuccessGreen
import com.copyeye.app.ui.theme.WarnAmber

/** One thing CopyEye needs, and whether this phone has given it. */
data class PermissionStep(
    val title: String,
    val why: String,
    /** Null when the app has no way to read the state — OEM settings mostly. */
    val granted: Boolean?,
    val actionLabel: String,
    /** Where to find it, in the user's own settings app. Shown for steps we cannot verify. */
    val path: String? = null,
    val required: Boolean = true,
    val onAction: () -> Unit,
)

/**
 * The permission list, with live state.
 *
 * Built as a checklist rather than a sequence of dialogs for one reason: two of the things CopyEye
 * needs are not runtime permissions at all. They are settings screens, one of which is buried three
 * levels into an OEM's own security app, and neither of which returns a result. A user who taps
 * "Allow", wanders into Settings and comes back has no way of knowing whether it worked — unless
 * the answer is on screen when they return.
 */
@Composable
fun PermissionChecklist(
    steps: List<PermissionStep>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        steps.forEach { step -> PermissionRow(step) }
    }
}

@Composable
private fun PermissionRow(step: PermissionStep) {
    val done = step.granted == true
    // The tick springs in. It is the only confirmation the user gets for a step that happened in a
    // different app, so it is worth making unmissable.
    val tickScale by animateFloatAsState(
        targetValue = if (done) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "tick",
    )

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (done) {
            SuccessGreen.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (done) SuccessGreen else Color.Transparent,
                    border = if (done) {
                        null
                    } else {
                        androidx.compose.foundation.BorderStroke(
                            2.dp,
                            if (step.required) WarnAmber else MaterialTheme.colorScheme.outline,
                        )
                    },
                    modifier = Modifier.size(24.dp),
                ) {}
                if (tickScale > 0f) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Granted",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp).scale(tickScale),
                    )
                }
            }

            Spacer(Modifier.size(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(step.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = step.why,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!done && step.path != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = step.path,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }

            Spacer(Modifier.size(10.dp))

            if (!done) {
                if (step.required) {
                    Button(onClick = step.onAction) { Text(step.actionLabel) }
                } else {
                    OutlinedButton(onClick = step.onAction) { Text(step.actionLabel) }
                }
            }
        }
    }
}
