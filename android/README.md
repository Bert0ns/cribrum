# Cribrum for Android

Kotlin and Jetpack Compose (`docs/design.md`, D1). Android 11 (API 30) or newer, the first version with the storage rules the design relies on (§4, §9). The code mirrors the [Python lab](../lab/): the same component names and the same types, so a detector moves from one to the other mostly by translation.

## Build

You need JDK 21 or newer (Robolectric needs it for the unit tests on SDK 37) and the Android SDK (Android Studio installs both). Point `ANDROID_HOME` at the SDK, or write `sdk.dir=/path/to/sdk` in `android/local.properties`.

```bash
cd android
./gradlew ktlintCheck assembleDebug testDebugUnitTest lintDebug :baselineprofile:compileNonMinifiedReleaseKotlin
```

`./gradlew ktlintFormat` fixes the formatting that `ktlintCheck` reports (rules in `.editorconfig`).

Unit tests run on the JVM with Robolectric, so they need no phone. CI runs the same command on every pull request that changes `android/`, `fixtures/` or `lab/src/`, and keeps the debug APK as an artifact.

Detector tests compare with the lab's golden fixtures (`fixtures/golden`), decoding them with Android's own decoders through Robolectric's native graphics.

Instrumented tests run on a connected phone. The app trash tests move files on its real shared storage under `Pictures/cribrum-test` and place a temporary sentinel in the real device trash to verify it survives, cleaning up after themselves:

```bash
./gradlew connectedDebugAndroidTest
```

That task installs the debug app over any installed Cribrum and uninstalls it when it ends. On a phone you use, build with `-PapplicationIdSuffix=.heiccheck`, which installs beside Cribrum, and run the test by hand: `HeicRotationDeviceTest` lists the commands.

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

`ReadmeScreenshots` renders the home, review pages, groups, the full size view, the compress page, the confirmation dialog, the trash, settings and the storage map on the JVM, with the real composables and theme, and every image drawn in code. `-PreadmeScreenshots` makes it write `docs/screenshots/*.png` and `site/screenshots/*.png`. Without the property Gradle leaves the test out, so a normal run leaves the files alone and CI does not depend on them:

```bash
./gradlew -PreadmeScreenshots testDebugUnitTest --tests '*ReadmeScreenshots'
```

### UI gallery

`UiGallery` renders every screen and key state of the app to PNG files, for a review of the UI by someone who cannot hold the phone. It drives the real `App` (the navigation bar, the top bar, the snackbar and the dialogs) with the real view models, over a library drawn in code: no photo and no file from a device is involved. It makes about 120 images, in Italian and in English, and some of them in the dark theme, with a font scale of 1.5 and on an unfolded Fold. `-PuiGallery` turns it on, and without it Gradle leaves the test out. The `ui-council` skill (`.agents/skills/ui-council/`) runs such a review with two models. The images go to `/tmp/cribrum-ui-gallery`, or to the folder given as `-PuiGallery=<folder>`, with an `index.md` that says what each one shows:

```bash
./gradlew -PuiGallery testDebugUnitTest --tests '*UiGallery'
```

A run takes about ten minutes and starts by emptying the folder. Nothing it writes belongs in the repository. Compare two runs by rendering into two folders. Every scene is a `Scene` in `GalleryScenes.kt`, and a new one needs only its id, its text for `index.md` and the steps that reach it.

