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
 * How long a screen-capture session survives with no scanning.
 *
 * Android shows a screen-recording indicator for as long as the session exists — it cannot be
 * suppressed, and should not be. Releasing the session when it is not being used is the only
 * honest way to make the indicator go away, at the cost of a fresh consent dialog next time.
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
    val projectionIdleTimeout: ProjectionIdleTimeout = ProjectionIdleTimeout.Immediately,
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
