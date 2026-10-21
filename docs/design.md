# Cribrum — Design Document

- **Status:** Draft
- **Last updated:** 2026-10-21

## 1. Problem

A phone accumulates thousands of images. Many have no lasting value: blurry shots, near-identical bursts, lower-resolution copies of the same photo, pictures of lecture slides or homework taken for a single moment, screenshots, and forwarded media. Messaging apps make it worse. WhatsApp alone keeps received, sent, and status media in several folders, some of them hidden from the gallery.

Gallery apps are built for browsing, not for cleanup. They don't search the whole storage, and they can't tell a memory from clutter.

## 2. Goals and non-goals

### Goals

- Find **every** image on local storage, including folders hidden from the gallery.
- Detect images that are likely deletable, grouped into understandable categories.
- Make reviewing hundreds of candidates fast.
- Never delete anything without explicit confirmation, and keep every deletion reversible for a while.
- Keep the code modular, so detectors, image sources, and model runtimes can be swapped or added easily.

### Non-goals (for now)

- Cloud photo libraries (Google Photos and similar).
- Cloud-based classifiers.
- Publication on the Play Store.
- iOS and desktop versions.
- Visual polish beyond a clean, functional UI.

## 3. Key decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | **Android first, native Kotlin + Jetpack Compose.** | The heavy work is platform-specific: file system access, permissions, background jobs, image decoding, on-device ML. Cross-platform frameworks (Flutter, React Native) would still need native modules for all of it, plus a bridge layer that is hard to debug. Compose Multiplatform keeps a desktop option open. |
| D2 | **Python lab as the reference implementation.** | Detectors are built and tuned in Python on real photos first, then ported to Kotlin. Golden test fixtures check that both implementations give the same results. |
| D3 | **On-device analysis only.** | Privacy, zero cost, works offline. Cloud classifiers were evaluated (cheap, roughly $1–2 per 10,000 images) and postponed. The architecture keeps them pluggable (see §7). |
| D4 | **Recall over precision.** | Missing a deletable image is worse than surfacing one the user wants to keep. Thresholds start low, any single detector can make an image a candidate, and protection signals only lower an image in the ordering, never remove it from the candidates. |
| D5 | **Photo age is not a deletion signal.** | An old photo may be old precisely because it survived earlier cleanups. Per-image review state replaces age (see §6.3). |
| D6 | **Nothing is deleted without confirmation, and deletions go to a trash first, kept 30 days by default.** | Mistakes must be recoverable. How long is a user setting. |
| D7 | **Personal, sideloaded distribution first.** | Allows the "All files access" permission (`MANAGE_EXTERNAL_STORAGE`) without Play Store review. |
| D8 | **Local images only.** | Cloud libraries need their own cleanup, but they are a separate, future problem (see §11). |

## 4. Finding images on Android

Two layers:

1. **MediaStore**, Android's media index. It covers the gallery and `Android/media/com.whatsapp/WhatsApp/Media/...`. It does **not** index folders containing a `.nomedia` file, such as `WhatsApp Images/Sent`. That is exactly where clutter piles up unseen.
2. **Deep scan** with `MANAGE_EXTERNAL_STORAGE`: a file system walk of shared storage that finds every image file, `.nomedia` folders included. The deep scan skips `.trashed-*` files (the system trash, which MediaStore hides by default) and `.pending-*` files (still being written), in both the lab and the app. Since Android 11, `Android/data` and `Android/obb` stay inaccessible even with this permission.

Deep scan is the default (D7). The scanner merges both layers and identifies each file by its path, size, and modification time.

Indexing is incremental. A first full pass runs in the background, ideally while charging, and later passes only process new or changed files. On Android, WorkManager runs a pass whenever the app opens and once a day while the phone charges. A pass runs as a foreground service with a notification, since a first scan of thousands of images outlasts the few minutes Android gives background work. The index keeps what each pass did, so a stopped pass resumes where it was.

## 5. Detectors

Each detector looks at an image (or at the image index) and emits **signals**. No detector decides on its own whether an image should be deleted.

