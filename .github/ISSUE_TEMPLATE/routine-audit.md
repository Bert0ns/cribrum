---
name: Routine, deep audit
about: Review one area of the code in depth. The areas rotate.
title: "routine-audit: "
labels: routine-audit
---

This is a routine. Run it from this issue body alone. Read `AGENTS.md` and `docs/design.md` first.

Area (the maintainer may fill this in, otherwise leave it empty):

## Choose the area

The areas, in rotation order, with where they live. Android paths are under `android/app/src/main/kotlin/io/github/bert0ns/cribrum/`, lab paths under `lab/src/cribrum_lab/`. Tests live next to them in `android/app/src/test` and `lab/tests`.

1. `ranker`. `ranker/`, `ranker.py`, and section 6 of the design.
2. `pipeline`. `pipeline/`, `pipeline.py`, and sections 4 and 7.
3. `trash`. `trash/` and section 9. This is the only code that deletes files.
4. `review-ui`. `review/`, `ui/ReviewScreens.kt`, `ui/ReviewViewModel.kt`, `ui/TrashScreen.kt`, and sections 6.3 and 8.
5. `lab-evaluation`. `evaluation.py`, `labels.py`, `labeling.py`, and section 10.

If the area line above is empty, list the closed audits with `gh issue list --label routine-audit --state closed --json number,comments`. Take the area after the one in the newest comment that begins with `Area:`, and start again at 1 after the last. With no previous audit, take area 1. Write the area you chose in a comment before you begin.

## Where to start

1. Run `git fetch origin`. The end commit is `git rev-parse origin/main`. Work on a checkout of it.
2. Find the last closed audit of the same area (its `Area:` line). Its start commit is the `Covered commit:` sha in its comments. Read its findings, so you do not report the same thing twice.
3. If there is none, or `git cat-file -e <sha>` fails, read the whole area. Otherwise read the whole area anyway, but spend the most time on `git diff <start>..<end> -- <area paths>`.

## What to check

Read `.agents/skills/code-review/SKILL.md` and apply its two axes to the area, with the issues that introduced it as the spec. Beyond that, look for

- data loss, in particular a path that can delete a file outside the trash, or purge before 30 days;
- a candidate dropped because of a protection signal, or photo age used as a signal;
- error paths, cancellation, and a stopped scan that does not resume;
- code and tests that disagree, and behavior no test covers;
- anything in the area that contradicts `docs/design.md`.

Read-only. Run the checks in `AGENTS.md` for the side you read, and report a red one as a finding.

## Output

One new issue per finding, with file and line, why it matters, and a suggested fix. Use the `lab` or `android` label if it fits, and `bug` if behavior is wrong. Do not put `routine-audit` on it. Open no PR. Run `unslop` on every issue you write. Nothing found means no issue.

## When you finish

1. Comment on this issue with `Area: <area name>`, then `Covered commit: <the full end sha>` on its own line, then the finding links, or `Nothing found.`
2. Close this issue with `gh issue close <number> --reason completed`.

## Do not

- Change code or docs, merge anything, push to `main`, or close issues other than this one.
- Report style nits that a linter or `routine-hygiene` would catch.
- Put the `routine-audit` label on a finding issue. It marks the runs, and the next run reads it.
