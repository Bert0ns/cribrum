---
name: Routine, code hygiene
about: Remove dead code, duplication and needless abstractions from what changed.
title: "routine-hygiene: "
labels: routine-hygiene
---

This is a routine. Run it from this issue body alone. Read `AGENTS.md` first.

## Where to start

1. Run `git fetch origin`. The end commit is `git rev-parse origin/main`. Work on a branch from it.
2. Find the previous run with `gh issue list --label routine-hygiene --state closed --json number,comments`. Its start commit is the sha in the newest comment that begins with `Covered commit:`.
3. If there is no such comment, or `git cat-file -e <sha>` fails, this is a first run and you read all of `lab/src`, `lab/tests` and `android/app/src`.
4. Otherwise read only the files in `git diff --name-only <start>..<end>`, and the lines that changed in them.

## What to check

Read `.agents/skills/deslop/SKILL.md`, `.agents/skills/ponytail/SKILL.md` and `.agents/skills/code-simplification/SKILL.md`, and apply them to the files above. Look for

- dead code, unused parameters and imports, and tests that no longer test anything;
- the same logic written twice, in one side or in both lab and android;
- abstractions, options and wrappers with one user and no reason to grow;
- comments that repeat the code, and defensive checks on trusted paths.

Skip generated files, `fixtures/`, and anything under `lab/data/`.

## Output

- A change that keeps behavior and is small enough to review in one sitting gets one PR, in as few commits as make sense. Run the checks in `AGENTS.md` for each side you touched, plus `python3 lab/scripts/check_parity.py`.
- A cleanup that needs a design change, touches many files, or that you are not sure keeps behavior gets one new issue per finding, with file and line. Use the `lab` or `android` label if it fits. Do not put `routine-hygiene` on it.
- Nothing found means no PR and no issue.

## When you finish

1. Comment on this issue with `Covered commit: <the full end sha>`, then the PR or issue links, or `Nothing found.`
2. Close this issue with `gh issue close <number> --reason completed`.

## Do not

- Merge anything, push to `main`, or close issues other than this one.
- Change behavior, thresholds or detector output. If a cleanup would bump a detector version, open an issue instead.
- Remove protection signals, or add any code that deletes files outside the trash abstraction.
- Edit `.agents/skills/`.
- Put the `routine-hygiene` label on a PR or a finding issue. It marks the runs, and the next run reads it.

A PR follows the commit and PR rules in `AGENTS.md`, passes its checks, and says `Refs #<this issue>` in the body. Run `deslop` on the diff and `unslop` on the PR text before opening it.
