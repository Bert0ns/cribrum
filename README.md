# Cribrum

*Cribrum* is Latin for "sieve" (Italian: *crivello*).

Cribrum finds the images on your device that you can delete without losing memories that matter: blurry shots, duplicates and lower-resolution copies, photos of lecture slides and homework, screenshots, and the media that messaging apps such as WhatsApp scatter across storage.

It **suggests** and you **decide**. Nothing is ever deleted without your confirmation, and deleted images go to a trash first, so you can still restore them.

## Status

Early design phase. Nothing is usable yet.

## Principles

- **Practical over pretty.** A clean, minimal UI whose features actually work.
- **Recall over precision.** Surfacing an image that turns out to be worth keeping is fine. Missing one that could have been deleted is not.
- **Private by design.** All analysis runs on the device. No image ever leaves it.
- **Nothing is lost by accident.** Every deletion is confirmed and reversible for 30 days.

## Repository layout

| Path | Contents |
|---|---|
| [`docs/design.md`](docs/design.md) | Design document: goals, decisions, architecture, roadmap |
| [`lab/`](lab/) | Python lab: prototype and reference implementation of the detectors |
| [`android/`](android/) | Android app (Kotlin + Jetpack Compose), not started yet |

## Roadmap

1. **Lab**: build the detectors in Python, label a sample of real photos, and measure recall per category.
2. **Android MVP**: full storage scan, storage overview, exact duplicates, deletion through the trash.
3. Near-duplicates with best-shot selection, blur detection.
4. Content categories with MobileCLIP: slides, documents, screenshots, forwarded media.
5. Learning from review decisions, periodic review reminders.

See [`docs/design.md`](docs/design.md) for details.
