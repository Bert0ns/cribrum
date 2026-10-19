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

Once per computer:

1. In WSL, run `sudo apt install adb`. Besides `adb`, it installs the udev rules that let a normal user open the phone. Without them, `adb devices` shows the phone as `no permissions`. The Android SDK's `adb` lacks these rules.
2. In PowerShell, run `winget install --id dorssel.usbipd-win`.
3. In an administrator PowerShell, run `usbipd list` to find the phone's BUSID, then `usbipd bind --busid <BUSID>`. The binding survives reboots. The BUSID depends on the USB port, so bind again after switching ports.

Every time you plug the phone in:

1. Stop the Windows adb server, because it holds the device: `adb kill-server` in PowerShell, or close Android Studio.
2. Run `usbipd attach --wsl --busid <BUSID>`. A normal PowerShell is enough. From WSL, call it as `usbipd.exe`.
3. In WSL, run `adb devices`. The first time, it shows the phone as `unauthorized` until you accept the USB debugging prompt on the phone. Accepting it makes the phone reconnect, which detaches it from WSL, so attach it again. Then run the script as above.

The Windows and WSL `adb` have different keys, so a phone that already trusts the Windows one still asks again. `usbipd attach --auto-attach` attaches the phone again every time it reconnects, until you stop it with Ctrl+C.

If `usbipd attach` fails with "The VBoxUsbMon driver is not correctly installed", uninstalling VirtualBox has removed the USB driver that usbipd-win shares with it. Run `winget uninstall --id dorssel.usbipd-win` and then `winget install --id dorssel.usbipd-win`, and bind the phone again.

## Analyze

```bash
cd lab
uv run cribrum-lab scan data/device    # find and analyze every image under the folder
uv run cribrum-lab report              # candidates per category, top 10 paths each
```

The first `scan` downloads MobileCLIP-S0 (45 MB) and PP-OCRv4's text detector (5 MB) into `lab/data/models/`; `--no-embeddings` skips it. `scan` stores everything in `lab/data/index.sqlite` as it goes. Run it again after an interruption or after copying new images: it only analyzes what is new or changed, or what a newer detector version has not seen yet. Images that are gone from the folder leave the index. `--jobs` sets the number of worker processes (all cores by default). The index refuses to open if its schema version changed: delete it and scan again.

## Label

```bash
uv run cribrum-lab label               # then open http://127.0.0.1:8765/
```

The page shows one image at a time from a sample of 500 (`--size`). Half of the sample are candidates, spread over every category, and half are images no detector flagged, so that recall can also be measured on what the detectors missed. Keys: `k` keep, `d` delete, `u` undo, `s` skip. A label says only whether you would keep the image: the detectors work out why. To check whether an image is really blurry, click it or press `z`: the original opens at full size, where 100% is one image pixel per screen pixel. The mouse wheel zooms, dragging moves the image, and `z` or `Esc` closes it. In a grid, `z` zooms on the image under the mouse. Labels go to `lab/data/labels.sqlite` and are keyed by content hash, so they survive copying the images again. A new run of `label` skips images that are already labeled; `--seed` draws a different sample.

With `--clusters`, similar images in the sample (by MobileCLIP embedding) come first, as grids of up to 48: click an image to leave it out, then one key labels the rest. The images left out and those alike to no other are shown one by one afterwards, or in a later session.

## Evaluate

```bash
uv run cribrum-lab evaluate            # --out data/report.txt to keep a copy
```

Prints the recall on the labeled images with the current thresholds, and the number of candidates it costs over the whole index. Per category, it counts the images labeled delete and keep that the category flags. Then it suggests a threshold for every category, chosen together so that all categories at once reach 95% recall with as few candidates as it can find: copy them into `THRESHOLDS` in `cribrum_lab/ranker.py`. It ends with the images labeled delete that no detector flagged. The report lists paths of personal images, so keep it under `lab/data/`.

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
| `cribrum_lab.models` | Downloads model files once into `lab/data/models/`, checked against a SHA-256 |
| `cribrum_lab.assets` | Small model files committed with their license (YuNet face detector) |
| `cribrum_lab.runtime` | `ModelRuntime` interface and `OnnxRuntime` |
| `cribrum_lab.mobileclip` | MobileCLIP-S0 image and text embeddings, zero-shot scores |
| `cribrum_lab.text` | Text coverage from PP-OCRv4's text detector |
| `cribrum_lab.prompts` | Zero-shot classes and prompts; their embeddings are committed in `prompt_embeddings.json` |
| `cribrum_lab.labels` | `LabelStore`: keep/delete labels keyed by content hash |
| `cribrum_lab.labeling` | Sampling and the local labeling web page |
| `cribrum_lab.evaluation` | Recall per category, threshold sweep and missed images |
| `cribrum_lab.ranker` | `Ranker`: per-category thresholds, candidates and the review order |
