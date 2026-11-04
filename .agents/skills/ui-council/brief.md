# UI/UX council brief: Cribrum Android app

<!-- The orchestrator fills in everything in angle brackets and deletes this comment. -->

You are one of two independent reviewers of the app's UI and UX. The other is a different model. Do not look for its report, and do not read earlier council reports or the GitHub issues about them. Your own judgment is the point.

## The goal

A **simpler and more intuitive** app, not a more complex or detailed one. Prefer removing, merging, renaming or reordering. A proposal that adds a screen, a setting, a text or a control must justify itself against that goal.

## The app

Cribrum finds the photos a user may want to delete or compress, and lets them review those quickly, all on the device. Read `README.md` and `docs/design.md` §1 to §3, §8 and §9.

These are product principles. Do not propose breaking them. If one hurts the UX, raise it as an open question:
- The app suggests, the user decides, and nothing is deleted automatically.
- Deletion goes only through the app's trash, with its retention and Undo.
- Photo age is never a reason to delete.
- No photo is preselected for compression.

The maintainer has also decided these. Treat them the same way:

<decisions already taken, one per line>

The maintainer uses the app in Italian on a Galaxy Z Fold7, folded and unfolded. Most users have slower, smaller phones. The app is in Italian and English.

## What to look at

1. **The gallery** in `<gallery folder>`: <count> PNGs of the app at commit `<commit>`, rendered from the real composables over drawn pictures. `index.md` describes each image, and its notes at the top list what differs from a phone. Those differences are not app problems. File names follow `screen-state_lang_theme_device.png`. Look at the images, at least every Italian phone image and the variants of the main screens.
2. **The flows** in `<council folder>/flows.md`, with the taps of each step.
3. **The code**: `android/app/src/main/kotlin/io/github/bert0ns/cribrum/MainActivity.kt`, `.../ui/`, and the strings in `res/values/` and `res/values-it/`. Use it for what images cannot show, and cite it for any claim about behavior.

## Your report

Write it in English to `<council folder>/`, under the file name in your launch message. Use plain words and no em dashes. It has these parts:

1. **Overall score**, 1 to 10, with two or three sentences.
2. **Scores per heuristic**, 1 to 10, one or two sentences each, citing gallery files:
   - Simplicity
   - Visibility of system status
   - Match with the user's language and mental model
   - User control and freedom
   - Consistency and standards
   - Error prevention
   - Recognition rather than recall
   - Efficiency
   - Aesthetic and minimalist design
   - Accessibility (touch targets, font scale, contrast, screen reader, dark theme)
   - Adaptivity (phone and unfolded Fold)
3. **Issues**, most important first, up to about 25. For each one:
   - an id (`U1`, `U2`, ...) and a title;
   - where it is: the gallery files, and file:line when it matters;
   - the problem and its cost to the user;
   - the simplest change;
   - its impact and effort, each high, medium or low;
   - whether it touches a principle or a decision above.
4. **Keep**: what works and must survive a redesign.
5. **Open questions** for the maintainer.

## Rules

- Write only your report. Do not edit, commit or push anything.
- Do not use the phone, adb, Gradle or `connectedDebugAndroidTest`.
- Name images by file. Never copy them into the report.
- When done, reply with your overall score and your top five issues, one line each.
