# Cribrum — Design Document

- **Status:** Draft
- **Last updated:** 2026-10-18

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
| D6 | **Nothing is deleted without confirmation, and deletions go to a 30-day trash first.** | Mistakes must be recoverable. |
| D7 | **Personal, sideloaded distribution first.** | Allows the "All files access" permission (`MANAGE_EXTERNAL_STORAGE`) without Play Store review. |
| D8 | **Local images only.** | Cloud libraries need their own cleanup, but they are a separate, future problem (see §11). |

## 4. Finding images on Android

Two layers:

1. **MediaStore**, Android's media index. It covers the gallery and `Android/media/com.whatsapp/WhatsApp/Media/...`. It does **not** index folders containing a `.nomedia` file, such as `WhatsApp Images/Sent`. That is exactly where clutter piles up unseen.
2. **Deep scan** with `MANAGE_EXTERNAL_STORAGE`: a file system walk of shared storage that finds every image file, `.nomedia` folders included. Since Android 11, `Android/data` and `Android/obb` stay inaccessible even with this permission.

Deep scan is the default (D7). The scanner merges both layers and identifies each file by its path, size, and modification time.

Indexing is incremental. A first full pass runs in the background, ideally while charging, and later passes only process new or changed files.

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

### MobileCLIP

[MobileCLIP](https://github.com/apple/ml-mobileclip) is a compact image–text embedding model that runs on phones. A single embedding per image, computed once and cached, serves three purposes:

- near-duplicate search (cosine similarity between embeddings);
- zero-shot classification against text prompts such as *"a photo of a projector slide"* or *"handwritten homework on paper"*;
- natural-language search later on.

With about 10,000 images, the embeddings take roughly 20 MB (512 float32 values per image), and comparing every pair directly is feasible without an approximate-nearest-neighbor index.

**Variant: MobileCLIP-S0, as ONNX.** It is the smallest variant (a 45 MB float32 image encoder), and its ONNX export ([Xenova/mobileclip_s0](https://huggingface.co/Xenova/mobileclip_s0), pinned to a revision) runs unchanged in the lab (ONNX Runtime) and on Android (ONNX Runtime Mobile). Both compute the same embeddings, so golden fixtures can compare them directly. Input: shortest side resized to 256 px (bilinear), center crop to 256×256, RGB scaled to [0, 1], no normalization. The text encoder (170 MB) only turns prompts into embeddings. The lab computes those once, and the app ships them as constants, so the text encoder never goes on the phone. The weights come under Apple's sample code license, which allows use and redistribution as long as its notice is kept with them. On the dev machine (Ryzen 5 3600XT, CPU only), decoding and preprocessing take about 80 ms per image on one core (12 worker processes share it), and inference about 19 ms per image in batches of 16 on all cores: roughly 5 minutes for 10,000 images. Larger variants (S2, MobileCLIP2) remain an option if the lab measures too low a recall.

## 6. Ranking

### 6.1 Candidate selection

An image becomes a candidate if **any** signal exceeds its category threshold (D4). Thresholds are tuned in the lab for recall (§10).

### 6.2 Protection signals

These signals hint that an image is a memory: faces, favorites, membership in a user album, uniqueness (no near-duplicates). They lower an image's position in the review order but **never** remove it from the candidates.

They are ordinary `Signal`s whose category is a `Protection` (`FACES`, `FAVORITE`, `ALBUM`) instead of a deletable `Category`. Detectors emit them, the index stores them, and detector versions apply to them, all exactly like other signals. They have no threshold, so they never make an image a candidate. The ranker multiplies an image's likelihood by `1 - weight × score` for each one. Uniqueness is not a signal: the ranker derives it from the image being in no duplicate or burst group. Candidates always stay ahead of the other images in the order. Favorites and albums only exist on Android (MediaStore), so the lab has no detector for them. Faces come from [YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet), a 230 KB ONNX face detector under the MIT license. Like MobileCLIP, the same file runs on ONNX Runtime in the lab and in the app, so ML Kit is not needed for faces.

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
    suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal>
}

interface GroupDetector {                // duplicates, bursts: looks at all images at once
    val id: String
    val version: Int
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

interface Trash {                        // app-managed trash, 30-day retention
    suspend fun moveToTrash(images: List<ImageRef>): List<ImageRef>
    suspend fun restore(images: List<ImageRef>): List<ImageRef>
    suspend fun trashed(): List<TrashedImage>
    suspend fun purge(images: List<ImageRef>)
    suspend fun purgeExpired(): List<ImageRef>
}

interface ModelRuntime                   // LiteRT or ONNX Runtime behind one interface
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

- Candidates are grouped by category, and each group is sorted by confidence.
- In grids, likely-deletable images are **preselected**. One tap keeps an image.
- Duplicate and burst groups have a "keep best, trash the rest" action, with side-by-side comparison.
- Quick gestures and no unnecessary confirmations, except a final summary before trashing.
- "Never suggest this again" works per image and per folder.

## 9. Deletion and trash

The system trash (`MediaStore.createTrashRequest`, Android 11+) only works for files MediaStore indexes, so it misses `.nomedia` folders. Cribrum therefore keeps its **own trash**: files move to an app-managed folder together with their original path, and are purged after 30 days. One mechanism covers every file.

The trash is a `.cribrum-trash` folder at the root of each storage volume, with a `.nomedia` file so galleries skip it. Each trashed file gets its own folder there, holding the file under its own name and an `origin.properties` with its original path, size, mtime and the time it was trashed. Moving is a rename on the same volume: it is instant, and it keeps the mtime, so a restored file is the same `ImageRef` as before. Because the trash lives on shared storage and needs no database, it survives an uninstall and can be restored by hand. A file that changed since its scan is not trashed, and a restore never overwrites a file that took the original path. The trash is the only code that deletes files: it purges an item 30 days after trashing it, or when the user empties the trash.

## 10. Python lab

Goal: prove that the detectors work on real data before writing the app.

1. **Collect:** copy the device's images to a local folder (`adb pull`, or a USB file transfer), messaging-app folders included. Image data is never committed.
2. **Detect:** implement the detectors behind the same interfaces as §7.
3. **Label:** a keyboard-driven labeling tool (`k` = keep, `d` = delete). A label carries no category: the labeler can tell what to keep, but not which detector should catch it, and asking for one turned out slow and error-prone. MobileCLIP clusters similar images so a whole cluster can be labeled at once. Target: 500–1,000 labeled images.
4. **Measure:** **recall** first, then the number of candidates it costs. The per-category thresholds are chosen together, for the recall of all categories at once. Target: about 80% recall on deletable images. It was 95%, but on the first real labels (912 images from one phone) 80% already made 69% of the images candidates, and anything above 80% made 96% of them candidates, since only flagging nearly every WhatsApp image catches the rest. A filter that flags everything does not help the review. Images that are not candidates still come after the candidates in the review order, so a miss is shown later, not hidden.
5. **Freeze:** export golden fixtures (sample images with expected signals) for the Kotlin implementation's tests. The fixture images are synthetic, generated from fixed seeds (`fixtures/`), never personal photos.

## 11. Future work and open questions

- **Cloud classifiers:** a `Detector` implementation that calls a vision model API. It should be opt-in, send only downscaled thumbnails, and exclude images with faces by default.
- **Cloud libraries:** a new `ImageSource`. Since 2025 the Google Photos Library API no longer gives third-party apps read access to a user's whole library, and it never supported deletion. Cleaning Google Photos may need a different approach.
- **Play Store:** "All files access" is granted only to specific app categories. Deep scan might have to become an optional feature.
- **Desktop and iOS:** reachable through Kotlin Multiplatform / Compose Multiplatform.
- **Learning from decisions:** adjust per-category thresholds from the user's keep/trash history.
- **Recall level as a setting:** the user picks how many of the deletable images the candidates should catch, trading a longer review for fewer misses. The lab would export one set of thresholds per level instead of one.
