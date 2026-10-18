# Agent guidelines

Most of the code in this repository is written by AI coding agents. Read this first.

## Ground rules

- Everything in English: code, identifiers, comments, docs, commit messages.
- Read [`docs/design.md`](docs/design.md) before starting. If a change contradicts a decision there, update the design doc in the same change and explain why.
- Never commit image data or anything derived from personal images (embeddings, thumbnails, label files). Keep it under `lab/data/`, which is ignored by git.
- Never write code that deletes files without going through the trash abstraction.

## Architecture rules

- Detectors emit `Signal`s and never decide on their own. Ranking lives in the `Ranker`.
- Recall over precision: no detector or ranker may drop a candidate because of protection signals. Protection signals only change the ordering.
- Photo age must not be used as a deletion signal.
- Keep the Python lab (`lab/`) and the Android app (`android/`) structurally parallel: same component names, same interfaces.

## Layout

- `lab/`: Python 3.12+, `src/` layout, tests under `lab/tests/` (pytest).
- `android/`: Kotlin + Jetpack Compose (not started).
