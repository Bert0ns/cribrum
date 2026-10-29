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

## Benchmark build

A debug build runs the Kotlin image preprocessing about three times slower than a release one (`docs/design.md` §5), so measure on this one. It is the release build, not debuggable, with `profileable` in its manifest so the shell can profile it, and signed with the debug key, so it needs no release keystore:

```bash
./gradlew assembleBenchmark
adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
```

It keeps the debug build's application id and signature, so it installs over a debug build and keeps its index. A release build has another signature: uninstall it first, which drops its index. CI does not build it.

### README screenshots

`ReadmeScreenshots` renders the home, a category grid and the trash on the JVM, with the real composables and theme, and every image drawn in code. `-PreadmeScreenshots` makes it write `docs/screenshots/*.png`. Without the property Gradle leaves the test out, so a normal run leaves the files alone and CI does not depend on them:

```bash
./gradlew -PreadmeScreenshots testDebugUnitTest --tests '*ReadmeScreenshots'
```

### Compose compiler reports

`-PcomposeReports` makes the Kotlin compile write the Compose compiler's stability reports and metrics to `app/build/compose_compiler`: `app-composables.txt` (which composables skip, and the stability of each parameter), `app-classes.txt` (the stability of each class) and `<variant>/app-module.json` (the totals). It is off by default. Gradle's build cache restores a compile without running it, and then writes no reports, so rerun the compile task:

```bash
./gradlew -PcomposeReports compileBenchmarkKotlin --rerun
```

The compiler runs with strong skipping, so a parameter that the report does not mark `stable` is compared by identity and the composable still skips when the same instance comes back.

### Trace sections

The code names its heavy steps with `androidx.tracing`, so a Perfetto trace of the benchmark build shows them. The names are stable, because the measurement script looks them up. A step that suspends is an async section, and the others are plain ones.

| Section | Where |
|---|---|
| `Index.indexedImages` | `ImageIndex.indexedImages`: reading every image and signal |
| `Ranker.rank` | `Review`'s calls of `Ranker.rank` |
| `Review.overview`, `Review.counts`, `Review.page` | the three `Review` loads, each with the two above inside |
| `Pipeline.walk`, `Pipeline.metadata`, `Pipeline.detectors`, `Pipeline.embeddings`, `Pipeline.embeddedDetectors`, `Pipeline.groupDetectors` | the phases of `Pipeline.run` |
| `Imaging.decode` | one image's decode |
| `Text.preprocess`, `MobileClip.preprocess` | one image's preprocessing, each with `Imaging.decode` inside |
| `Onnx.run` | one `ModelRuntime.run` call of `OnnxRuntime` |

Sections are per image at the finest, never per pixel or row. Perfetto shows the async sections on process tracks and the others on thread tracks, so a query for a name must read both.

### Measuring

`scripts/measure.sh` measures the benchmark build on a connected phone, and prints one line per number:

- `cold start`: `TotalTime` of `am start -W`.
- `home ready`: from the start of the process to the end of the last `Review.overview` before the category opens, from a Perfetto trace.
- `category open`: the length of the first `Review.page`, from the same trace.
- `scroll frames`, `scroll janky frames`, `scroll p90`, `scroll p99`: `dumpsys gfxinfo` over ten flings on the category page.
- `indexed images` and `last scan` with the new or changed files, the time of each phase and the group detectors that ran or skipped: what the last scan logged under the `scan-time` tag (`adb logcat -s scan-time`), while the log still holds it. Settings shows the total time of the last scan.

It needs the phone unlocked with its screen on, and [uv](https://docs.astral.sh/uv/), which installs the `perfetto` Python package for `scripts/trace_times.py` and downloads Perfetto's `trace_processor` the first time. It starts the app cold, opens one category from the home, scrolls it and goes back, and taps nothing else. It opens "Similar copies" (or "Copie simili"), or the category named in its arguments, which must have something to review. It refuses a name that is not a category title, and stops before the flings if the category screen did not open.

A cold start force-stops the app, which would kill a running scan. The script stops instead when a scan is running (WorkManager's job or foreground service in `dumpsys activity services`), so start it when the scan is done:

```bash
android/scripts/measure.sh
android/scripts/measure.sh Screenshots
```

`ADB` overrides the adb command. Under WSL with the Windows adb, run it from a folder Windows can reach, since adb gets its files through a temporary folder under the current directory:

```bash
cd /mnt/c/Users/Public
ADB='cmd.exe /c C:\Users\<you>\AppData\Local\Android\Sdk\platform-tools\adb.exe' ~/cribrum/android/scripts/measure.sh
```

The times grow with the library, so write down `indexed images` with every number.

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
| `io.github.bert0ns.cribrum.tracing` | `traceSuspend`: a trace section around code that suspends | none |
