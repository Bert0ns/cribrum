---
name: ui-council
description: "Run a UI/UX review of the Android app by a council of two models from different providers, working on the rendered UI gallery, then merge their reports into one with scores, issues and the decisions the maintainer has to take. Use when the maintainer asks for a UI/UX review, for the council, or to compare the UI with an earlier council run."
---

# UI/UX council

Two models review the app's UI and UX independently, from the same brief. The orchestrator checks their claims, merges the reports and brings the decisions to the maintainer. The goal is a **simpler and more intuitive** app, not a more detailed one.

The council cannot use the phone, which holds personal photos. It looks at the **UI gallery** instead: PNGs of every screen and key state, rendered from the real composables over drawn pictures (`android/README.md`, UI gallery). The members read the code for what images cannot show, such as gestures, timing and TalkBack.

**Keep the members unbiased.** Nothing they read mentions an earlier run or its scores: not the brief, not the launch message, not the council folder. The comparison is the orchestrator's job.

## Steps

### 1. Render the gallery

```bash
cd android && ANDROID_HOME=~/android-sdk JAVA_HOME=~/.local/jdks/jdk-21.0.12.1+1 \
  flock /tmp/cribrum-gradle.lock ./gradlew -PuiGallery=/tmp/cribrum-ui-gallery-<run> testDebugUnitTest --tests '*UiGallery'
```

`<run>` is the version, such as `2.0.0`. It takes about ten minutes. Look at a sample: Italian phone, dark, font 1.5 and the unfolded Fold. A state with no scene is invisible to the council, so add the missing scene first in its own PR. The notes at the top of `index.md` (`NOTES` in `GalleryScenes.kt`) must match the app.

### 2. Write the flows

Make the council folder, `/tmp/cribrum-ui-council-<run>/`, and write `flows.md` there from the code, or give it to a worker model while the gallery renders. For each main flow, list the screens in order, the taps of each step and what each shows. Cover:
- the first run;
- reviewing a category;
- compressing a batch;
- the trash;
- settings and the storage map;
- a running scan and how to stop it.

Count the taps from the code.

### 3. Write the brief

Copy [`brief.md`](brief.md) into the council folder and fill in its placeholders. The **decisions already taken** come from the "Decisions taken by the maintainer" section of the earlier council issues, plus any later ones in `docs/design.md`. The report file names go in the launch messages.

### 4. Launch the two members

Run them in parallel, each in a worktree thread at the commit under review. Each one gets a short message that names the brief and its report file:

| Member | Report |
|---|---|
| Opus 5.5 medium | `opus.md` |
| Gemini 3.8 Flash high | `gemini.md` |

They come from different providers, because their blind spots differ. When one is out of credits, take a model of the same tier in `AGENTS.md`, from another provider if possible. The council takes two of the three agent slots. If a member ends its turn before its report is complete, nudge it.

### 5. Merge the reports

If there was an earlier run, read its merged report: it is the first comment of the last council issue. Save it in your scratchpad, not in `/tmp`, where the members could find it.

Write `report.md` in the council folder, with these sections:
1. **Scores**: one column per member, plus the earlier run's columns.
2. **What changed**, if there was an earlier run: what is fixed, still open and new.
3. **A. Both found it**, with each proposed change and its impact and effort.
4. **B. and C. One member only**, one section per member.
5. **Discarded**: what is false, undoes a decision already taken, reverses a merged PR the maintainer asked for, or breaks a product principle. Search `git log` and the issues for recent changes.
6. **Decisions for the maintainer**: everything that touches `docs/design.md` or a product principle, as short numbered questions.
7. **Suggested first batch**: small items that both members support or that you checked, and that need no decision.

Check every high impact claim against the code and, for anything visible, against the image. Quote the file and line. Correct or discard what does not hold, and say so.

### 6. Open the council issue

`/tmp` does not survive a restart of the session, so open the issue now, titled `feat(android): simplify the ui after the <run> ui/ux council review`. Its body is the scores and the consensus, in a few lines. Post `report.md` as the first comment, then each member's report in a comment of its own, inside a `<details>` block. One comment holds at most 65,536 characters. Check the reports for machine paths before posting.

### 7. Bring the decisions to the maintainer

Reply with:
- the scores and the earlier ones;
- the consensus, one line per issue;
- the numbered decisions.

Nothing starts before the answers.

### 8. Turn the decisions into work

Rewrite the issue body with three parts: the score summary, a final "Decisions taken by the maintainer" section, and a checklist by area that keeps the report's ids. The body overrides the report.

Give the work to the model the maintainer picks, or by default to a strong one. Split a large issue into a stack, one area per PR. Each PR keeps the gallery working and adds scenes for new states. It updates `docs/design.md` and has its strings in Italian. Reviews follow `AGENTS.md`, and the maintainer tries a debug build before the merge.

### 9. Run it again

After the merge, run the council again from step 1, with the same heuristics, and close the issue with the comparison.

## Rules

- Members only write their report in the council folder: no edits, commits or pushes.
- No phone, adb or `connectedDebugAndroidTest` for anyone in the council.
- Reports name images by file and never copy them.
- The heuristics in `brief.md` stay fixed, so scores compare across runs.
