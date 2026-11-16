# Riserva

*Reserve, as in the fuel reserve lamp.* A fuel gauge for the Galaxy Z Fold's two screens: hours of battery left on the main screen and on the cover, worked out from how this phone is actually used, and a reserve lamp that says when to stay folded. A Cerniera app, built to the Bellezza Necessaria mandate on the shared Kit.

## How it learns

One UI gives a single "time left" and per-app percentages. It doesn't know that an hour unfolded costs far more than an hour on the cover. Riserva learns both rates:

- **Fold Taskbar reads the meter.** Only an always-running process can hear the screen go on and off, and Fold Taskbar already is one. At every change (main, cover, off, charger in or out) and every five minutes while a screen is on, it hands Riserva a reading: the screen, the charge (the counter in µAh, and the percentage), and the current. Switch it off under *Riserva* in Fold Taskbar's settings.
- **The charge lost between two readings belongs to the screen that was on.** Charging is left out. Each screen's rate is the charge lost over the hours seen, from the last seven days, each day counting half as much as one three and a half days newer.
- **Hours left** = charge left ÷ that screen's rate. A rate is shown once it has seen about twenty minutes of use (two hours with the screen off).
- Readings stay on the phone (two weeks of them, in Riserva's own files). Nothing is sent anywhere.

## On screen

- **The gauge:** one tank, two figures. Upright on the cover the tank stands beside them; on any wider shape it lies beneath them, in a pane of its own.
- **Reserve lamp:** lights rosso when the main screen has less than 1, 2 or 3 hours left (2 by default), with Power saving one tap away. A quiet notification goes with it, so Fold Taskbar's Spia shows a champagne lamp.
- **Today:** the charge from midnight, each stretch in the colour of the screen that spent it.
- **What each screen costs:** the rates, and how many cover hours one unfolded hour is worth.
- **Widget:** the gauge at the widget's own size, one picture each way round.

## Get the APK

Every build on main is published as a release with `Riserva.apk`; branch builds are pre-releases. Each build is signed with the same key, so new builds install as updates.

## Still to see on the Fold

See `FOLD_CHECKS.md`.
