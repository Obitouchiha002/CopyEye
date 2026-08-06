# Known limitations

Honest list. Everything here is either a platform rule CopyEye cannot change, a deliberate scope
decision, or a known rough edge.

---

## Platform rules CopyEye cannot change

### Protected screens cannot be scanned

Apps that set `FLAG_SECURE` — banking apps, DRM video players, some password managers, and parts of
the system UI — are composited into a media projection as solid black. CopyEye detects the blank
frame and says so. It does not try to work around the protection, and there is no setting that does.

### Android asks for screen permission on every start

From Android 14, screen-capture consent is per-session and cannot be remembered. CopyEye holds one
session for as long as it is running, so the dialog appears once per start rather than once per scan
— but stopping CopyEye, or Android stopping it, means the next start asks again.

### The screen-recording indicator stays visible

Because the capture *session* is long-lived, Android shows its recording indicator (and, on some
versions, a persistent status-bar chip) for the whole time CopyEye is on — even though no frame is
produced between taps. The indicator is accurate about the permission and pessimistic about the
behaviour. There is no supported way to hold a projection without it, and CopyEye does not try.

### Minimum Android 10 (API 29)

Android 10 is where several things CopyEye depends on became reliable at once: typed foreground
service declarations, `VibrationEffect.createPredefined`, and the media-projection behaviour the
capture pipeline is built around. Supporting Android 5–9 was possible but meant four alternative
code paths through the parts of the app that are hardest to test, on devices that would struggle
with on-device OCR anyway.

### One virtual display per grant

Android 14 permits exactly one `createVirtualDisplay` per projection. Rotation and split-screen
resizes are handled with `VirtualDisplay.resize`. If a device rejects a resize, the session has to be
restarted, which means a fresh consent dialog.

---

## Deliberate scope decisions

| Not included | Why |
|---|---|
| Accounts, cloud sync | Nothing needs to leave the device, and adding sync would require `INTERNET`, which would break the strongest privacy guarantee in this app |
| Translation | Would need either a network call or a second family of on-device models. Out of scope for a first release, and the primary job is copying |
| AI summaries | Same |
| Ads, subscriptions, analytics | None, deliberately |
| Continuous / automatic scanning | The product's whole premise is that it looks only when asked |
| An accessibility service | Would grant continuous access to every app's content. MediaProjection asks for less and asks visibly |
| Scanning without a visible eye | Would make the app indistinguishable from spyware |

---

## Rough edges and open work

### Scripts

Only Latin and Devanagari are wired up. ML Kit also offers Chinese, Japanese and Korean models; the
engine is written against a `Set<OcrScript>` so adding one is a value in the enum plus a dependency,
but no other script has been tested.

### Rotated and angled text

ML Kit reports a rotation angle per line, and CopyEye stores it (`OcrLine.angleDegrees`) but draws
axis-aligned highlight rectangles. Text at a steep angle gets a box larger than the glyphs, which is
loose but still selectable. Rotated highlights are not implemented.

### Smart Frame Mode and video

The burst takes several sequential frames rather than a true buffered burst, so on fast motion the
frames may be further apart than ideal. It is off by default on low-tier devices for that reason.
For video that can be paused, pausing gives a better result than any burst.

### Region rescan

"Rescan" re-runs recognition at the same resolution and filters to the selected region, rather than
re-running at full resolution on a crop. It cleans up a noisy result but does not recover text that
was too small to read the first time. Switching to Accurate mode does.

### Keyboard avoidance below Android 11

The IME inset can only be queried from API 30. On Android 10 the eye does not step aside for the
keyboard. It can still be dragged.

### Multi-display and desktop mode

Only the default display is captured. On a device in desktop mode or with an external screen
attached, CopyEye scans the phone's own display.

### OEM background limits

Xiaomi, Oppo, Vivo, Realme and some Samsung builds kill foreground services aggressively. The Help
screen links to the battery-optimisation settings, which usually fixes it. CopyEye does not request
the exemption programmatically — Play policy reserves that for a short list of app types that a
screen-capture utility is not on.

### APK size

The bundled OCR models are a ~11 MB native library per ABI. Per-ABI splits bring a real device's
download to about 13–17 MB; the universal APK is about 45 MB. For Play, use `bundleRelease`.

Using ML Kit's unbundled (`play-services-mlkit-*`) variants would cut this to a few megabytes, at the
cost of requiring Google Play Services and a first-run model download. The bundled models were chosen
so recognition works offline on any device.

### Not yet tested on hardware

This build compiles, passes 84 unit tests and passes lint with zero errors, but it has not been run
on a physical device or emulator in this session. The instrumentation tests are written and compile
but have not been executed. Before shipping, work through the device matrix in the next section.

---

## Device matrix to work through before release

- A small phone and a large phone
- A low-end device (2 GB RAM) and a 120 Hz flagship
- Gesture navigation and three-button navigation
- A display cutout / punch hole
- Split-screen and, if available, a foldable's inner and outer screens
- Light and dark themes, and "Remove animations" turned on in Developer options
- Mixed Hindi–English text
- YouTube, Instagram Reels, Chrome, a PDF viewer, the gallery, and a slides app
- A banking app or DRM video, to confirm the protected-screen message rather than a crash
- Rotation during a scan, and the keyboard opening under the eye
