# Agent guidelines

Most of the code in this repository is written by AI coding agents. Read this first.

## Ground rules

- Everything in English: code, identifiers, comments, docs, commit messages. The one exception is `android/app/src/main/res/values-it/strings.xml`, which is Italian.
- Read [`docs/design.md`](docs/design.md) before starting. If a change contradicts a decision there, update the design doc in the same change and explain why.
- Never commit image data or anything derived from personal images (embeddings, thumbnails, label files). Keep it under `lab/data/`, which is ignored by git.
- Never write code that deletes files without going through the trash abstraction.
- Every new user-facing string in `android/` needs its Italian translation in `values-it/strings.xml` in the same change, or `lintDebug` fails. Code, docs, commits and issues stay in English.

## Architecture rules

- Detectors emit `Signal`s and never decide on their own. Ranking lives in the `Ranker`.
- Recall over precision: no detector or ranker may drop a candidate because of protection signals. Protection signals only change the ordering.
- Photo age must not be used as a deletion signal.
- Keep the Python lab (`lab/`) and the Android app (`android/`) structurally parallel: same component names, same interfaces.

## Checks

The deterministic checks must pass before a PR is ready, and a red check means it is not. Run them locally first:

- `lab/`: `uv run ruff check && uv run ruff format --check && uv run pytest`
- `android/`: `./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`, and `./gradlew ktlintFormat` fixes the formatting
- `python3 lab/scripts/check_parity.py` checks that lab and android agree on detector ids, versions and thresholds. When you change one side, change the other, and bump the detector version if its output changes.

CI runs `lab`, `android`, `parity` and `commit-format` on every PR, and branch protection on `main` requires them to pass.

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

## Routines

Routines that keep the codebase healthy are issue templates in [`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE/). Nothing runs on its own. The maintainer opens an issue from a template and says "prendi in mano l'issue numero X", and the issue body is the whole prompt. Each routine starts from the commit its previous run recorded in a `Covered commit:` comment on the last closed issue with the same label, and records its own when it finishes. No routine merges anything.

| Template | Covers | Suggested model |
|---|---|---|
| `routine-drift` | `docs/design.md` against the code, and lab against android | Gemini 3.8 high or Sonnet 5.5 high |
| `routine-hygiene` | Dead code, duplication and needless abstractions in what changed | Sonnet 5.5 high |
| `routine-dependencies` | Gradle, uv and skills updates, in one PR | Sonnet 5.5 high |
| `routine-audit` | A deep review of one area, chosen in rotation | Opus 5.5 xhigh or Gemini 4 Argon |

The maintainer picks the model when opening the issue. Per-PR reviews stay with the `code-review` skill, run by a different model or subscription from the one that wrote the PR. Opus 5.5 high reviews PRs that change `docs/design.md` or the ranker.

## Commits and pull requests

Every commit subject and every PR title uses `type(scope): subject`. The `commit-format` CI job checks them.

- Types: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `build`, `ci`, `chore`. Add `!` before the colon for a breaking change.
- Scopes: `android`, `lab`, `site`, `skills`. Leave the scope out when a change spans the repo.
- Subject: imperative, lowercase after the colon, no trailing period, at most 72 characters for the whole line. Example: `feat(android): add the DOCUMENT detector`.
- The body follows `unslop` (plain words, no em dashes) and explains why. `unslop` asks for sentence case in headings, but the lowercase subject wins for commit subjects and PR titles.
- The check skips `Merge pull request #N from ...`, `Merge branch ...` and `Merge remote-tracking branch ...` commits, and the `Revert "..."` commits and PR titles that GitHub's Revert button writes.

The person merging picks the merge method by size:

- Small change: squash merge. The PR title becomes the commit.
- Medium change, with commits that each make sense alone: rebase merge.
- Large change: merge commit.

Rebase and merge keep every commit, so every commit in a PR must follow the format, not only the title. Fix review findings with a new conventional commit (`fix(android): ...`), or squash the fix into the commit it fixes before the PR is merged.

## Layout

- `lab/`: Python 3.12+, `src/` layout, tests under `lab/tests/` (pytest).
- `android/`: Kotlin + Jetpack Compose (not started).
