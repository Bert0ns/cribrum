# Cribrum for Android

Kotlin and Jetpack Compose (`docs/design.md`, D1). Android 11 (API 30) or newer, the first version with the storage rules the design relies on (§4, §9). The code mirrors the [Python lab](../lab/): the same component names and the same types, so a detector moves from one to the other mostly by translation.

## Build

You need JDK 21 or newer (Robolectric needs it for the unit tests on SDK 37) and the Android SDK (Android Studio installs both). Point `ANDROID_HOME` at the SDK, or write `sdk.dir=/path/to/sdk` in `android/local.properties`.

```bash
cd android
./gradlew ktlintCheck assembleDebug testDebugUnitTest lintDebug
```

`./gradlew ktlintFormat` fixes the formatting that `ktlintCheck` reports (rules in `.editorconfig`).

Unit tests run on the JVM with Robolectric, so they need no phone. CI runs the same command on every pull request that changes `android/`, `fixtures/` or `lab/src/`, and keeps the debug APK as an artifact.

Detector tests compare with the lab's golden fixtures (`fixtures/golden`), decoding them with Android's own decoders through Robolectric's native graphics.

Instrumented tests run on a connected phone. The app trash tests move files on its real shared storage under `Pictures/cribrum-test` and place a temporary sentinel in the real device trash to verify it survives, cleaning up after themselves:

```bash
./gradlew connectedDebugAndroidTest
```

## Install (sideload)

Enable USB debugging on the phone (see the [lab README](../lab/README.md#data); from WSL, forward the phone with usbipd-win first), then:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release

Releases are manual: on GitHub, Actions → release → Run workflow, on `main`, with the version as `MAJOR.MINOR.PATCH`. The workflow refuses a version that is not above the latest release, runs the unit tests and lint, and publishes `cribrum-X.Y.Z.apk` and its SHA-256 as the release `vX.Y.Z`. Install it with `adb install -r`, or open it on the phone.

Every release is signed with the same key, so each one installs over the previous one. It lives in the repository secrets `ANDROID_KEYSTORE_BASE64` (the PKCS12 keystore, in base64), `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_ALIAS`. Keep a copy of the keystore and its password outside GitHub: with a lost key, the next release can only be installed after uninstalling the app, which drops its index. The trash is on shared storage and survives.

A debug build is signed with another key, so it can't be installed over a release, or the other way round, without uninstalling first.

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
| `io.github.bert0ns.cribrum.runtime` | `ModelRuntime` and `OnnxRuntime`: on-device models behind one interface | `cribrum_lab.runtime` |
| `io.github.bert0ns.cribrum.mobileclip`, `.prompts` | MobileCLIP2-S0 image embeddings, and the zero-shot classes with the prompts' stored embeddings | `cribrum_lab.mobileclip`, `cribrum_lab.prompts` |
| `io.github.bert0ns.cribrum.text` | `textCoverage`: PP-OCRv4 text detection, the share of the image covered by text | `cribrum_lab.text` |
| `io.github.bert0ns.cribrum.imaging` | `loadGrayscale`: Pillow's decoding, luminance and Lanczos thumbnail, step by step | `cribrum_lab.imaging` |
| `io.github.bert0ns.cribrum.review` | `Review`: pages of candidates, and decisions applied to the trash and the index together | none |
| `io.github.bert0ns.cribrum.pipeline` | `Pipeline`, and `IndexWorker` running it with WorkManager | `cribrum_lab.pipeline` |
