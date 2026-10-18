# Cribrum lab

Python prototype and reference implementation of Cribrum's detectors. Detectors are built, tuned, and measured here on real photos, then ported to the Android app. See [`../docs/design.md`](../docs/design.md), section 10.

## Setup

The lab uses [uv](https://docs.astral.sh/uv/). `uv.lock` pins every dependency, so commit it together with any change to `pyproject.toml`.

```bash
cd lab
uv sync                   # create .venv and install everything, dev tools included
uv run pytest             # tests
uv run ruff check         # lint
uv run ruff format        # format
```

CI runs the same checks on every pull request that touches `lab/`.

## Data

The lab works on copies of your images under `lab/data/`, which git ignores. To copy them from an Android device:

1. Enable USB debugging (Settings → Developer options) and connect the phone.
2. Check that `adb devices` lists it, then accept the prompt on the phone.
3. Run:

   ```bash
   lab/scripts/pull-device-images.sh
   ```

The script copies the images (JPEG, PNG, WebP, GIF, HEIC) from the camera, pictures, screenshots, downloads and documents folders, and from the WhatsApp, Telegram and Instagram media folders (in both their old and Android 11+ locations), into `lab/data/device/`. Videos, audio and other files stay on the device, and so do gallery thumbnail caches (`.thumbnails`). It keeps the device's folder layout, because detectors use paths as evidence, and it keeps modification times. It includes the WhatsApp folders hidden from the gallery by `.nomedia`. Running it again overwrites the copies.

To try it on a few folders first, pass them relative to `/sdcard`:

```bash
lab/scripts/pull-device-images.sh "DCIM/Screenshots" Pictures
```

Never commit anything under `lab/data/`.

### From WSL

WSL2 does not see USB devices, so the Linux `adb` cannot reach a phone plugged into Windows. Forward the phone to WSL with [usbipd-win](https://github.com/dorssel/usbipd-win):

1. Once, in WSL: `sudo apt install adb`.
2. Once, in PowerShell: `winget install usbipd`, then `usbipd list` to find the phone's BUSID, and `usbipd bind --busid <BUSID>` from an administrator PowerShell.
3. Every time you plug the phone in, stop the Windows adb server first (`adb kill-server`, or close Android Studio), because it holds the device. Then run `usbipd attach --wsl --busid <BUSID>` in PowerShell.
4. In WSL, check that `adb devices` lists the phone, then run the script as above.

## Analyze

```bash
cd lab
uv run cribrum-lab scan data/device    # find and analyze every image under the folder
uv run cribrum-lab report              # candidates per category, top 10 paths each
```

The first `scan` downloads MobileCLIP-S0 (45 MB) into `lab/data/models/`; `--no-embeddings` skips it. `scan` stores everything in `lab/data/index.sqlite` as it goes. Run it again after an interruption or after copying new images: it only analyzes what is new or changed, or what a newer detector version has not seen yet. Images that are gone from the folder leave the index. `--jobs` sets the number of worker processes (all cores by default). The index refuses to open if its schema version changed: delete it and scan again.

## Layout

| Module | Contents |
|---|---|
| `cribrum_lab.model` | Core types: `ImageRef`, `Category`, `Signal` |
| `cribrum_lab.sources` | `ImageSource` interface and `FilesystemImageSource`, which finds every image under a folder (videos are ignored) |
| `cribrum_lab.metadata` | `read_metadata`: displayed size, MIME type, camera EXIF and capture time |
| `cribrum_lab.index` | `ImageIndex`: SQLite store for images, metadata, signals per detector version, and review state |
| `cribrum_lab.imaging` | `load_grayscale`: downscaled luminance for content-based detectors |
| `cribrum_lab.hashing` | `content_hash` (xxHash of a whole file) and `perceptual_hash` (64-bit pHash), for duplicates |
| `cribrum_lab.screens` | Known screen resolutions, for screenshots |
| `cribrum_lab.folders` | What a folder says about an image: camera or messaging app |
| `cribrum_lab.detectors` | `Detector` interface and its implementations |
| `cribrum_lab.pipeline` | `run`: scan, metadata, detectors and group detectors into the index |
| `cribrum_lab.cli` | The `cribrum-lab` command |
| `cribrum_lab.runtime` | `ModelRuntime` interface and `OnnxRuntime` |
| `cribrum_lab.mobileclip` | MobileCLIP-S0 image and text embeddings, zero-shot scores |
| `cribrum_lab.ranker` | `Ranker`: per-category thresholds, candidates and the review order |
