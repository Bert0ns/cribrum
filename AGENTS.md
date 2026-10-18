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

## Skills

Project skills live in [`.agents/skills/`](.agents/skills/) (`.claude/skills/` only holds symlinks to them). Read a skill's `SKILL.md` before the task it covers.

| Skill | Use it for |
|---|---|
| `tdd` | Building detectors and other features test-first |
| `ponytail` | Keeping a change to the smallest thing that works |
| `code-review` | Reviewing a branch against these guidelines and its GitHub issue |
| `code-simplification` | Simplifying working code without changing behavior |
| `deslop` | Removing AI boilerplate from code before committing |
| `unslop` | Removing AI tells from docs, issues and commit messages |
| `grill-me`, `grilling` | Stress-testing a design decision before it goes into `docs/design.md` |

They come from [Bert0ns/agent-skills](https://github.com/Bert0ns/agent-skills), pinned in `skills-lock.json`. Update them with `npx skills update -p`, and never edit them in place.

## Layout

- `lab/`: Python 3.12+, `src/` layout, tests under `lab/tests/` (pytest).
- `android/`: Kotlin + Jetpack Compose (not started).