| Category | Technique | Notes |
|---|---|---|
| `EXACT_DUPLICATE` | Group by file size, then content hash (xxHash) | Exact, cheap |
| `NEAR_DUPLICATE` | Perceptual hash (pHash/dHash) + embedding similarity | Catches recompressed and resized copies, such as those made by WhatsApp. Suggests keeping the best copy: highest resolution, least compression, original EXIF metadata. |
| `BURST` | Capture-time clustering + embedding similarity | Suggests the best shot: sharpness, eyes open (face detection). |
| `BLURRY` | Variance of the Laplacian on a downscaled grayscale image | Measure the subject region when one is found, to avoid flagging portraits with an intentionally blurred background. |
| `BAD_EXPOSURE` | Luminance histogram | Pocket shots, black frames, blown-out frames |
| `SCREENSHOT` | Path and file name, image size equal to a screen resolution, no camera EXIF | Nearly deterministic |
| `DOCUMENT` | Text coverage (PP-OCRv4 text detection) + MobileCLIP zero-shot classification | Slides, whiteboards, handwritten notes, homework, receipts, printed pages. Only detection runs: how much text there is matters, not what it says. |
| `FORWARDED` | Messaging-app folder, missing camera EXIF, meme/sticker zero-shot prompts | Forwarded memes and greeting images |
| `STICKER` | The file is in a messaging app's sticker folder (`WhatsApp Stickers`, `WhatsApp Backup Excluded Stickers`) | Nearly deterministic. On the first phone, 62% of the images were stickers. |

### MobileCLIP

