# Known limitations

Honest list. Everything here is either a platform rule CopyEye cannot change, a deliberate scope
decision, or a known rough edge.

---

## Platform rules CopyEye cannot change

### Protected screens cannot be scanned

Apps that set `FLAG_SECURE` — banking apps, DRM video players, some password managers, and parts of
the system UI — are composited into a media projection as solid black. CopyEye detects the blank
frame and says so. It does not try to work around the protection, and there is no setting that does.

### Screen-capture consent cannot be remembered

From Android 14, consent is per-session and an app may not cache or reuse a grant. Since CopyEye
releases its session as soon as it has a frame, the dialog appears on each scan by default. Keeping
the session for 15 seconds to 3 minutes (Scan settings) makes a burst of scans cost one dialog.

### The screen-recording indicator during a scan, and the dialog that buys its absence

Android shows a screen-recording indicator for as long as a capture session exists, and no app may
suppress it. CopyEye's answer is to hold no session except during a scan, so the indicator is absent
while the eye sits idle and appears only for the moment a frame is taken.

The unavoidable cost sits on the other side of that trade: a new session needs new consent, so
Android's dialog appears on any scan that starts from a released session. Scan settings →
"Release screen access after" moves the trade-off; it cannot remove it.

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

### The `specialUse` foreground-service type needs a Play justification

The idle service runs as `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, which is what lets the floating eye
persist with no screen access and therefore no recording indicator. Google Play requires a written
justification for that type at review time; the manifest carries one in
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, but a submission may still draw questions. Distribution outside
Play is unaffected.

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

### What has and has not been verified on a device

Run on an **Android 14 (API 34) x86_64 emulator**, 1080x2340. Confirmed working there:

- onboarding, Home, and both permission flows
- the floating eye: drawing, dragging, edge snap, auto-dim and edge peek
- a scan end to end — capture, frozen frame, scan wave, recognition of both scripts, and text
  outlines landing accurately on the real glyph positions
- the service holding `types=40000000` (no screen access) while idle, `types=20` only during a
  capture, and no recording indicator on the status bar at rest

**Not verified: the sub-one-second scan target.** ML Kit on an x86_64 emulator has no NEON and falls
back to a software TFLite path; model warm-up alone measured 25–100 seconds there, and per-scan
recognition is similarly pathological. Those numbers say nothing about an ARM phone and should not be
quoted. The scan budget has to be measured on real hardware before any claim is made about it.

Also unverified: everything in the device matrix below, and the instrumentation tests, which compile
but have not been executed.

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
