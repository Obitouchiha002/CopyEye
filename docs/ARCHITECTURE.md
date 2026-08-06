# Architecture

## The shape of the problem

CopyEye is three long-lived things that have to agree with each other:

1. a **window drawn over other apps**, alive for hours while the user does something else,
2. a **screen-capture session**, which Android guards more tightly than almost anything else, and
3. a **momentary full-screen UI** that appears, does one job, and vanishes.

Each has a different lifecycle owner — a service, a system-granted token, and an activity — and none
of them can hold a reference to the others safely. The architecture is mostly about that.

---

## Module layout

One Gradle module. The separation is by package, enforced by dependency direction rather than by
build files.

```
core/            no dependencies on anything else in the app
  common/        ApiLevel, Haptics, DeviceCapabilities
  permissions/   PermissionChecker, PermissionSnapshot
  state/         CopyEyeState, CopyEyeStateMachine, CopyEyeBus

data/            depends on core
  preferences/   AppSettings, SettingsRepository

clipboard/       depends on core + data
capture/         depends on core + data
ocr/             depends on core + data + capture
selection/       depends on all of the above
overlay/         depends on all of the above
feature/         screens; depends on everything
ui/              theme, nav, shared components
```

**Why one module.** The app is about 5,000 lines. Splitting it into a dozen Gradle modules would buy
parallel compilation the build does not need and cost a `build.gradle.kts` per module plus a
convention-plugin layer to keep them consistent. The interfaces that matter for testing
(`TextRecognitionEngine`) exist regardless of module boundaries.

**Why no dependency-injection framework.** The wiring graph is nine objects, listed in
`AppContainer.kt`. Hilt would add a code generator and a plugin, and the class most awkward to inject
into — the overlay service — is constructed by the system rather than by us.

**Why no Room.** Clipboard history is an opt-in list capped at 200 items, needing append, prune and a
substring search. That is a JSON file. A database would add a schema, migrations and KSP for nothing.

---

## Permission flow

Two permissions, granted in completely different ways, so they are handled separately.

```
                    ┌──────────────────────────────────────────┐
                    │  User taps "Start CopyEye"               │
                    └────────────────┬─────────────────────────┘
                                     │
                     ┌───────────────▼───────────────┐
                     │ canDrawOverlays()?            │
                     └───┬───────────────────────┬───┘
                     no  │                       │ yes
          ┌──────────────▼──────────┐            │
          │ ACTION_MANAGE_OVERLAY_  │            │
          │ PERMISSION settings page│            │
          │ (falls back to app info │            │
          │  on OEMs without it)    │            │
          └──────────────┬──────────┘            │
                         └───────────┬───────────┘
                                     │
                     ┌───────────────▼────────────────┐
                     │ POST_NOTIFICATIONS (API 33+)   │
                     │ asked, not required            │
                     └───────────────┬────────────────┘
                                     │
                     ┌───────────────▼────────────────┐
                     │ createScreenCaptureIntent()    │
                     │ system consent dialog          │
                     └───────────────┬────────────────┘
                          RESULT_OK  │
                     ┌───────────────▼────────────────┐
                     │ startForegroundService(        │
                     │   ACTION_START + result)       │
                     └───────────────┬────────────────┘
                                     │
                     ┌───────────────▼────────────────┐
                     │ Service.startForeground(       │
                     │   TYPE_MEDIA_PROJECTION)       │
                     └───────────────┬────────────────┘
                                     │
                     ┌───────────────▼────────────────┐
                     │ getMediaProjection(result)     │
                     │ createVirtualDisplay(null)     │
                     └────────────────────────────────┘
```

The order at the bottom is not a preference. On Android 14 and above, `getMediaProjection` throws
`SecurityException` unless a foreground service of type `mediaProjection` is *already running*. That
is why the consent result travels from `MainActivity` into a service start command rather than being
used where it arrives.

`ApiLevel` holds every remaining version gate — window metrics (R), `VibratorManager` (S),
`POST_NOTIFICATIONS` (T), the strict media-projection rules (U) — each annotated with
`@ChecksSdkIntAtLeast` so lint can still verify `NewApi` at the call sites rather than being blinded
by the indirection.

---

## Screen-capture lifecycle

This is the part with a hard constraint that shapes everything else.

> **Android 14+:** `MediaProjection.createVirtualDisplay` may be called **once** per granted
> projection. A second call throws. A new grant requires a new consent dialog.

So "create a capture session per scan" is not available — the user would see a system dialog on every
tap. The session must be long-lived.

But a long-lived virtual display rendering into an `ImageReader` means the compositor is producing
frames of the user's screen continuously, whether or not anyone reads them. "We only read the buffer
when you tap" is a much weaker claim than the product should be making.