[MobileCLIP](https://github.com/apple/ml-mobileclip) is a compact image–text embedding model that runs on phones. A single embedding per image, computed once and cached, serves three purposes:

- near-duplicate search (cosine similarity between embeddings);
- zero-shot classification against text prompts such as *"a photo of a projector slide"* or *"handwritten homework on paper"*;
- natural-language search later on.

With about 10,000 images, the embeddings take roughly 20 MB (512 float32 values per image), and comparing every pair directly is feasible without an approximate-nearest-neighbor index.

**Variant: MobileCLIP2-S0, as ONNX.** [MobileCLIP2](https://arxiv.org/abs/2508.20691) (2025) keeps MobileCLIP's architectures and improves their training: S0 goes from about 68% to 71.5% ImageNet zero-shot accuracy at the same size. S0 is the smallest variant (a 45 MB float32 image encoder), and its ONNX export ([plhery/mobileclip2-onnx](https://huggingface.co/plhery/mobileclip2-onnx), `onnx/s0/`, pinned to a revision) runs unchanged in the lab and in the app, on ONNX Runtime in both. Both compute the same embeddings, so golden fixtures can compare them directly. The export was checked against Apple's open_clip weights on the golden fixtures: image and text embeddings agree to within 1e-6. Input: shortest side resized to 256 px (bilinear), center crop to 256×256, RGB scaled to [0, 1], no normalization. Zero-shot uses the checkpoint's learned temperature, 64.33. The text encoder (254 MB) only turns prompts into embeddings. The lab computes those once into `lab/src/cribrum_lab/prompt_embeddings.json`, and the app build copies that file into its resources, so the text encoder never goes on the phone.

On the first phone's 912 labels, MobileCLIP2-S0 raised recall from 73% to 81% at the starting thresholds. With thresholds suggested for 80% recall, it flags 117 kept images instead of 146, for about the same number of candidates. Its similarities spread like MobileCLIP-S0's: at 0.90 it links 3,125 pairs of the 10,000 images against 2,905, so the NEAR_DUPLICATE and BURST thresholds stayed.

**License.** The MobileCLIP2 weights come under Apple's Machine Learning Research Model license, which allows research and other non-commercial use only. Cribrum is a personal, non-commercial app, so this is accepted. A commercial release would need another model, such as MobileCLIP-S0 (Apple sample code license) or Meta's Perception Encoder (Apache 2.0). The PP-OCRv4 text detector is Apache 2.0, which asks for the license text next to a redistributed copy. The pinned RapidOCR revision ships none, so the app does not package a notice yet: add PaddleOCR's license to the APK before any release.

On the dev machine (Ryzen 5 3600XT, CPU only), decoding and preprocessing take about 80 ms per image on one core (12 worker processes share it), and inference about 19 ms per image in batches of 16 on all cores: roughly 5 minutes for 10,000 images. These numbers were measured with MobileCLIP-S0, which has the same image encoder architecture (MCi0).

**In the app.** The build downloads the image encoder from the same revision, checks the same SHA-256 as the lab, and packs it in the APK's assets with Apple's license next to it. It is never committed. The app ports the lab's preprocessing step by step (Pillow's draft decoding, EXIF orientation, bilinear resize), and on the golden fixtures its embeddings match the lab's with a cosine of at least 0.99999. ONNX Runtime adds a 35 MB native library per ABI, so the APK only carries the arm64 one. On a Galaxy Z Fold7 (Snapdragon 8 Elite) the encoder takes about 47 ms per image on the CPU, the same at batch 1 and batch 16, and loading the model takes under half a second. Preprocessing takes 30 ms for a WhatsApp JPEG, 53 ms for a 12 MP camera JPEG (draft decoding does most of the work) and 280 ms for a 1080×2400 PNG screenshot, which has no draft mode. That puts a 10,000-photo library at roughly 15 to 20 minutes on one core, less with preprocessing spread across cores.

**Text coverage in the app.** PP-OCRv4 detection runs on the CPU with one thread per image, as in the lab, so the scan's workers do not fight over cores. The build downloads the model from the lab's pinned URL and SHA-256 and packs it as a resource. Pillow's draft rule asks for both sides to stay at least 640 px, so a 1600×1200 JPEG decodes at full size, where MobileCLIP's 256 px draft decodes it at a quarter. That is why text preprocessing costs more than MobileCLIP's (about 65 to 100 ms against 31 for a 12 MP JPEG, and 80 to 115 ms against 14 for a WhatsApp one; PNG screenshots have no draft mode and cost the same, 110 to 175 ms against 117). On a Galaxy Z Fold7, with a release-like (non-debuggable) build and a warm runtime, detection plus preprocessing takes 150 to 240 ms per image on one thread, and the model alone about 100 to 150 ms. With one image per core (8 threads) it runs 20 to 26 images per second, so text coverage adds roughly 6 to 8 minutes to a 10,000-photo scan. Loading the model takes about 0.35 s. The speed tests must be run on such a build: a debuggable build runs the Kotlin preprocessing about three times slower, and the first rounds after install run slower again while the runtime compiles the hot loops. Hence `TextSpeedDeviceTest` warms up before it times, and the MobileCLIP times above, from a debuggable build, are about twice too high for preprocessing (14, 31 and 117 ms for the WhatsApp JPEG, the camera JPEG and the screenshot on the same build).

## 6. Ranking

### 6.1 Candidate selection

An image becomes a candidate if **any** signal exceeds its category threshold (D4). Thresholds are tuned in the lab for recall (§10), and the values are in `ranker.py` and `Ranker.kt`.

### 6.2 Protection signals

These signals hint that an image is a memory: faces, favorites, membership in a user album, uniqueness (no near-duplicates). They lower an image's position in the review order but **never** remove it from the candidates.

They are ordinary `Signal`s whose category is a `Protection` (`FACES`, `FAVORITE`, `ALBUM`) instead of a deletable `Category`. Detectors emit them, the index stores them, and detector versions apply to them, all exactly like other signals. They have no threshold, so they never make an image a candidate. The ranker multiplies an image's likelihood by `1 - weight × score` for each one. Uniqueness is not a signal: the ranker derives it from the image being in no duplicate or burst group. Candidates always stay ahead of the other images in the order. Favorites and albums only exist on Android (MediaStore), so the lab has no detector for them. Faces come from [YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet), a 230 KB ONNX face detector under the MIT license. Like MobileCLIP, the same file runs on ONNX Runtime in the lab and in the app, so ML Kit is not needed for faces. The lab commits the file, and the app build copies it from there instead of downloading it.

### 6.3 Review state

Each image carries a review state:

- `UNREVIEWED`: candidate as usual.
- `KEPT`: the user reviewed it and chose to keep it. It is not suggested again unless a **new** signal appears. For example, a higher-resolution copy shows up and turns this one into the inferior duplicate. Keeping an image records the identity of each signal it has (category, group, role). A signal is new when its identity is not among them: a newer detector version, a higher score or a retuned threshold is not new.
- `TRASHED`: in the app trash.

### 6.4 "Review everything" mode

All `UNREVIEWED` images, ordered from most to least likely deletable. Going through all of them eventually brings false negatives to zero by construction. A missed image just shows up later in the order.

## 7. Architecture

The core idea is a pipeline of independent, interchangeable components.

```kotlin
interface ImageSource {                  // MediaStore + deep scan today; cloud libraries later
    fun scan(): Flow<ImageRef>
}

interface Detector {                     // one per technique; a cloud classifier would be one more
    val id: String
    val version: Int
    val category: Category? get() = null
    suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal>
}

interface GroupDetector {                // duplicates, bursts: looks at all images at once
    val id: String
    val version: Int
    val category: Category? get() = null
    suspend fun analyze(images: List<ImageRef>, context: AnalysisContext): Map<ImageRef, List<Signal>>
}

enum class Role { BEST, INFERIOR }      // a group member's role, stored in evidence["role"]

data class Signal(
    val detectorId: String,
    val detectorVersion: Int,
    val category: Category,
    val score: Float,                    // 0..1
    val evidence: Map<String, String> = emptyMap(),
)

interface Ranker {                       // combines signals into candidates and an ordering
    fun rank(images: List<IndexedImage>): List<Candidate>
}

interface Trash {                        // app-managed trash, retention set by the user (30 days by default)
    suspend fun moveToTrash(images: List<ImageRef>): List<ImageRef>
    suspend fun restore(images: List<ImageRef>): List<ImageRef>
    suspend fun trashed(): List<TrashedImage>
    suspend fun purge(images: List<ImageRef>)
    suspend fun purgeExpired(): List<ImageRef>
}

interface ModelRuntime                   // ONNX Runtime behind one interface
```

- Signals are stored with the version of the detector that produced them. When a detector improves, only its signals are recomputed.
- Detectors get per-image data they don't compute themselves (content hash, metadata, embeddings) through lookups in `AnalysisContext`. The pipeline backs them with the index, so each value is computed once and shared by every detector.
- Group detectors (duplicates, bursts) run over the index instead of over a single image. They use the same `Signal` output. Every member of a group gets a signal, with the group id in `evidence["group"]` and its role in `evidence["role"]`: `best` for the copy to keep (with score 0, so no threshold ever suggests it), `inferior` for the others. Images in no group get no signal.
- The Python lab mirrors these interfaces one-to-one, so porting is mostly translation.

### Planned stack

| Concern | Choice |
|---|---|
| UI | Jetpack Compose |
| Index | Room (SQLite) |
| Background work | WorkManager |
| Thumbnails | Coil |
| On-device ML | ONNX Runtime: MobileCLIP, YuNet (faces), PP-OCRv4 text detection. The same files as the lab, so results match. |

### Index (draft)

| Field | Purpose |
|---|---|
| `path`, `size`, `mtime` | Identity and change detection |
| `width`, `height`, `mime`, `exif_*` | Metadata used by detectors and best-copy selection |
| `content_hash` | Exact duplicates |
| `phash` | Near-duplicates |
| `embedding` | MobileCLIP vector |
| `review_state` | §6.3 |
| `signals` (separate table) | Detector output, keyed by image + detector id + version |

## 8. Review UX

The bottleneck of a recall-first design is review speed, so this screen is the heart of the app.

- The home screen lists every category in fixed order (`Category.entries`). A category without an Android detector yet shows a short "coming soon" line, an empty one shows 0 images to review, and tapping either opens nothing.
- Candidates are grouped by category, and each group is sorted by confidence.
- A sticker is listed under `STICKER` only, whatever else flags it. Stickers were 62% of the images on the first phone and took most of the blurry, document, near-duplicate and forwarded candidates, which hid the photos there. It stays a candidate and keeps its place in the review order: only the grouping changes.
- In grids, likely-deletable images are **preselected**. One tap keeps an image.
- A "Skip" button, to the right of the trash button, leaves the current page for later. It decides nothing: the images stay `UNREVIEWED`, and nothing is stored. They are left out of the next pages while the category stays open, and show up again the next time it is opened.
- Duplicate and burst groups have a "keep best, trash the rest" action, with side-by-side comparison.
- Quick gestures and no unnecessary confirmations, except a final summary before trashing.
- "Never suggest this again" works per image and per folder.
- A settings screen, opened from the home screen, shows app information, the trash retention choice, and a warning that uninstalling leaves the trash folder on shared storage.
- A storage map screen, opened from settings, shows a treemap of indexed image space across explored folders to reveal where space is concentrated. It counts indexed images that are not in the trash. Single-child parent folders without direct files collapse so the map starts at the first branching point. A folder keeps its color when you zoom into it: its largest subfolder inherits the folder's hue and the others spread around it. The selected folder gets a thick outline in the theme's text color and the other folders dim.

## 9. Deletion and trash

The system trash (`MediaStore.createTrashRequest`, Android 11+) only works for files MediaStore indexes, so it misses `.nomedia` folders. Cribrum therefore keeps its **own trash**: files move to an app-managed folder together with their original path, and are purged after a retention the user sets in the settings screen: 7, 14, 30, 60 or 90 days, 30 by default. One mechanism covers every file.

The trash is a `.cribrum-trash` folder at the root of each storage volume, with a `.nomedia` file so galleries skip it. Each trashed file gets its own folder there, holding the file under its own name and an `origin.properties` with its original path, size, mtime and the time it was trashed. Moving is a rename on the same volume: it is instant, and it keeps the mtime, so a restored file is the same `ImageRef` as before. Because the trash lives on shared storage and needs no database, it survives an uninstall and can be restored by hand. A file that changed since its scan is not trashed, and a restore never overwrites a file that took the original path. The trash is the only code that deletes files. The retention is read on every purge, not stored with the item, so a change applies to the items already in the trash too: lowering it purges what is now older than the new value at the next scan, so the settings screen first asks for confirmation, with the number of images that would go, when the new value would purge at least one. It purges an item once the retention has passed since trashing it, or when the user empties the trash.

A trashed image stays in the index, as `TRASHED`, while its file is in the trash: the scan does not find it, but does not forget it either. Its signals and review state are still there if it comes back. The index forgets it when the trash purges it.

## 10. Python lab

Goal: prove that the detectors work on real data before writing the app.

1. **Collect:** copy the device's images to a local folder (`adb pull`, or a USB file transfer), messaging-app folders included. Image data is never committed.
2. **Detect:** implement the detectors behind the same interfaces as §7.
3. **Label:** a keyboard-driven labeling tool (`k` = keep, `d` = delete). A label carries no category: the labeler can tell what to keep, but not which detector should catch it, and asking for one turned out slow and error-prone. MobileCLIP clusters similar images so a whole cluster can be labeled at once. Target: 500–1,000 labeled images.
4. **Measure:** **recall** first, then the number of candidates it costs. The target is 95% recall on deletable images, measured for the whole library, not for the labeled sample. The sample is stratified: half of it is candidates, spread over the categories, and half is images no detector flagged, while 88% of the library were candidates when the first labels were drawn. It over-represents the images that are hardest to catch, so its recall reads lower than the library's. The evaluate report prints both. For the library, it weights each label by the size of its stratum in the index divided by the number of labels in that stratum. Each label records its stratum from now on (`candidate` or `unflagged`, `labels.sqlite` gets the column when it is opened). The 1,200 labels made before that have none, so the report places them by whether the thresholds in use when they were sampled made their image a candidate (`SAMPLING_THRESHOLDS`, the values before this change). That is an approximation, and the report says how many labels it applies to.

    | Thresholds | Sample recall (716 deletes) | Library recall | Kept images flagged (of 484) | Candidates (of 10,220) |
    |---|---|---|---|---|
    | Before | 79% (567) | 94% | 210 | 9,010 |
    | Now | 86% (615) | 96% | 219 | 9,171 |
    | Cheapest found for 95% on the sample | 96% (689) | 97% | 342 | 9,751 |

    On the sample, the 90% to 95% steps catch the last images only through `FORWARDED` at 0.45, which flags every messaging-app image and makes 95% of the library candidates. Measured on the library, the target is met with the thresholds in `ranker.py` and `Ranker.kt`. Stickers (6,306 images, 62% of the library) are always candidates, so they are more than two thirds of the candidates at any recall.

    **How the thresholds were picked.** The evaluate report prints, for each category and threshold, the labeled deletes and keeps the category flags and the ones it adds to the candidates the other categories already make. A threshold is lowered only when the images it adds to the whole candidate set are clearly more often deletes than keeps. Steps that add a handful of images are noise. The values are the maintainer's choice, read from that table:

    | Step | Deletes added | Keeps added | Candidates added |
    |---|---|---|---|
    | `DOCUMENT` 0.5 to 0.2 (applied) | 48 | 9 | 158 |
    | `DOCUMENT` 0.2 to 0.1 | 17 | 11 | 111 |
    | `BLURRY` 0.4 to 0.2 | 6 | 7 | 34 |
    | `NEAR_DUPLICATE` 0.5 to 0.3 | 3 | 6 | 33 |
    | `BAD_EXPOSURE` 0.5 to 0.4 (applied) | 0 | 0 | 0 |
    | `BAD_EXPOSURE` 0.5 to 0.1 | 1 | 0 | 5 |

    Only `DOCUMENT` clears the rule. `BAD_EXPOSURE` at 0.4 changes nothing on these labels. Every other category stays at 0.5 (`BLURRY` at 0.4). `BURST` has a small list (63 images, 10 deletes and 28 keeps), but recall comes before precision and it costs few reviews. `FORWARDED` at 0.4 would flag every messaging-app image, for 301 more deletes and 224 more keeps over 2,198 more images outside sticker folders, about the sample's base rate, so the extra images tell nothing.

    Lower thresholds also move images up the review order: the chance that a labeled delete comes before a labeled keep goes from 0.768 to 0.785.

    **Same labels.** The thresholds were picked on the same labels they are measured on. A bootstrap over the labels gives 83% to 88% for the sample recall and 95.2% to 96.6% for the library recall (95% intervals). A review of the first set of thresholds tried, which moved more thresholds and reached 90% on the sample, held out half of the labels in 20 random splits: 89.5% recall on average and 83% at worst. New labels, drawn with the recorded strata, are the check.

    The 101 deletes in the sample that no detector flags are 95 in messaging-app folders and 6 in camera folders. For all 95 of the messaging ones the closest category is `FORWARDED`. They are photos in a messaging folder that the zero-shot prompts do not read as memes or greetings, so nothing in the signals tells them from the 108 kept messaging images that are flagged. What to chase next is a signal for received photos nobody kept, not a different threshold.

    Recall of the categories on the 716 deletes, with the deletes no other category flags in brackets: `FORWARDED` 45% (32), `DOCUMENT` 43% (81), `STICKER` 33% (30), `SCREENSHOT` 15% (29), `NEAR_DUPLICATE` 16% (14), `BLURRY` 14% (6), `BAD_EXPOSURE` 12% (1), `BURST` 1% (4), `EXACT_DUPLICATE` 0.3% (0). Most deletable images are flagged by several categories, so a category's recall alone is not what it adds. The report prints both columns, so a re-run refreshes them.
5. **Freeze:** export golden fixtures (sample images with expected signals) for the Kotlin implementation's tests. The fixture images are synthetic, generated from fixed seeds (`fixtures/`), never personal photos.

## 11. Future work and open questions

- **Cloud classifiers:** a `Detector` implementation that calls a vision model API. It should be opt-in, send only downscaled thumbnails, and exclude images with faces by default.
- **Cloud libraries:** a new `ImageSource`. Since 2025 the Google Photos Library API no longer gives third-party apps read access to a user's whole library, and it never supported deletion. Cleaning Google Photos may need a different approach.
- **Play Store:** "All files access" is granted only to specific app categories. Deep scan might have to become an optional feature.
- **Desktop and iOS:** reachable through Kotlin Multiplatform / Compose Multiplatform.
- **Learning from decisions:** adjust per-category thresholds from the user's keep/trash history.
- **Recall level as a setting:** the user picks how many of the deletable images the candidates should catch, trading a longer review for fewer misses. The lab would export one set of thresholds per level instead of one.
- **Compression instead of deletion:** for images the user wants to keep but that take a lot of space, offer to recompress them (a lower JPEG quality, or HEIC or AVIF) or downscale them. The original would go to the trash like any deletion, so a bad result can be undone, and EXIF metadata and the mtime would be kept.
- **Profiling the app:** profile a full first scan and the review screens on a real phone (Android Studio profiler, Macrobenchmark) and fix the hot spots. §5 already records the per-image cost of MobileCLIP and text coverage. The first target is the total time of a first scan of 10,000 photos.
