# UI/UX council brief: Cribrum Android app

<!-- The orchestrator fills in everything in angle brackets and deletes this comment. -->

You are one of two independent members of a UI/UX review council. The other member is a different model. Do not look for its report and do not coordinate. Independent judgments are the point.

## The goal

The maintainer wants a better UI and UX. That means **simpler and more intuitive**, not more complex or more detailed. Prefer removing, merging, renaming or reordering over adding. A proposal that adds a screen, a setting, an explanation or a control has to justify itself against that goal.

## What the app is

Cribrum is an Android app that helps the user find the photos they may want to delete and the photos they may want to compress, and review them quickly. It runs entirely on the device. Read `README.md` and `docs/design.md`: §1 to §3 for the purpose and principles, §8 for the UI, §9 for the trash and compression.

Some principles are product decisions. Do not propose breaking them. If you think one hurts the UX, say so as an open question instead:

- Recall over precision. The app suggests, the user decides, and nothing is deleted automatically.
- Deletion only goes through the app's own trash, with its retention and Undo.
- Photo age is never a reason to delete.
- No photo is preselected for compression.

The maintainer has already decided these, in earlier council runs and since. Treat them like the principles above:

<decisions already taken, one per line>

Users: the maintainer uses the app in Italian on a Samsung Galaxy Z Fold7, folded and unfolded. Most users have slower, smaller phones. Italian and English are the two languages.

## What you can look at

The app cannot be run for you. The real phone holds personal photos, and there is no emulator. Use these:

1. **The gallery** in `<gallery folder>`: <count> PNGs rendered at commit `<commit>` by a Robolectric test from the real composables, over a library of drawn synthetic pictures. `index.md` lists every image with its screen, its state and what the user can do there. File names follow `screen-state_lang_theme_device.png`. Look at the images themselves, not only at the index: at least every phone image in Italian, and the variants (dark, font 1.5, Fold, English) of the main screens.
2. **The flows** in `<gallery folder>/flows.md`: the main user flows as the code implements them, with tap counts.
3. **The code**: `android/app/src/main/kotlin/io/github/bert0ns/cribrum/MainActivity.kt` and `.../ui/`, and the strings in `android/app/src/main/res/values/strings.xml` and `values-it/strings.xml`. Use them for what the images cannot show: gestures, haptics, animations, loading and timing, accessibility semantics.

The notes at the top of `index.md` list what the render shows differently from the phone. Those are not app problems. A dialog, sheet or menu is also captured together with the screen under it.

## What to produce

Write your report in English, in Markdown, to `<council folder>/`, under the file name your launch message gives you. Use plain words, no em dashes and no filler. Structure:

1. **Overall score** from 1 to 10 for the app's UI/UX quality today, with two or three sentences on why.
2. **Scores per heuristic**, 1 to 10 each, with one or two sentences each, citing gallery files:
   - Simplicity: how little the user must read, decide and learn
   - Visibility of system status
   - Match with the user's language and mental model, Italian copy included
   - User control and freedom: undo, back, exits
   - Consistency and standards: Material 3, Android conventions, and within the app
   - Error prevention
   - Recognition rather than recall
   - Efficiency: taps and time for the main flows, from `flows.md`
   - Aesthetic and minimalist design: visual hierarchy, density, noise
   - Accessibility: touch targets, font scale, contrast, screen reader, dark theme
   - Adaptivity: phone and unfolded Fold
3. **Issues**, ordered by their impact on users, the most important first. Up to about 25: quality over count. For each one:
   - an id (`U1`, `U2`, ...) and a short title;
   - where: the screens and gallery files, and the code file:line when it matters;
   - the problem, and what it costs the user;
   - the proposed change, as simple as possible, preferring removal or merging;
   - its impact (high, medium, low) and effort (small, medium, large);
   - whether it touches a product principle or a decision above.
4. **Keep**: what works well and must not be lost in a redesign.
5. **Open questions** for the maintainer: decisions that are theirs, not yours.

A claim about behavior that the images do not show, such as what a gesture does, must cite the code that does it.

## Rules

- Do not edit, commit or push anything in the repository. Write only under `<council folder>`.
- Do not use the phone or adb, and do not run `connectedDebugAndroidTest`. You do not need Gradle.
- Do not copy images into the report. Refer to them by file name.
- When done, reply with your overall score and your top five issues, one line each.
