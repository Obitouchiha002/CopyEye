# Privacy and security

CopyEye reads the screen. That is an unusual amount of trust to ask for, so this document states
exactly what it does with what it sees, and — where possible — points at the mechanism that makes
each claim true rather than at the intention behind it.

---

## The three sentences shown in the app

> CopyEye scans only when you tap the eye.
> Screen images are processed on your device.
> Captured frames are not uploaded or saved.

The rest of this document is those three sentences, checked.

---

## "Only when you tap"

CopyEye holds no screen access at all until you tap Iris, and gives it back before recognition even
starts. This is a property of the pipeline, not a flag in it.

While the eye is on screen, the service runs as a `specialUse` foreground service. There is no
`MediaProjection`, no virtual display, and nothing capable of reading the screen. Tapping Iris raises
Android's consent dialog, switches the service to `mediaProjection`, takes exactly one frame, and
stops the projection again — all before OCR runs. Everything after that point works on a bitmap in
this process's memory.

**How to check it yourself, on your own phone:**

```
adb shell dumpsys activity services com.copyeye.app | grep types=
```

`types=40000000` is `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` — no screen access.
`types=20` is `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`, and you should only ever catch it there
during a scan.

The visible consequence is the one that matters most to users: **Android's screen-recording indicator
is not on your status bar while CopyEye sits idle**, because there is nothing for it to report. It
appears for the moment of a scan and goes.

The cost is Android's, not ours: a new session needs new consent, so the system dialog appears on a
scan that starts from a released session. Scan settings → "Release screen access after" lets you
trade a short-lived session against fewer dialogs, up to `Never` if you would rather keep the
indicator and never see the dialog again.

## "Processed on your device"

Recognition uses ML Kit's **bundled** Latin and Devanagari models
(`com.google.mlkit:text-recognition`, `com.google.mlkit:text-recognition-devanagari`), which ship
inside the APK. Not the `play-services-mlkit-*` variants, which download models on demand.

Consequence: OCR works on a device with no network and no Google Play Services. Scanning is
unaffected by aeroplane mode.

## "Not uploaded"

This section used to make a stronger claim than it can now, and it is worth recording why rather
than quietly editing it.

**It used to say:** the app declares no `INTERNET` permission, so it cannot open a socket, and
"nothing is uploaded" is enforced by the kernel's network sandbox rather than by the app's code —
true even for a build with a bug in it.

**What changed:** CopyEye gained a control backend. Every launch it calls `checkin`, which
registers the install and reports whether it has been suspended. The permission is now declared and
used.

**What is sent:** the Android ID, the name typed on first launch, and the app version. That is the
whole payload; it is one JSON object and `RemoteAdmin.kt` is the only file that builds it.

**What is not, and the reason it is still structural:** no screen, no recognised word, no clipboard
entry. `RemoteAdmin` holds no reference to `FrameStore`, `ScreenFrame`, the OCR engine or the
clipboard, and nothing in the capture pipeline holds a reference to `RemoteAdmin`. There is no call
graph between the two halves, so screen content cannot reach the network even through a bug — which
is a weaker guarantee than the sandbox gave, and a stronger one than a promise.

If you want the old guarantee back, the check-in is one object and removing it removes the
permission with it.

## "Not saved"

Captured frames exist as a `Bitmap` in memory and nowhere else.

- Nothing is written to the gallery, to `MediaStore`, to the cache directory, or to external storage.
- `FrameStore` holds at most one frame; storing a new one releases the previous.
- `ScanViewModel.onCleared` recycles the frame when the scan session ends, for any reason including
  a rotation mid-selection.
- `CopyEyeApp.onTrimMemory` drops any pending frame at `TRIM_MEMORY_UI_HIDDEN`.

---

## Permissions

**Requested**

| Permission | Purpose | If denied |
|---|---|---|
| `SYSTEM_ALERT_WINDOW` | Draw Iris over other apps | The eye cannot appear; the app explains and links to the setting |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the floating eye alive **without** any screen access. The service's normal state. | Not user-denyable |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required by Android before a capture may start; held only for the moment of a scan | Not user-denyable |
| `POST_NOTIFICATIONS` | The ongoing notification, which is also the fastest way to stop CopyEye | Scanning still works; the user loses the quick stop |
| `VIBRATE` | Three short haptic taps | Silently skipped |

