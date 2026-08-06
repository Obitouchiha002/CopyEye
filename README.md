# CopyEye

Copy any text you can see on your Android screen — from videos, Reels, Shorts, images, games,
presentations, and apps that block text selection.

Tap a small floating eye. CopyEye freezes the current screen, reads it on-device, and lets you pick
a word, a line, a paragraph, or everything. Copy, and you are back in the app you were using.

---

## What it does

| | |
|---|---|
| **Floating eye (Iris)** | Draggable, edge-snapping, fades out of the way after a few seconds |
| **One-tap scan** | Freezes the visible frame and runs OCR on it |
| **Languages** | English/Latin and हिन्दी/Devanagari, including mixed screens |
| **Selection** | Tap a word, a line or a paragraph; drag across text; draw a region; zoom and pan |
| **Copy** | Copy, Copy all, or edit the text before copying |
| **Smart actions** | Links, phone numbers, email addresses and addresses get a secondary action |
| **Clipboard history** | Optional, off by default, local only, with automatic expiry |
| **Privacy** | On-device OCR, no network permission at all, frames deleted after each scan |
| **No recording indicator at rest** | CopyEye holds zero screen access until you tap. Android's screen-recording icon appears for the scan and goes. |
| **Runs on** | Android 10 and every version above it |

---

## Build and run

**Requirements**

- Android Studio Ladybug or newer (or JDK 21 with the Android SDK on the command line)
- Android SDK Platform 36
- A device or emulator running **Android 10 (API 29) or newer**

**From the command line**

```bash
git clone <this repo>
cd CopyEye

# Point at your SDK if local.properties is not already there
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

./gradlew :app:assembleDebug          # build
./gradlew :app:installDebug           # install on a connected device
./gradlew :app:testDebugUnitTest      # unit tests (84 of them, no device needed)
./gradlew :app:connectedDebugAndroidTest   # instrumentation tests (needs a device)
./gradlew :app:lintDebug              # lint
./gradlew :app:assembleRelease        # minified, per-ABI release APKs
./gradlew :app:bundleRelease          # App Bundle, for Play
```

The debug APK lands in `app/build/outputs/apk/debug/`.

**Release builds** are minified and shrunk, and are deliberately left unsigned by the build file —
attach your own signing config before shipping.

**Size.** The bundled OCR models are a ~11 MB native library per architecture, so the build produces
per-ABI APKs: about **13–17 MB** for a real device, against 45 MB for the universal APK. For Play,
`bundleRelease` handles the same split and more.

---

## First run

1. Open CopyEye and walk through the five onboarding screens.
2. Grant **Display over other apps** — this is what lets Iris float.
3. Iris appears at the right edge. Drag her anywhere; she snaps to the nearest side. **No screen
   permission is asked for at this point, and no recording indicator appears** — CopyEye cannot see
   your screen yet.
4. Open any app and tap Iris. Android's screen-capture dialog appears; tap **Start now**. CopyEye
   takes one frame, hands the access straight back, and shows you the text.
5. Pick text, copy, and you are back where you were.

By default CopyEye releases screen access the instant it has the frame, so the recording indicator is
only on your status bar for the moment of the scan. The trade-off is Android's: a new session needs a
new dialog. **Scan settings → Release screen access after** lets you keep the session for 15 seconds,
1 minute, 3 minutes, or indefinitely if you would rather never see the dialog again.

An ongoing notification is shown while CopyEye is running. Its **Stop** action shuts everything down.

---

## Permissions, and why each one exists

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draws Iris above other apps. Without it there is no product. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keeps the floating eye alive with **no** screen access. This is the type the service runs under nearly all the time. |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required by Android before a capture can start. The service switches to this type only for the moment a scan is running. |
| `POST_NOTIFICATIONS` | That notification is also how you stop CopyEye at any moment. Denying it does not break scanning. |
| `VIBRATE` | Three short taps: scan started, text ready, copied. Can be turned off. |

**Not requested, and not needed:** `INTERNET`, `CAMERA`, `RECORD_AUDIO`, location, contacts,
storage. The absence of `INTERNET` in particular is what makes "nothing is uploaded" a fact about
the manifest rather than a promise about the code.

Screen capture itself is not a manifest permission — it is a per-session consent dialog that Android
shows, and that CopyEye cannot suppress or remember.

---

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — how the pieces fit, the capture lifecycle, the state machine
- [Privacy and security](docs/PRIVACY.md) — what is stored, what is not, and how each claim is enforced
- [Known limitations](docs/LIMITATIONS.md) — what does not work and why
- [Performance](docs/PERFORMANCE.md) — the idle and scan budgets, and where the remaining headroom is

---

## Project layout

```
app/src/main/java/com/copyeye/app/
├── AppContainer.kt              manual dependency container
├── CopyEyeApp.kt                Application
├── MainActivity.kt              app window; owns every permission handshake
├── core/
│   ├── common/                  API-level gates, haptics, device tiers
│   ├── permissions/             permission reads and the intents that fix them
│   └── state/                   the state machine and the process-wide bus
├── data/preferences/            settings model + DataStore
├── clipboard/                   clipboard writes and local history
├── overlay/                     the floating eye: service, controller, view, gestures, geometry
├── capture/                     MediaProjection, frame preparation, frame handoff
├── ocr/                         recognition engine, models, coordinate mapping, smart actions
├── selection/                   the scan activity, selection logic, scan animation, toolbar
├── feature/                     onboarding, home, settings, history, privacy, help
└── ui/                          theme, navigation, shared components
```

---

## Status

First release scope, complete: onboarding, both permission flows, the draggable animated eye with
edge snapping and auto-dim, screen capture, English and Hindi OCR, the scan animation, word/line/
paragraph selection, copy and copy-all, secure-screen handling, settings, the privacy screen, the
foreground-service notification, and automated tests.

Beyond the first-release list, the quick menu, clipboard history, region selection, zoom and pan,
edit-before-copy and smart actions are also implemented.

No accounts, no cloud sync, no ads, no subscriptions, no analytics. See
[Known limitations](docs/LIMITATIONS.md) for what is deliberately not here.

**Not yet run on hardware.** The project builds, passes 84 unit tests and passes lint with zero
errors, but nothing in it has been executed on a device or emulator. The instrumentation tests
compile but have not been run. Work through the device matrix in
[Known limitations](docs/LIMITATIONS.md#device-matrix-to-work-through-before-release) before
shipping.
