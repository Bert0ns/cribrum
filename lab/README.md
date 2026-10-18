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
