---
name: Routine, dependency update
about: Update the Gradle, uv and skills dependencies in one PR.
title: "routine-dependencies: "
labels: routine-dependencies
---

This is a routine. Run it from this issue body alone. Read `AGENTS.md` first.

## Where to start

1. Run `git fetch origin`. The end commit is `git rev-parse origin/main`. Work on a branch from it.
2. Find the previous run with `gh issue list --label routine-dependencies --state closed --json number,comments`. Read the newest comment that begins with `Covered commit:`. Its issue may list updates that were held back on purpose, so do not redo those without a new reason.
3. Dependencies do not depend on the diff. Check every one each time. The commit only records when the check happened.

## What to check

- Android. Compare each version in `android/gradle/libs.versions.toml` and the Gradle wrapper with the latest release on Google Maven, Maven Central and the Gradle plugin portal.
- Lab. Run `uv lock --upgrade` in `lab/` and read the lock diff. Raise a floor in `lab/pyproject.toml` only when the code needs the newer version.
- Skills. Run `npx skills update -p` from the repo root. This is the one allowed way to change `.agents/skills/` and `skills-lock.json`.

Leave these alone, because changing them changes results and the golden fixtures depend on them. They are the pinned model revisions and SHA-256 values for MobileCLIP2, YuNet and PP-OCRv4, and the ktlint version pinned in CI.

## Output

- If something is outdated, open one PR with every update that passes. Use one commit per ecosystem, with the scope `android`, `lab` or `skills` and the type `build` (`chore` for skills). The body lists each old and new version.
- Run the checks in `AGENTS.md` for each side you touched, plus `python3 lab/scripts/check_parity.py`. A skills update also needs the parity check and no changes outside `.agents/skills/` and `skills-lock.json`.
- A major upgrade, or an update that makes a check fail, stays out of the PR and gets one new issue with the error. Do not put `routine-dependencies` on it.
- Nothing outdated means no PR and no issue.

## When you finish

1. Comment on this issue with `Covered commit: <the full end sha>`, then the PR and issue links, or `Nothing found.`
2. Close this issue with `gh issue close <number> --reason completed`.

## Do not

- Merge anything, push to `main`, or close issues other than this one.
- Edit a skill by hand.
- Loosen a check, a threshold or a golden fixture to make an update pass.
- Put the `routine-dependencies` label on a PR or a finding issue. It marks the runs, and the next run reads it.

A PR follows the commit and PR rules in `AGENTS.md` and says `Refs #<this issue>` in the body. Run `unslop` on the PR text before opening it.