**The resolution: hold no session at all except during a scan.**

The first version of this kept one session alive for the whole time CopyEye was on, and gated the
*surface* instead — idle meant a virtual display with no surface, producing no frames. That was
correct, and it was not enough. Android shows a screen-recording indicator for as long as a session
*exists*, regardless of whether any frame is produced, and no app may suppress it. Users read a
permanent recording indicator as "this app is watching me", and they are not wrong to be cautious.

So the session is now as short-lived as the scan:

```
  CopyEye ON
      │                                          ← foreground service, type specialUse
      ├─ floating eye on screen                     NO projection, NO indicator
      │
      │   idle, for hours …                      ← zero screen access of any kind
      │
      ├─ user taps Iris
      │     ├─ consent dialog (Android's; unavoidable per session)
      │     ├─ startForeground(TYPE_MEDIA_PROJECTION)   ← required before the next line
      │     ├─ getMediaProjection(result)
      │     ├─ createVirtualDisplay(surface = imageReader.surface)
      │     ├─ await first frame  (timeout 2.5 s)
      │     ├─ Image → Bitmap, cropping the row-stride padding
      │     ├─ projection.stop()                       ← indicator disappears here
      │     └─ startForeground(TYPE_SPECIAL_USE)       ← back to eye-only
      │
      │   OCR and selection run on the bitmap    ← no screen access needed any more
      │
      └─ CopyEye OFF  →  service stops, overlay removed
```

Screen access exists only between the consent dialog and the frame landing in memory — a few hundred
milliseconds. Everything after that, including recognition and the whole selection session, works on
a bitmap.

**The two foreground-service types.** Android requires a foreground service to keep an overlay alive
for hours, and requires that service to declare a type. `mediaProjection` cannot be the idle type —
it is what raises the indicator. So the service declares `mediaProjection|specialUse` and calls
`startForeground` with whichever applies: `specialUse` at rest, `mediaProjection` for the moment a
scan needs it. `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` in the manifest states why.

**The cost, stated plainly.** A fresh session needs fresh consent, so Android's dialog appears on
each scan that starts from a released session. `ProjectionIdleTimeout` lets the user trade that back:
`Immediately` (the default) means no indicator between scans and a dialog each time;
`15 seconds`/`1 minute`/`3 minutes` keep the session briefly so a burst of scans costs one dialog;
`Never` keeps it for the session and accepts the permanent indicator. Tapping Iris with no session
raises the dialog and then scans, so it stays one gesture either way.

`MediaProjection.Callback.onStop` fires when the user revokes capture from the system UI or another
app takes the projection. CopyEye tears down, updates the notification, and the next tap routes the
user back to `MainActivity` to reconnect.

### Frame handoff

A capture is several megabytes. `Intent` extras cap out well below that, so `FrameStore` — a
single-slot in-memory holder — passes it from the service to `ScanActivity`. Both live in the same
process. The slot holds at most one frame; storing a new one releases the old, so a frame can never
outlive the scan that produced it, and `ScanViewModel.onCleared` releases whatever it took.

---

## Floating-eye state machine

`CopyEyeState` is a flat enum with an explicit transition table in `CopyEyeStateMachine`. Flat rather
than nested because three lifecycles need to agree on one current value.

```
                    Disabled
                       │
              ┌────────┴────────┐
              ▼                 ▼
     PermissionRequired ───► Ready
                                │
                                ▼
                    ┌──────► EyeIdle ◄──────┐
                    │      ╱   │   ╲        │
                    │     ╱    │    ╲       │
                    │    ▼     ▼     ▼      │
                    │ EyeDimmed │  Dragging │   ← Dragging has NO edge to Capturing
                    │    │      │     │     │
                    │    └──────┼─────┘     │
                    │           │           │
                    │           ▼           │
                    │   OpeningQuickMenu ───┤
                    │           │           │
                    │           ▼           │
                    │       Capturing       │
                    │       ╱   │   ╲       │
                    │      ▼    ▼    ▼      │
                    │ Secure  Scanning Error│
                    │ Screen    │           │
                    │      ┌────┴────┐      │
                    │      ▼         ▼      │
                    │ TextDetected NoTextFound
                    │      │                │
                    │      ▼                │
                    │  Selecting            │
                    │      │                │
                    │      ▼                │
                    │   Copying ──► Copied ─┘
                    │
                    └── Paused
```

Three edges are missing on purpose, and each corresponds to a real bug:

- **`Dragging → Capturing` does not exist.** A drag never becomes a scan.
- **`Capturing → Capturing` and `Scanning → Capturing` do not exist.** Impatient repeat taps are
  dropped, not queued; a second scan cannot start while one is in flight.