**Not requested:** `INTERNET`, `CAMERA`, `RECORD_AUDIO`, `ACCESS_*_LOCATION`, `READ_CONTACTS`,
`READ_MEDIA_*`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`,
`PACKAGE_USAGE_STATS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

**No accessibility service.** An `AccessibilityService` would make some things easier — reading text
without OCR, knowing which app is in front — at the cost of a permission that grants continuous
access to the content of every app on the device. MediaProjection asks for less and asks visibly.

---

## Clipboard

Writes are marked sensitive on Android 13 and above:

```kotlin
clip.description.extras = PersistableBundle().apply {
    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
}
```

This suppresses the system's copy preview. CopyEye cannot know whether the text it just read off a
screen is a password or a poem, so it assumes the former.

CopyEye **does not** read the clipboard, and does not register a clipboard listener. It only writes.

---

## Clipboard history

Off by default. When the user turns it on:

- Only text copied **through CopyEye** is stored. The system clipboard is never monitored.
- Storage is a JSON file in the app's private directory, capped at 200 items.
- Retention defaults to 24 hours; 1 hour, 7 days and Never are also offered. Expired items are pruned
  on every load and every write.
- Pinned items are exempt from expiry, because pinning is the user overriding the default.
- "Clear all" is one tap, on both the History screen and the Privacy screen.

**On encryption.** The file is not separately encrypted, and the app says so rather than implying
more. Adding a layer would need a key, and the only place to keep that key would be the same private
directory the file is already in — which is protection against nothing. What the file does have is
Android's file-based encryption, tied to the user's lock screen, on every device shipping Android 10
or later. The real mitigations here are the ones that reduce what exists at all: opt-in, capped,
short-lived by default, never leaves the device, never backed up.

---

## Backups

Both cloud backup and device-to-device transfer are disabled for every domain:

```xml
android:allowBackup="false"
```
plus explicit `<exclude>` rules in `backup_rules.xml` and `data_extraction_rules.xml`.

Settings are trivial to set again. Clipboard history is whatever happened to be on the user's screen,
which is exactly the kind of thing that should not travel to a new phone inside a backup.

---

## Logging and crash reports

- Recognised text is never logged, at any level.
- Log statements carry state names, error kinds and sizes — never content.
- The release build strips `Log.d`, `Log.v` and `Log.i` entirely via
  `-assumenosideeffects` in `proguard-rules.pro`, which is cheaper and more reliable than auditing
  every call site.
- `CopyEyeBus` carries a copied-character *count*, never the copied text, so recognised text never
  reaches a process-wide singleton where a future log statement could find it.

**There is no analytics SDK, no crash reporter and no telemetry of any kind** in the app. Adding one
later would need `INTERNET`, which would invalidate the strongest claim in this document — so if that
ever changes, this file has to change with it.

---

## Secure screens

Banking apps, DRM video players and some password managers set `FLAG_SECURE`. Android composites
those windows into a media projection as solid black.

CopyEye detects the resulting uniform frame (`FrameAnalysis.isBlank`, a variance test rather than a
black test, because some vendors blank to white), and shows *"This screen is protected and cannot be
scanned."*

It does not attempt to work around the protection by any means, and there is no setting that does.

---

## What an auditor should check

1. `grep -r "INTERNET" app/src/main/AndroidManifest.xml` — no match.
2. `MediaProjectionController` — the only `setSurface` calls are inside `awaitFrame` and
   `detachSurface`.
3. `FrameStore` — one slot, released on replace and on clear.
4. `ScanViewModel.onCleared` — releases the frame unconditionally.
5. `grep -rn "Log\." app/src/main` — no call site takes recognised text.
7. `dumpsys activity services com.copyeye.app | grep types=` while idle — `40000000`, never `20`.
6. `ClipboardWriter` — writes only; no `addPrimaryClipChangedListener` anywhere in the app.
