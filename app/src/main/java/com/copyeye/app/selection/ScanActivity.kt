package com.copyeye.app.selection

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copyeye.app.core.common.ApiLevel
import com.copyeye.app.overlay.FloatingEyeService
import com.copyeye.app.ocr.SmartAction
import com.copyeye.app.ui.theme.CopyEyeTheme
import kotlinx.coroutines.delay

/**
 * The full-screen text-selection session.
 *
 * ## Why an activity rather than another overlay window
 *
 * The floating eye has to be an overlay — it lives above other apps. This does not. It appears
 * because the user asked for it, it takes the whole screen, and it goes away when they are done.
 *
 * Making it an activity buys correct back-button handling, a real IME for the edit sheet, working
 * TalkBack focus order, and system-managed lifecycle for the several megabytes of bitmap it holds.
 * A touchable full-screen overlay would have to reimplement all four, and would be indistinguishable
 * from a tapjacking overlay to both the system and a suspicious user.
 *
 * It is excluded from recents and finishes on its own, so the user lands back in the app they were
 * reading with no visible detour.
 */
class ScanActivity : ComponentActivity() {

    private val viewModel: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastStartedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val regionMode = intent.getBooleanExtra(EXTRA_REGION_MODE, false)
        val secureScreen = intent.getBooleanExtra(EXTRA_SECURE_SCREEN, false)
        viewModel.start(regionMode = regionMode, secureScreen = secureScreen)

