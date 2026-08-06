package com.copyeye.app.data.preferences

/** Which visual treatment Iris uses. */
enum class EyeStyle { Classic, Minimal, Ring }

/** Accent applied to the iris and to the scan wave. */
enum class EyeAccent { Violet, Cyan, Rose, Amber, Mono }

/** Which edge the eye prefers to rest against. */
enum class PreferredEdge { Auto, Left, Right }

/** How much idle motion Iris is allowed. */
enum class AnimationIntensity { Off, Subtle, Full }

/** Trades OCR accuracy against latency. */
enum class OcrMode { Fast, Accurate }

/** How detected text is outlined in the selection overlay. */
enum class HighlightStyle { Outline, Fill, Underline }

/**
 * How CopyEye gets a picture of the screen.
 *
 * [ScreenRecording] is Android's public API: works everywhere, no setup, and costs either a consent
 * dialog or a recording indicator. [Shizuku] runs the platform's own `screencap` with shell
 * privileges the user granted outside this app: no dialog and no indicator, but it needs Shizuku
 * installed and re-activated after each reboot on an unrooted phone.
 *
 * [Automatic] uses Shizuku when it is ready and falls back to screen recording otherwise, which is
 * what almost everyone should leave it on.
 */
enum class CaptureMethod { Automatic, ScreenRecording, Shizuku }

/**
 * How long a screen-capture session survives with no scanning.
 *
 * This is the app's central trade-off, and Android leaves no third option:
 *
 *  - Keep the session ([Never]) and the user is asked once, then every tap scans instantly — but
 *    the system's screen-recording indicator sits on the status bar the whole time.
 *  - Release it and the indicator goes — but a new session needs new consent, so the system dialog
 *    returns on the next scan.
 *
 * The indicator is raised by the session *existing*, not by frames being read, so no amount of care
 * inside the app can have both. [Never] is the default because a dialog on every scan makes the
 * one-tap promise meaningless, while an indicator is something a user learns to read correctly.
 */
enum class ProjectionIdleTimeout(val millis: Long?) {
    /** Zero grace. The icon is on screen only while a scan is actually happening. */
    Immediately(0L),

    /** Long enough that a burst of scans costs one dialog, short enough to feel momentary. */
    FifteenSeconds(15_000L),
    OneMinute(60_000L),
    ThreeMinutes(180_000L),

    /** Keep the session. One dialog per start, but the indicator stays up. */
    Never(null),
}

/** How long copied items survive in local history. */
enum class HistoryRetention(val millis: Long?) {
    OneHour(60L * 60_000L),
    OneDay(24L * 60L * 60_000L),
    SevenDays(7L * 24L * 60L * 60_000L),
    Forever(null),
}

/** OCR scripts the recogniser is allowed to load. */
enum class OcrScript { Latin, Devanagari }

/**
 * Every user-tunable value in one immutable snapshot.
 *
 * The overlay service reads this on a hot path (each drag frame consults [eyeSizeDp] and
 * [idleOpacity]), so it is a plain data class held in memory and refreshed from a Flow rather than
 * being read from DataStore per frame.
 */
data class AppSettings(
    // --- Appearance ---
    val eyeStyle: EyeStyle = EyeStyle.Classic,
    val eyeSizeDp: Int = 42,
    val eyeAccent: EyeAccent = EyeAccent.Violet,
    val preferredEdge: PreferredEdge = PreferredEdge.Auto,
    val animationIntensity: AnimationIntensity = AnimationIntensity.Subtle,
    val blinkIntervalSeconds: Int = 6,
    val hapticsEnabled: Boolean = true,
    val reducedMotion: Boolean = false,

    // --- Auto-hide ---
    val autoHideEnabled: Boolean = true,
    val autoDimDelayMs: Long = 3_000L,
    val idleOpacity: Float = 0.30f,
    val edgePeekFraction: Float = 0.30f,
    val autoRepositionEnabled: Boolean = true,

    // --- Scanning ---
    val scripts: Set<OcrScript> = setOf(OcrScript.Latin, OcrScript.Devanagari),
    val ocrMode: OcrMode = OcrMode.Fast,
    val smartFrameMode: Boolean = false,
    val captureMethod: CaptureMethod = CaptureMethod.Automatic,
    val projectionIdleTimeout: ProjectionIdleTimeout = ProjectionIdleTimeout.Never,
    val autoCopySingleLine: Boolean = false,
    val closeAfterCopy: Boolean = true,
    val closeAfterCopyDelayMs: Long = 550L,
    val highlightStyle: HighlightStyle = HighlightStyle.Outline,
    val backgroundDimAmount: Float = 0.45f,

    // --- History ---
    val historyEnabled: Boolean = false,
    val historyRetention: HistoryRetention = HistoryRetention.OneDay,

    // --- Device ---
    val lowPerformanceMode: Boolean = false,

    // --- Onboarding / position ---
    val onboardingComplete: Boolean = false,
    /** Last resting position as a fraction of the usable overlay bounds, so it survives rotation. */
    val eyePositionXFraction: Float = 1f,
    val eyePositionYFraction: Float = 0.42f,
) {
    /** True when Iris should not animate at all — either the user asked, or the system did. */
    val motionSuppressed: Boolean
        get() = reducedMotion || animationIntensity == AnimationIntensity.Off

    /** Effective idle alpha; auto-hide off means the eye never dims. */
    val effectiveIdleOpacity: Float
        get() = if (autoHideEnabled) idleOpacity else 1f
}
