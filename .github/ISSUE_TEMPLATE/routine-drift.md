---
name: Routine, drift check
about: Compare docs/design.md with the code, and lab with android.
title: "routine-drift: "
labels: routine-drift
---

This is a routine. Run it from this issue body alone. Read `AGENTS.md` and `docs/design.md` first.

## Where to start

1. Run `git fetch origin`. The end commit is `git rev-parse origin/main`. Work on a checkout of it.
2. Find the previous run with `gh issue list --label routine-drift --state closed --json number,comments`. Its start commit is the sha in the newest comment that begins with `Covered commit:`.
3. If there is no such comment, or `git cat-file -e <sha>` fails, this is a first run and you read everything.
4. Otherwise read `git diff <start>..<end>` first. Then read the code on the other side of each changed line, even if it did not change, because drift is a mismatch between two places.

## What to check

- Run `python3 lab/scripts/check_parity.py`. A failure is a finding. It already covers detector ids, versions, thresholds and ranker weights, so do not redo that by hand.
- Compare `lab/` with `android/` where the script does not look. That means component and interface names, evidence keys, signal categories, and the ranking order.
- Compare `docs/design.md` with the code. That means the interfaces in section 7, the detector table in section 5, the ranking rules in section 6, the trash in section 9 and the decisions D1 to D8.
- Find a detector whose code changed in the range but whose `version` did not.
- Find a rule in `AGENTS.md` that code breaks. Recall over precision, photo age as a signal, and deleting files outside the trash abstraction are the ones to look for.

When code and doc disagree, do not guess which one is right. Say so in the finding. Code that contradicts a design decision needs a decision from the maintainer, not a doc edit.

## Output

- A mismatch that is only wording in a doc, or a doc that is plainly behind the code, gets one small PR. Run `unslop` on every sentence you write.
- Anything else gets one new issue per finding, with the two places (file and line) and what differs. Use the `lab` or `android` label if it fits. Do not put `routine-drift` on it.
- Nothing found means no PR and no issue.

## When you finish

1. Comment on this issue with `Covered commit: <the full end sha>`, then one line per finding with its link, or `Nothing found.`
2. Close this issue with `gh issue close <number> --reason completed`.

## Do not

- Merge anything, push to `main`, or close issues other than this one.
- Change code, thresholds or detector versions in a drift PR. Docs only.
- Edit `.agents/skills/`.
- Put the `routine-drift` label on a PR or a finding issue. It marks the runs, and the next run reads it.
- Commit image data or anything derived from personal images.

A PR follows the commit and PR rules in `AGENTS.md`, passes its checks, and says `Refs #<this issue>` in the body.
