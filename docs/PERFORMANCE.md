# Performance

An overlay that sits on screen for hours has a different cost profile from an app you open and
close. Most of what follows is about the idle case, because that is where a floating utility earns
or loses its place on someone's phone.

---

## Idle cost

**Target: indistinguishable from not running.**

What is actually scheduled while CopyEye is on and nobody is touching it:

| Component | Idle cost |
|---|---|
| Virtual display | **No surface attached.** The compositor produces no frames. |
| `ImageReader` | Allocated, no listener attached, nothing arriving |
| `IrisEyeView` | **Nothing scheduled between blinks.** One `postDelayed` every 3–20 s |
| A blink | One `ValueAnimator`, 190 ms, then quiet again |
| Breathing animation | Only on `AnimationIntensity.Full`, which is not the default |
| Dim timer | One `postDelayed`, cancelled on touch |
| Capture thread | A `HandlerThread` parked on an empty looper |
| OCR engine | Lazily created; not instantiated until the first scan |
| Wake locks | None |
| Polling | None |

The idle design rests on one decision: **the eye animates by scheduling, not by running a frame
loop.** There is no `Choreographer` callback, no `postInvalidateOnAnimation` chain, no `INFINITE`
animator in the default configuration. Between blinks the view is as inert as a static drawable.

The second decision is the null surface. A virtual display rendering into an `ImageReader` costs a
composition pass and a buffer copy per frame, forever. Detaching the surface between scans takes
that to zero, and it is also what makes the privacy claim structural rather than behavioural.

Setting `AnimationIntensity` to `Off`, or turning on Reduced motion, removes even the blink timer.

---

## Scan latency

The budget, from tap to selectable text:

| Stage | Typical | Notes |
|---|---|---|
| Touch feedback | < 16 ms | Synchronous press scale-up on the touch-down event |
| Surface attach → first frame | 40–150 ms | Compositor-bound; one frame at the display's refresh rate |
| `Image` → `Bitmap` | 10–30 ms | One `copyPixelsFromBuffer` plus a crop when the row stride is padded |
| Crop + downscale | 15–40 ms | Skipped entirely when the frame is already under the cap |
| ML Kit recognition | 150–600 ms | The dominant term; scales with pixel count, not with text |
| Merge + selection index | < 10 ms | Linear in line count |

**Perceived** latency is much lower than the total, because the frozen frame and the scan wave appear
as soon as the capture lands — typically inside 200 ms. Recognition finishes underneath a UI that is
already showing the user their own screen.

Sub-one-second total is the design target and the common case on a mid-range or better phone. It is
not a guarantee, and the code does not behave as if it were: the wave loops rather than stalling,
the scan can be cancelled at any point, and the interface never blocks.

### Where the time actually goes

Recognition dominates, and recognition scales with pixels. That is why the only preprocessing that
survived is the part that removes pixels:

- **Crop the status bar.** A clock and some icons. Free.
- **Downscale to 1280 px** on the long edge in Fast mode, 1920 px in Accurate.

A 1440p screen has 3.7 million pixels; capped at 1280 px it has about 1.0 million. Roughly a 3.5×
reduction in the dominant term, for text that is still around 20 px tall — comfortably above what ML
Kit needs.

Contrast stretching, sharpening and adaptive thresholding were considered and left out. ML Kit's own
pipeline already binarises, and a hand-rolled pass in Kotlin costs 30–60 ms on a full-screen bitmap
to hand the recogniser something it was going to compute anyway.

### Two recognisers

Latin and Devanagari run **concurrently** on the same `InputImage`, so wall-clock cost is roughly the
slower of the two rather than their sum. Total CPU roughly doubles for the few hundred milliseconds
a scan lasts. A user who only ever reads English can turn Devanagari off in Scan settings and get
that back.

---

## Memory

The largest single allocation is the captured bitmap: at 1080×2400 in ARGB_8888 that is about 10 MB.

Rules that keep repeated scans flat:

1. `FrameStore` holds **at most one** frame; storing a new one releases the previous.
2. `FrameProcessor` recycles every intermediate it creates and never the caller's original unless it
   is replacing it.
3. `ScanViewModel.onCleared` releases the frame when the session ends, for any reason.
4. `CopyEyeApp.onTrimMemory` drops any pending frame at `TRIM_MEMORY_UI_HIDDEN` and closes the OCR
   engine's native handles at `TRIM_MEMORY_RUNNING_LOW`.
5. `MediaProjectionController` drains and closes every `Image` it acquires, including frames that
   arrive after the one it wanted.
6. `DeviceCapabilities.isUnderMemoryPressure` refuses a scan rather than starting one that will fail.

The `ImageReader` is created with `maxImages = 2`. One is enough for correctness; two means a frame
arriving while the previous is being converted is not dropped by the framework.

---

## Device tiers

`DeviceCapabilities` brackets the device by `ActivityManager.isLowRamDevice` and total RAM, and uses
it to pick *defaults*, never to block a feature:

| Tier | RAM | Smart Frame Mode | Low performance mode |
|---|---|---|---|
| Low | ≤ 2.6 GB or `isLowRamDevice` | off by default | suggested |
| Mid | 2.6–5.2 GB | off by default | off |
| High | > 5.2 GB | suggested | off |

Low performance mode caps the long edge at 960 px and skips multi-frame capture.

---

## Drag smoothness

Dragging updates a window position, not a view layout, so it does not go through measure or layout at
all:

- `WindowManager.updateViewLayout` with new `x`/`y` on each `ACTION_MOVE`.
- Gesture classification is pure arithmetic in `DragGestureHandler` — no allocation per event.
- The eye is a `LAYER_TYPE_NONE` view drawing a handful of circles. A hardware layer for a 42 dp view
  costs more in texture upload than it saves.
- Shaders are rebuilt on size and accent changes only, never in `onDraw`.
- Snap-back is a single 260 ms `ValueAnimator`; the overshoot interpolator gives the spring feel
  without pulling in the physics library.

---

## Release build

- R8 with `isMinifyEnabled` and `isShrinkResources`.
- ML Kit classes are kept explicitly — they are reached reflectively and a shrunk build fails to load
  the recogniser without those rules.
- `Log.d`, `Log.v` and `Log.i` are stripped via `-assumenosideeffects`, which is both a size win and
  the cheapest way to guarantee recognised text cannot reach a log.
- Per-ABI splits: a real device downloads about 13–17 MB rather than the 45 MB universal APK.

---

## Where the remaining headroom is

Ordered by payoff, and none of it is needed for a first release:

1. **Text-region detection before OCR.** Running ML Kit's detector on a downscaled frame first, then
   recognising only the regions it found at full resolution, would cut the dominant term
   substantially on sparse screens — which is most screens.
2. **Reuse a single `Bitmap` across scans.** The frame is the same size every time. A reusable buffer
   would remove a 10 MB allocation and its collection per scan.
3. **`RenderScript`-free GPU downscale.** The current downscale is `Bitmap.createScaledBitmap` on the
   CPU; a GPU path would save 15–40 ms.
4. **Recognise progressively.** ML Kit returns everything at once, but running it over horizontal
   bands would let the first results render while the rest is still working.
5. **Skip recognition when the frame is unchanged.** A cheap perceptual hash would make a repeat scan
   of a static screen nearly free.

These have not been implemented, and the numbers above are design estimates rather than measurements
— nothing in this build has been profiled on hardware yet.