- **Every state can reach `Disabled`.** Service teardown always removes the overlay.

`CopyEyeStateMachine.transition` returns the old state when a transition is illegal, so a bug is a
no-op rather than a crash. `StateMachineTest` asserts the missing edges directly.

Gesture classification lives in `DragGestureHandler`, free of `MotionEvent`, so tap-versus-drag is
unit-tested rather than tested by hand on one phone. Geometry lives in `EdgeSnapController`, free of
Android types, so rotation and cutout behaviour is unit-tested too.

---

## OCR pipeline

```
ScreenFrame (raw capture, e.g. 1080 × 2400)
   │
   ├─ FrameProcessor.prepare
   │    ├─ crop the status bar          (clock and icons, never worth copying)
   │    ├─ downscale to ≤1280px         (Fast) or ≤1920px (Accurate)
   │    └─ record offset + scale on the frame, for the inverse mapping
   │
   ├─ MlKitTextRecognitionEngine.recognize
   │    ├─ Latin recogniser        ┐ run in sequence, each on its own InputImage,
   │    └─ Devanagari recogniser   ┘ emitting a merged result after each
   │
   ├─ merge
   │    ├─ dedupe lines by IoU > 0.55, keeping the longer text
   │    └─ merge blocks by IoU > 0.60
   │
   ├─ OcrResult  (blocks → lines → words, all in bitmap pixels)
   │
   ├─ SelectionEngine — flattens to reading order, does all hit testing and range logic
   │
   └─ FrameTransform — bitmap pixels ⇄ screen pixels, through the fit and the user's zoom/pan
```

Two recognisers rather than one because ML Kit has no combined Latin + Devanagari model, and the
screens this app exists for — a Hindi caption over an English interface — need both. The merge is
what stops the same English button label appearing twice.

**They cost the sum, not the maximum.** An earlier version launched both with `async` and assumed the
wall-clock cost would be the slower of the two. A device trace showed otherwise: ML Kit dispatches
every recognition onto one shared internal worker, so the second call simply queues behind the first.
Recognition is therefore a `Flow` that emits after each script — English text is selectable while the
Devanagari pass is still running. The total is unchanged; the wait is halved.

Each recogniser also gets its own `InputImage`. Sharing one made ML Kit's native side lock and unlock
the same pixel buffer twice, which it reported as `Failed to unlock pixels for bitmap`.

**Model loading is warmed off the critical path.** ML Kit does not touch its models until the first
`process` call, and that first call is expensive — seconds, not milliseconds. `warmUp` runs a 32x32
throwaway image through each recogniser when the app is opened and again when the service starts, so
the cost lands while the user is granting permissions rather than on their first tap.

Both models are bundled into the APK (`com.google.mlkit:text-recognition*`, not the Play-Services
variants), so recognition works offline on a device with no Google Play Services.

`OcrResultMapper` has two overloads: one taking a `ScreenFrame`, one taking plain numbers. The second
exists because `ScreenFrame` carries a `Bitmap`, which cannot be constructed in a JVM test, and the
coordinate arithmetic is exactly the thing that needs testing.

---

## Selection UI

`ScanActivity` is an activity, not another overlay window. The floating eye has to be an overlay
because it lives above other apps. The selection screen does not: it appears because the user asked,
takes the whole screen, and leaves.

Making it an activity buys correct back handling, a real IME for the edit sheet, working TalkBack
focus order, and system-managed lifecycle for several megabytes of bitmap. A touchable full-screen
overlay would reimplement all four and would look like a tapjacking overlay to both the system and a
suspicious user.

It is `excludeFromRecents`, `noHistory`, `singleInstance`, with no window animation — so the frozen
frame appears exactly where the real screen was, and a stale capture of a banking app never turns up
in the task switcher.

Gesture arbitration inside the frame: two or more pointers always mean zoom and pan, one pointer
means selection, and the tail of a pinch is never allowed to become a selection drag.

---

## Threading

| Work | Where |
|---|---|
| Overlay drawing, drag, window updates | Main thread; `IrisEyeView` schedules nothing between blinks |
| `ImageReader` callbacks, `Image` → `Bitmap` | Dedicated `HandlerThread` (`copyeye-capture`) |
| Frame preparation, recognition, merging | `Dispatchers.Default` |
| DataStore and history file I/O | `Dispatchers.IO` |
| Scan orchestration | Service `lifecycleScope`, cancelled on teardown |
| Selection session | `viewModelScope`, cancelled with the activity |

Nothing blocking runs on the main thread. Recognition is cancellable and is cancelled when the scan
overlay closes.
