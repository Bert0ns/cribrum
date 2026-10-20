# Cribrum for Android

Kotlin and Jetpack Compose (`docs/design.md`, D1). Android 11 (API 30) or newer, the first version with the storage rules the design relies on (§4, §9). The code mirrors the [Python lab](../lab/): the same component names and the same types, so a detector moves from one to the other mostly by translation.

## Build

You need JDK 21 or newer (Robolectric needs it for the unit tests on SDK 37) and the Android SDK (Android Studio installs both). Point `ANDROID_HOME` at the SDK, or write `sdk.dir=/path/to/sdk` in `android/local.properties`.

```bash
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Unit tests run on the JVM with Robolectric, so they need no phone. CI runs the same command on every pull request that touches `android/`, and keeps the debug APK as an artifact.

Detector tests compare with the lab's golden fixtures (`fixtures/golden`), decoding them with Android's own decoders through Robolectric's native graphics.

Instrumented tests run on a connected phone. The app trash tests move files on its real shared storage, under `Pictures/cribrum-test`, and clean up after themselves:

```bash
./gradlew connectedDebugAndroidTest
```

## Install (sideload)

Enable USB debugging on the phone (see the [lab README](../lab/README.md#data); from WSL, forward the phone with usbipd-win first), then:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Layout

| Package | Contents | Lab counterpart |
|---|---|---|
| `io.github.bert0ns.cribrum.model` | Core types: `ImageRef`, `Category`, `Protection`, `Signal`, `IndexedImage` | `cribrum_lab.model` |
| `io.github.bert0ns.cribrum.ranker` | `Ranker`, `Candidate`, thresholds and protection weights | `cribrum_lab.ranker` |
| `io.github.bert0ns.cribrum.index` | Room tables and `ImageIndex`, with the lab's schema | `cribrum_lab.index` |
| `io.github.bert0ns.cribrum.metadata` | `ImageMetadata` and `readMetadata` | `cribrum_lab.metadata` |
| `io.github.bert0ns.cribrum.trash` | `Trash` and `AppTrash`: the only code that deletes files (`docs/design.md` §9) | none: the lab never deletes |
| `io.github.bert0ns.cribrum.sources` | `ImageSource`: the deep scan, MediaStore, and both merged | `cribrum_lab.sources` |
| `io.github.bert0ns.cribrum.ui` | Compose screens | `cribrum_lab.labeling` (the lab's only UI) |
| `io.github.bert0ns.cribrum.detectors` | `Detector`, `GroupDetector`, `AnalysisContext`, and the detectors ported so far | `cribrum_lab.detectors` |
| `io.github.bert0ns.cribrum.hashing`, `.folders` | Content hash (xxHash3), camera and messaging folders | `cribrum_lab.hashing`, `cribrum_lab.folders` |