A few things come out different from the phone. The test window has no status bar or gesture bar. A dialog is as wide as the screen, where the phone leaves a margin on each side. The paths in the full size view start with a folder in `/tmp`.

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
| `Compress.list` | `CompressViewModel.refresh`: reading the photos, the favourites and the retention for the Compress page |
| `Compress.tile` | one Compress tile's image load, from Coil's request start to its success, error or cancel. The tiles of a screen overlap, so the time to the first screen of tiles is from the first start to the last end |
| `Compressor.preview` | one `Compressor.preview`, the before and after of the Compress summary, inside its lock (not the wait for the one before) |
| `Trash.list` | `ReviewViewModel.openTrash`: reading the trash's list |
| `Trash.tile` | one trash tile's image load, like `Compress.tile` |
| `StorageMap.build` | `ReviewViewModel.openStorageMap`: reading the images that are not in the trash and building the folder tree |
| `StorageMap.layout` | `layoutTreemap` for the map on screen, in the composition, so it runs again when the folder or the size changes |
| `FullSize.image` | the image load of the review's and the trash's full size view (`ZoomableImage`), from Coil's request start to its end |
| `TiledImage.base` | the base image load of a `TiledImage` (the Compress full size view and the summary's before and after), like `FullSize.image` |
| `TiledImage.tile` | one `BitmapRegionDecoder.decodeRegion` of a `TiledImage`: a view's missing tiles are one decode of their bounding box, so it is one for the view, not for each tile |

Sections are per image at the finest, never per pixel or row. Perfetto shows the async sections on process tracks and the others on thread tracks, so a query for a name must read both.

### Measuring

`scripts/measure.sh` measures the benchmark build on a connected phone, and prints one line per number:

- `cold start`: `TotalTime` of `am start -W`.
- `home ready`: from the start of the process to the end of the last `Review.overview` before the category opens, from a Perfetto trace.
- `category open`: the length of the first `Review.page`, from the same trace.
- `scroll frames`, `scroll janky frames`, `scroll p90`, `scroll p99`: `dumpsys gfxinfo` over ten flings on the category page.
- `compress open`: from the first `Compress.list`, which starts a frame or two after the tap on the tab, to the end of the last `Compress.tile` of the first screen. The first screen's tiles are the loads that start within a second of the first one, since a list composes them together and the script scrolls seconds later. The page is opened right after the category, so its tiles load from a cold image cache.
- `compress tiles`: how many tile loads the first screen started, the slowest one, and the most open at once. A count of loads in flight, to tell a slow decode from a queue.
- `compress scroll frames`, `compress scroll janky frames`, `compress scroll p90`, `compress scroll p99`: as `scroll`, over ten flings on the Compress page.
- `trash open`, `trash tiles` and `trash scroll ...`: the same for the trash, from the first `Trash.list` to the last `Trash.tile` of the first screen.
- `storage map open`: from the first `StorageMap.build` to the end of the `StorageMap.layout` that follows it.
- `indexed images` and `last scan` with the new or changed files, the time of each phase and the group detectors that ran or skipped: what the last scan logged under the `scan-time` tag (`adb logcat -s scan-time`), while the log still holds it. Settings shows the total time of the last scan.

It needs the phone unlocked with its screen on, and [uv](https://docs.astral.sh/uv/), which installs the `perfetto` Python package for `scripts/trace_times.py` and downloads Perfetto's `trace_processor` the first time. It records one Perfetto trace of the whole run, which takes about two and a half minutes since the trace has a fixed length. It starts the app cold, opens one category from the home, scrolls it and goes back, then opens the Compress page, the trash and the storage map from the home, and scrolls the first two. It opens "Similar copies" (or "Copie simili"), or the category named in its arguments, which must have something to review. It refuses a name that is not a category title, and stops before the flings if the category screen did not open. A Compress page or a trash with nothing in it is not scrolled and has no number.

The taps are all in the header of the script and in `measure.sh -h`, and they are all it makes:

- the title of one category on the home;
- the "Compress" tab, and the "Clean" tab to come back;
- the trash icon in the home's top bar, which opens the trash (it is not a Trash button);
- the "Settings" tab, its "Storage map" row, which opens a screen that only reads, and the "Clean" tab.

Besides those it swipes to scroll the category, the Compress page, the trash and Settings, and presses Back. It never taps a tile, a checkbox, Continue, Trash, Keep, Skip, Undo, Exclude, a Settings action or Scan now. It only goes on from the home, which it recognises by the app's name in the top bar, and stops when a screen is not the one it expects.

**By hand: the full size view and the summary.** Both need a tile, so the script leaves them out. Start `scripts/measure.sh --by-hand 120` instead: it makes no tap and no swipe, starts the app cold and records for that many seconds while you use it. In that time open the full size view of a review image or a trash image (long press a tile), swipe to the next one, and for the Compress ones long press a tile and zoom, or select photos, tap Continue and wait for the previews. When the trace ends it prints:

- `summary previews`: the length of each `Compressor.preview`, in order. The first one is the time to the first before and after, from the moment the summary opens, since the summary starts it at once.
- `full size image`: each `FullSize.image`, the load of the review's and the trash's full size view.
- `zoom base image`: each `TiledImage.base`, the base image of the Compress full size view and of the summary's slider.
- `zoom tile decodes`: how many `TiledImage.tile` decodes the zooms made and the slowest. One decode reads the bounding box of a view's missing tiles.

Name a photo by its size in megapixels only, never by its name or path, and keep the trace in `/tmp`.

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

### Baseline Profile

`app/src/main/baselineProfiles/baseline-prof.txt` lists the code of the cold start, the home, a category opening and its scroll, so ART compiles it ahead of time instead of running it interpreted the first times. It is committed, and every build reads it from `main`: R8 rewrites its names for the minified builds, and the APKs of `release` and `benchmark` carry the result in `assets/dexopt/baseline.prof`, next to the rules the libraries ship. No build generates it, so a build needs no phone.

**On a sideloaded install.** Without Google Play nothing applies the profile at install time. `androidx.profileinstaller` does it from inside the app: about five seconds after the first frame of the first start after an install or update, it writes the profile to where ART looks for it. That changes nothing yet. Android compiles the app with it at its next background dexopt, which needs the phone idle and charging, so usually overnight. Until then the app runs as if it had no profile. A measurement can't wait for that, so force both steps after installing:

```bash
adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE io.github.bert0ns.cribrum/androidx.profileinstaller.ProfileInstallReceiver
adb shell cmd package compile -f -m speed-profile io.github.bert0ns.cribrum
adb shell cmd package dump io.github.bert0ns.cribrum | grep 'status='
```

The broadcast answers `result=1` when it wrote the profile and `result=2` when the app had already done so, and the last line must say `status=speed-profile`. Do the same on the build you compare with, since that one has the libraries' rules and would otherwise start from a different state.

**Regenerating.** Do it after a change that moves much code on these paths, such as a new home or a new grid. It holds the screen for a few minutes, and the phone must stay unlocked with its screen on:

```bash
./gradlew :app:assembleNonMinifiedRelease :baselineprofile:assembleNonMinifiedRelease
scripts/generate_profile.sh
```

`ADB` overrides the adb command, and under WSL with the Windows adb the script runs from a folder Windows can reach, as `measure.sh` does. Check the new profile before committing it: it lists class and method names only.

`:baselineprofile` holds the generator, `HomeAndCategoriesGenerator`, an instrumented test that starts the app, waits for the home, opens a plain and a grouped category and scrolls them. It never taps anything else. It runs against the plugin's `nonMinifiedRelease` build, which differs from a build for users in three ways, so it can run on a phone that has the real app with a real library:

- Its application id is `io.github.bert0ns.cribrum.profile`, so it installs beside the real app and the script uninstalls it at the end.
- Its manifest drops `MANAGE_EXTERNAL_STORAGE`, so it can't reach a real image or the real trash, and a scan stops at once. The script refuses a build that asks for it.
- `app/src/nonMinifiedRelease` adds `ProfileSeed`, which only that build has. It lets the app past its permission screen through the `Permissions` hook, and fills the index with 300 flat-colored images in the package's private storage, with made-up signals. It runs before the collection starts, and the generator filters its classes out, so it stays out of the profile.

Use the script and not the plugin's `generateBaselineProfile`. That task only reaches a phone on the local adb, and it writes to a folder that `release` reads and `benchmark` does not, since the plugin takes a build type named `benchmark` for one of its own.

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
| `io.github.bert0ns.cribrum.baselineprofile` (`:baselineprofile`) | The generator of the Baseline Profile | none |
