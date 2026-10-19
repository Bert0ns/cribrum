# Cribrum for Android

Kotlin and Jetpack Compose (`docs/design.md`, D1). Android 11 (API 30) or newer, the first version with the storage rules the design relies on (§4, §9). The code mirrors the [Python lab](../lab/): the same component names and the same types, so a detector moves from one to the other mostly by translation.

## Build

You need JDK 21 or newer (Robolectric needs it for the unit tests on SDK 37) and the Android SDK (Android Studio installs both). Point `ANDROID_HOME` at the SDK, or write `sdk.dir=/path/to/sdk` in `android/local.properties`.

```bash
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Unit tests run on the JVM with Robolectric, so they need no phone. CI runs the same command on every pull request that touches `android/`, and keeps the debug APK as an artifact.

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
| `io.github.bert0ns.cribrum.metadata` | `ImageMetadata` | `cribrum_lab.metadata` |