        setContent {
            CopyEyeTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                ScanScreen(
                    state = state,
                    viewModel = viewModel,
                    onClose = { finishQuietly() },
                    onOpenSmartAction = ::launchSmartAction,
                    onShare = ::shareSelected,
                )
            }
        }
    }

    /**
     * Leaves without an exit animation, so the app underneath is simply there again.
     *
     * The theme already sets `windowAnimationStyle` to null; this covers the OEM skins that ignore
     * it. `overridePendingTransition` was deprecated in Android 14 in favour of
     * `overrideActivityTransition`, which has to be called *before* finishing.
     */
    private fun finishQuietly() {
        if (ApiLevel.hasActivityTransitionOverrides) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            finish()
        } else {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun launchSmartAction(action: SmartAction) {
        val intent = when (action) {
            is SmartAction.OpenUrl -> Intent(Intent.ACTION_VIEW, action.value.toUri())
            is SmartAction.Call -> Intent(
                Intent.ACTION_DIAL,
                "tel:${action.value.filter { it.isDigit() || it == '+' }}".toUri(),
            )
            is SmartAction.Email -> Intent(Intent.ACTION_SENDTO, "mailto:${action.value}".toUri())
            is SmartAction.Map -> Intent(
                Intent.ACTION_VIEW,
                "geo:0,0?q=${Uri.encode(action.value)}".toUri(),
            )
        }
        try {
            startActivity(intent)
            finishQuietly()
        } catch (e: ActivityNotFoundException) {
            // Nothing on the device handles it; the text is still on the clipboard.
        }
    }

    private fun shareSelected() {
        val text = viewModel.selectedText()
        if (text.isBlank()) return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(share, null))
            finishQuietly()
        } catch (e: ActivityNotFoundException) {
            // No share targets installed.
        }
    }

    /** Tells the service to play the copy flourish on Iris. */
    private fun notifyCopied() {
        runCatching { startService(FloatingEyeService.copiedIntent(this)) }
    }

    companion object {
        /**
         * When this activity last reached `onCreate`.
         *
         * The service uses it to find out whether its `startActivity` actually produced a screen.
         * Some OEM builds — Xiaomi's MIUI most prominently — gate background activity starts behind
         * a permission of their own, and refuse them *silently*: no exception, no log, nothing.
         * From the user's side that is a floating button that does nothing when tapped, with no
         * explanation anywhere. Watching for the activity that never arrived is the only way to
         * turn that into a message.
         */
        @Volatile
        var lastStartedAtElapsedMs: Long = 0L
            private set

        private const val EXTRA_REGION_MODE = "region_mode"
        private const val EXTRA_SECURE_SCREEN = "secure_screen"

        fun intent(context: Context, regionMode: Boolean): Intent =
            Intent(context, ScanActivity::class.java)
                .putExtra(EXTRA_REGION_MODE, regionMode)

        fun secureScreenIntent(context: Context): Intent =
            Intent(context, ScanActivity::class.java)
                .putExtra(EXTRA_SECURE_SCREEN, true)
    }

    @Composable
    private fun ScanScreen(
        state: ScanUiState,
        viewModel: ScanViewModel,
        onClose: () -> Unit,
        onOpenSmartAction: (SmartAction) -> Unit,
        onShare: () -> Unit,
    ) {
        // A copy schedules the close rather than performing it, so the "Copied" confirmation gets
        // its moment on screen before the activity disappears.
        var closeRequested by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(false)
        }
        LaunchedEffect(closeRequested) {
            if (closeRequested) {
                delay(viewModel.closeDelayMs)
                onClose()
            }
        }

        // The toolbar reports its own height so the frozen frame can be fitted above it. Measuring
        // rather than hard-coding keeps them in step when the smart-action chips appear.
        var toolbarHeightPx by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(0f)
        }
        // Collapsed to start: the frozen frame is fitted above the toolbar, so every dp the bar
        // takes is a dp of the user's screen shown smaller. Copy is the only control most scans
        // need, and it stays visible either way — the rest is one tap on the handle away.
        var toolbarExpanded by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(false)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (state) {
                is ScanUiState.Scanning -> {
                    state.frame?.let { frame ->
                        FrozenFrameLayer(
                            frame = frame,
                            result = null,
                            highlights = emptyList(),
                            highlightStyle = viewModel.highlightStyle,
                            dimAmount = viewModel.dimAmount,
                            regionMode = false,
                            onTap = { _, _, _ -> },
                            onDragStart = { _, _, _ -> },
                            onDragTo = { _, _, _ -> },
                            onRegion = {},
                        )
                    }
                    ScanningBanner(reducedMotion = viewModel.reducedMotion, onCancel = onClose)
                }

                is ScanUiState.Ready -> {
                    FrozenFrameLayer(
                        frame = state.frame,
                        result = state.result,
                        highlights = viewModel.highlightRects(),
                        highlightStyle = viewModel.highlightStyle,
                        dimAmount = viewModel.dimAmount,
                        regionMode = state.regionMode,
                        onTap = viewModel::onTap,
                        onDragStart = viewModel::onDragStart,
                        onDragTo = viewModel::onDragTo,
                        onRegion = viewModel::onRegionSelected,
                        bottomInsetPx = toolbarHeightPx,
                    )

                    SelectionToolbar(
                        hasSelection = state.hasSelection,
                        mode = state.mode,
                        regionMode = state.regionMode,
                        smartActions = state.smartActions,
                        onModeChange = viewModel::setMode,
                        onCopy = {
                            if (viewModel.copySelection()) closeRequested = true
                            notifyCopied()
                        },
                        onCopyAll = {
                            if (viewModel.copyAll()) closeRequested = true
                            notifyCopied()
                        },
                        onEdit = viewModel::beginEditing,
                        onToggleRegion = { viewModel.setRegionMode(!state.regionMode) },
                        onRescan = {
                            val region = viewModel.highlightRects()
                                .reduceOrNull { acc, rect -> acc.union(rect) }
                            if (region != null) viewModel.rescanRegion(region)
                        },
                        onShare = onShare,
                        onSmartAction = onOpenSmartAction,
                        onClose = onClose,
                        expanded = toolbarExpanded,
                        onToggleExpanded = { toolbarExpanded = !toolbarExpanded },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                            .onSizeChanged { toolbarHeightPx = it.height.toFloat() },
                    )

                    CopiedToast(
                        visible = state.justCopied,
                        characterCount = viewModel.selectedText().length,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 180.dp),
                    )

                    if (state.editing) {
                        EditDialog(
                            text = state.editedText,
                            onTextChange = viewModel::onEditedTextChanged,
                            onDismiss = viewModel::cancelEditing,
                            onCopy = {
                                if (viewModel.copySelection()) closeRequested = true
                                notifyCopied()
                            },
                        )
                    }
                }

                is ScanUiState.NoText -> ScanMessage(
                    title = "No text found",
                    body = "Nothing readable was on screen. Try again once the text is fully " +
                        "visible, or zoom in first.",
                    actionLabel = "Close",
                    onAction = onClose,
                )

                ScanUiState.SecureScreen -> ScanMessage(
                    title = "This screen is protected",
                    body = "The app you are using does not allow its screen to be captured. " +
                        "CopyEye cannot scan it.",
                    actionLabel = "Close",
                    onAction = onClose,
                )

                is ScanUiState.Failed -> ScanMessage(
                    title = "Scan failed",
                    body = messageFor(state),
                    actionLabel = "Close",
                    onAction = onClose,
                )
            }
        }
    }

    private fun messageFor(state: ScanUiState.Failed): String = when (state.error) {
        com.copyeye.app.core.state.CopyEyeError.CaptureTimeout ->
            "The screen took too long to capture. Try again."
        com.copyeye.app.core.state.CopyEyeError.CaptureEmpty ->
            "Nothing came back from the capture. Try again."
        com.copyeye.app.core.state.CopyEyeError.ScanScreenBlocked ->
            "Your phone blocked CopyEye from opening this screen over another app."
        com.copyeye.app.core.state.CopyEyeError.OcrTimedOut ->
            "This screen took too long to read. Photos and video frames are much slower than app " +
                "text — try zooming in on just the part you want, then scanning again."
        com.copyeye.app.core.state.CopyEyeError.OcrUnavailable ->
            "The text recogniser could not start on this device."
        com.copyeye.app.core.state.CopyEyeError.LowMemory ->
            "Not enough free memory to scan right now. Close an app and try again."
        com.copyeye.app.core.state.CopyEyeError.ClipboardUnavailable ->
            "The clipboard is not available right now."
        else -> "Something went wrong. Try again."
    }
}

@Composable
private fun EditDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit before copying") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxSize(),
            )
        },
        confirmButton = { TextButton(onClick = onCopy) { Text("Copy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
