package com.copyeye.app.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.copyeye.app.remote.RemoteAdmin
import com.copyeye.app.ui.components.IrisMark
import kotlinx.coroutines.launch

/**
 * The first thing a new install shows.
 *
 * It is not a sign-in: no password, no email, no account, and nothing is verified. You type a name
 * and the app opens. It exists so the control panel lists people rather than Android IDs.
 *
 * The backend owns uniqueness, because two people can choose the same name in the same second and
 * only the database can settle that. If the backend cannot be reached the name is accepted anyway —
 * this screen must never become the reason someone cannot use an app that works offline.
 */
@Composable
fun NameGateScreen(onDone: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val trimmed = name.trim()
        if (trimmed.length < 2) {
            error = "Give it at least two characters."
            return
        }
        busy = true
        error = null
        scope.launch {
            when (RemoteAdmin.claimName(context, trimmed)) {
                "taken" -> {
                    error = "Someone already goes by that. Try another."
                    busy = false
                }
                "invalid" -> {
                    error = "Letters, numbers, spaces and _ - . only."
                    busy = false
                }
                // "ok" and "offline" both continue.
                else -> onDone(trimmed)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IrisMark(modifier = Modifier.size(104.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            text = "What should we call you?",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Just a name — no password, no email, no account. " +
                "Your scans and copied text stay on this phone either way.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.take(40)
                error = null
            },
            singleLine = true,
            isError = error != null,
            label = { Text("Your name") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = ::submit,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Start")
            }
        }
    }
}
