# Cribrum lab

Python prototype and reference implementation of Cribrum's detectors. Detectors are built, tuned, and measured here on real photos, then ported to the Android app. See [`../docs/design.md`](../docs/design.md), section 10.

## Setup

```bash
cd lab
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
pytest
```

## Data

Put copies of your images under `lab/data/`, which git ignores. For example, from a connected Android device:

```bash
adb pull /sdcard/DCIM lab/data/device/DCIM
adb pull /sdcard/Pictures lab/data/device/Pictures
adb pull /sdcard/Android/media/com.whatsapp lab/data/device/whatsapp
```

Never commit anything under `lab/data/`.

## Layout

| Module | Contents |
|---|---|
| `cribrum_lab.model` | Core types: `ImageRef`, `Category`, `Signal` |
| `cribrum_lab.detectors` | `Detector` interface and its implementations |
