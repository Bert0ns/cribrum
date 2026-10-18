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

The script copies the camera, pictures, screenshots, downloads and documents folders, plus the WhatsApp and Telegram image folders (in both their old and Android 11+ locations), into `lab/data/device/`. It keeps the device's folder layout, because detectors use paths as evidence, and it keeps modification times. It includes the WhatsApp folders hidden from the gallery by `.nomedia`. Videos, audio and voice notes are left out. Running it again overwrites the copies.

Never commit anything under `lab/data/`.

## Layout

| Module | Contents |
|---|---|
| `cribrum_lab.model` | Core types: `ImageRef`, `Category`, `Signal` |
| `cribrum_lab.sources` | `ImageSource` interface and `FilesystemImageSource`, which finds every image under a folder (videos are ignored) |
| `cribrum_lab.metadata` | `read_metadata`: displayed size, MIME type, camera EXIF and capture time |
| `cribrum_lab.index` | `ImageIndex`: SQLite store for images, metadata, signals per detector version, and review state |
| `cribrum_lab.detectors` | `Detector` interface and its implementations |
