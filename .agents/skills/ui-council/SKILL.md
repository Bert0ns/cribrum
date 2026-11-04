---
name: ui-council
description: "Run a UI/UX review of the Android app by a council of two models from different providers, working on the rendered UI gallery, then merge their reports into one with scores, issues and the decisions the maintainer has to take. Use when the maintainer asks for a UI/UX review, for the council, or to compare the UI with an earlier council run."
---

# UI/UX council

Two models review the app's UI and UX independently, from the same brief. The orchestrator checks their claims, merges the two reports into one and brings the decisions to the maintainer. The goal is always a **simpler and more intuitive** app, not a more detailed one.

The first run (issue #321, on 1.9.1) scored the app 6 out of 10 from both members, and led to the #322 to #325 stack.

## Why it works this way

The council cannot hold the phone. The maintainer's phone holds personal photos, and there is no emulator. So the council looks at the **UI gallery**: `UiGallery` renders every screen and key state from the real composables and view models, over a library of drawn pictures, to PNG files with an `index.md` (see `android/README.md`, UI gallery). The gallery is synthetic, so the images and the reports hold no personal data.

What the gallery cannot show (gestures, animations, timing, TalkBack) the members read in the code.

## Steps

### 1. Render the gallery

Render the commit under review, usually `origin/main`, into a folder of its own, so a later run can be compared with it:

```bash
cd android && ANDROID_HOME=~/android-sdk JAVA_HOME=~/.local/jdks/jdk-21.0.12.1+1 \
  flock /tmp/cribrum-gradle.lock ./gradlew -PuiGallery=/tmp/cribrum-ui-gallery-<run> testDebugUnitTest --tests '*UiGallery'
```

`<run>` is the date or the version, for example `2.0.0`. A run takes about ten minutes. The folder must be an absolute path, and the run empties it first.

Then look at a sample yourself: a few phone images in Italian, the dark ones, font 1.5 and the unfolded Fold. A member judges only what the gallery shows, so a state that matters and has no scene is invisible to the council. If one is missing, add the scene first (a `Scene` in `GalleryScenes.kt`, its own small PR), then render again.

The end of `index.md` lists what comes out different from the phone, such as no status bar and full width dialogs. Keep that list right when the app changes: the first run's brief had to correct it by hand.

### 2. Write the flows

`flows.md` is not generated. Write it into the gallery folder from the code, or give it to a worker model. For each main flow, list the screens in order, the taps for each step and what each step shows:

- first run: permissions, the first scan, the categories appearing;
- reviewing a category: a page, keeping and trashing, the full size view, Undo;
- compressing a batch: the selection, the level, the summary, the result, Undo;
- the trash: the full size view, restoring, deleting for good;
- settings: thresholds, excluded folders and the storage map, retention, the diagnostics;
- a running scan: where its progress shows, and how to stop it.

Count the taps from the code, not from memory: the members use the counts to judge efficiency.

### 3. Write the brief

Make a council folder, `/tmp/cribrum-ui-council-<run>/`, and copy [`brief.md`](brief.md) into it. Fill in its placeholders:

- the gallery folder, its image count and the commit;
- the **decisions already taken**: copy them from the previous council issue (for the first run, the "Decisions taken by the maintainer" section of #321), and add any later ones from `docs/design.md`.

Do not give the members the previous report or its scores. They anchor on them, and the comparison is the orchestrator's job.

### 4. Launch the two members

The two members run in parallel, each in its own worktree thread at the same commit. Give each one a short message that points to the brief and names its report file.

| Member | Report |
|---|---|
| Opus 5.5 medium | `opus.md` |
| Gemini 3.8 Flash high | `gemini.md` |

The two come from different providers on purpose, because their blind spots differ. When one model is out of credits, replace it with another from a different provider than the other member, and name the report after it.

The members only read and write their report. Keep the three-agents-at-a-time limit in mind: the council takes two slots. A thread can end its turn before its report is written; nudge it, and do not merge until both files are complete.

### 5. Merge the reports

Read both reports in full, then write `report.md` in the council folder:

1. **Scores**: a table with one column per member, and the previous run's scores in a third column if there was one.
2. **A. Both found it**: the consensus issues, each with the proposed change and its impact and effort.
3. **B. and C. One member only**: one section per member.
4. **Discarded**: proposals that undo a decision already taken, reverse a merged PR the maintainer asked for, or break a product principle. The first run discarded two. Search `git log` and the issues before you keep a proposal that changes something recent.
5. **Decisions for the maintainer**: everything that touches a decision in `docs/design.md` or a product principle, written as short numbered questions.
6. **Suggested first batch**: small issues that both members support, or that you checked in the code, and that need no decision.

**Check the claims in the code** before they go in, the high impact ones at least. Quote the file and line you checked. In the first run a member claimed that panning closes the full size view; the code only closes it on a tap. Correct or drop such claims, and say so in the report.

When there is a previous run, add a section on what changed: which earlier issues are fixed, which are still open, and which are new.

### 6. Open the council issue

`/tmp` does not survive a restart of the session: the first run's reports were lost that way. So open the run's issue now, titled `feat(android): simplify the ui after the <run> ui/ux council review`. Its body for now is the scores and the consensus in a few lines. Post `report.md` as its first comment, with the members' two reports after it, each in a `<details>` block. They describe synthetic images and the code, so they can go public, but check them for paths from the machine first.

### 7. Bring the decisions to the maintainer

Reply with:

- the two overall scores, and the previous ones;
- the consensus issues in one line each;
- the decisions as numbered questions.

Wait for the answers. No work starts before the decisions are taken.

### 8. Turn the decisions into work

Edit the council issue's body so it holds:

- the summary of the scores;
- a "Decisions taken by the maintainer" section, which is final;
- a checklist grouped by area, whose items keep the report's ids.

The issue body overrides the report where they differ. A UI change across many screens goes to a strong model (Opus 5.5 high). When the issue is too large for one PR, split it into a stack, one area per PR, each with its own conventional commits. Each PR keeps the gallery working, adds a scene for a new state, updates `docs/design.md` and gets its strings in Italian. Reviews follow [`AGENTS.md`](../../../AGENTS.md#which-model-does-the-work). Before merging, the maintainer tries a debug build on the phone.

### 9. Run it again

Once the work is merged, render a fresh gallery and run the council again from step 1, with the same brief and the same heuristics, so the scores compare. Close the issue with the comparison.

## Rules

- The members never edit, commit or push. They write only in the council folder.
- Nobody in the council uses the phone or adb, or runs `connectedDebugAndroidTest`.
- Reports refer to images by file name and never copy them.
- Keep the heuristics in `brief.md` the same from run to run. A new heuristic breaks the comparison, so add it only with a note in the merged report.
