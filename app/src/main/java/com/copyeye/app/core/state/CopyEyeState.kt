package com.copyeye.app.core.state

/**
 * Every state CopyEye can occupy, from "the user has never enabled it" through to a finished copy.
 *
 * The states are deliberately flat rather than nested: the overlay service, the capture pipeline
 * and the selection UI all need to agree on a single current state, and a flat enum is the only
 * shape that survives being read from three processes' worth of callbacks without ambiguity.
 */
enum class CopyEyeState {
    /** Service is off. Nothing is drawn, nothing is captured. */
    Disabled,

    /** Service was asked to start but overlay and/or projection consent is missing. */
    PermissionRequired,

    /** Permissions are in place; the service is starting up. */
    Ready,

    /** The eye is visible at full opacity, waiting. */
    EyeIdle,

    /** Inactivity elapsed: the eye is translucent and partly tucked behind the screen edge. */
    EyeDimmed,

    /** A finger is moving the eye. Scans must not start from here. */
    Dragging,

    /** Long press held; the quick menu is on screen. */
    OpeningQuickMenu,

    /** A frame grab is in flight. */
    Capturing,

    /** OCR is running on a captured frame. */
    Scanning,

    /** OCR returned at least one text block. */
    TextDetected,

    /** The user is choosing text in the selection overlay. */
    Selecting,

    /** A copy is being written to the clipboard. */
    Copying,

    /** Copy succeeded; the confirmation is showing. */
    Copied,

    /** OCR completed but found nothing usable. */
    NoTextFound,

    /** The captured frame was blanked by FLAG_SECURE or DRM. */
    SecureScreen,

    /** Something failed; see the accompanying [CopyEyeError]. */
    Error,

    /** The user paused the eye; the service lives but draws nothing. */
    Paused,
    ;

    val isScanInFlight: Boolean
        get() = this == Capturing || this == Scanning

    val isOverlayInteractive: Boolean
        get() = this == EyeIdle || this == EyeDimmed || this == Dragging || this == OpeningQuickMenu
}

/** Failure causes that the UI has a distinct message and recovery action for. */
enum class CopyEyeError {
    OverlayPermissionDenied,
    ProjectionPermissionDenied,
    ProjectionStopped,
    CaptureTimeout,
    CaptureEmpty,
    OcrUnavailable,
    OcrFailed,

    /** Recognition ran but blew its time budget — usually a photo or a video frame. */
    OcrTimedOut,
    ClipboardUnavailable,
    LowMemory,
    Unknown,
}

/**
 * The legal transition table.
 *
 * This exists because the three most damaging bugs in an overlay app of this shape are all invalid
 * transitions: a drag that also fires a scan, a second OCR job launched by an impatient double tap,
 * and an overlay that outlives the service that owns it. Encoding the graph once and asserting
 * against it means those bugs fail loudly in tests instead of quietly on a user's phone.
 */
object CopyEyeStateMachine {

    private val allowed: Map<CopyEyeState, Set<CopyEyeState>> = mapOf(
        CopyEyeState.Disabled to setOf(
            CopyEyeState.PermissionRequired,
            CopyEyeState.Ready,
        ),
        CopyEyeState.PermissionRequired to setOf(
            CopyEyeState.Ready,
            CopyEyeState.Disabled,
            CopyEyeState.Error,
        ),
        CopyEyeState.Ready to setOf(
            CopyEyeState.EyeIdle,
            CopyEyeState.Disabled,
            CopyEyeState.PermissionRequired,
            CopyEyeState.Error,
        ),
        CopyEyeState.EyeIdle to setOf(
            CopyEyeState.EyeDimmed,
            CopyEyeState.Dragging,
            CopyEyeState.OpeningQuickMenu,
            CopyEyeState.Capturing,
            CopyEyeState.Paused,
            CopyEyeState.Disabled,
            CopyEyeState.PermissionRequired,
        ),
        CopyEyeState.EyeDimmed to setOf(
            CopyEyeState.EyeIdle,
            CopyEyeState.Dragging,
            CopyEyeState.OpeningQuickMenu,
            CopyEyeState.Capturing,
            CopyEyeState.Paused,
            CopyEyeState.Disabled,
            CopyEyeState.PermissionRequired,
        ),
        // Dragging deliberately cannot reach Capturing: a drag never becomes a scan.
        CopyEyeState.Dragging to setOf(
            CopyEyeState.EyeIdle,
            CopyEyeState.Disabled,
        ),
        CopyEyeState.OpeningQuickMenu to setOf(
            CopyEyeState.EyeIdle,
            CopyEyeState.Capturing,
            CopyEyeState.Paused,
            CopyEyeState.Disabled,
        ),
        // Capturing/Scanning cannot re-enter themselves: repeated taps are dropped, not queued.
        CopyEyeState.Capturing to setOf(
            CopyEyeState.Scanning,
            CopyEyeState.SecureScreen,
            CopyEyeState.EyeIdle,
            CopyEyeState.Error,
            CopyEyeState.Disabled,
        ),
        CopyEyeState.Scanning to setOf(
            CopyEyeState.TextDetected,
            CopyEyeState.NoTextFound,
            CopyEyeState.EyeIdle,
            CopyEyeState.Error,
            CopyEyeState.Disabled,
        ),
        CopyEyeState.TextDetected to setOf(
            CopyEyeState.Selecting,
            CopyEyeState.EyeIdle,
            CopyEyeState.Error,
            CopyEyeState.Disabled,
        ),
        CopyEyeState.Selecting to setOf(
            CopyEyeState.Copying,
            CopyEyeState.Scanning,
            CopyEyeState.EyeIdle,
            CopyEyeState.Error,
            CopyEyeState.Disabled,
        ),
        CopyEyeState.Copying to setOf(
            CopyEyeState.Copied,
            CopyEyeState.Error,
            CopyEyeState.Disabled,
        ),
        CopyEyeState.Copied to setOf(
            CopyEyeState.Selecting,
            CopyEyeState.EyeIdle,
            CopyEyeState.Disabled,
        ),
        CopyEyeState.NoTextFound to setOf(
            CopyEyeState.Selecting,
            CopyEyeState.EyeIdle,
            CopyEyeState.Capturing,
            CopyEyeState.Disabled,
        ),
        CopyEyeState.SecureScreen to setOf(
            CopyEyeState.EyeIdle,
            CopyEyeState.Disabled,
        ),
        CopyEyeState.Error to setOf(
            CopyEyeState.EyeIdle,
            CopyEyeState.PermissionRequired,
            CopyEyeState.Disabled,
        ),
        CopyEyeState.Paused to setOf(
            CopyEyeState.EyeIdle,
            CopyEyeState.Disabled,
        ),
    )

    fun canTransition(from: CopyEyeState, to: CopyEyeState): Boolean =
        from == to || allowed[from]?.contains(to) == true

    /** Returns [to] when the transition is legal, or [from] unchanged when it is not. */
    fun transition(from: CopyEyeState, to: CopyEyeState): CopyEyeState =
        if (canTransition(from, to)) to else from
}
