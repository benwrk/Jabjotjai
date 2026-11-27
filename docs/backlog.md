# Backlog

Work that is known to be outstanding. **Curated, not accumulated**: delete an entry when it is
done or stops being true (git keeps the history), so every entry here is still true.

An entry says what is missing, why it matters, and what done looks like. An open question goes in
`docs/decisions/` with `status: Open` instead, and work that waits on one links to it.

## Sign every build with one stable key, kept off the build machine as well

Raw files live in the app's private storage, and nothing exports them yet. Android accepts an
update only if "the signing certificate of the update must be the same as the signing
certificate of the installed app, or it must contain a valid proof-of-rotation"; otherwise "a
user must first uninstall the currently installed version, which erases all app data from the
device" (developer.android.com/google/play/app-updates, read 2026-11-27).

Debug builds are signed with the debug keystore Android Studio generates on each machine. A
build from another machine, from a fresh Android Studio install, or a first release build signed
with a new key would therefore force an uninstall and delete every recording on the phone.

Done when:

- one keystore signs both debug and release builds, its location and passwords coming from
  user-level Gradle properties rather than the repository;
- the build fails loudly if they are missing, rather than falling back to the debug key;
- the keystore has a backup that does not live on the build machine;
- a decision records it.

Until then, build and install only from the machine that installed the current build.

## Prove a capture survives 30 minutes on battery with the screen off

`docs/spec.md` 0.1 item 4, staying connected in the background, is the one 0.1 requirement not
yet seen working. Seen so far on the test phone (2026-11-27): 59 minutes with the screen on and
the phone charging, and about 3 minutes locked on battery, both with no gaps; Doze never went
past `INACTIVE`, and it does not start while the phone charges.

Done when a capture has run for at least 30 minutes with the phone unplugged, still, and its
screen off, with battery optimisation left at its default, and the raw file shows no gaps and
the usual ECG and accelerometer rates; record the deepest state `dumpsys deviceidle` reached and
the date. If the capture drops, exempt the app from battery optimisation and run it again: the
difference is what the battery-management walkthrough below has to ask for.

## Walk the user through exempting the app from battery management

`AGENTS.md` ("Capture") requires it: vendor battery management can end a recording silently, so
the app has to guide the user through exempting it, with the settings paths verified on the
actual device. 0.1 has no such step. Done when a first-run checklist exists and each of its
settings paths has been checked on the test phone (Galaxy S25, Android 17, One UI 9.0), with the
date.

## Check the calibration pulse clears the ECG label in portrait

The pulse now stands on the bottom of the band and stops below the label, and in landscape on the
test phone the two no longer touch (seen 2026-11-27). Done when the same is seen in portrait.

## Record a stream that goes silent while the strap is connected

A stream that is started but never delivers leaves nothing in the file but its `stream_started`
event: a gap is only opened for a stream that has delivered before, and only on a disconnection
or a stream error. On 2026-11-27 a capture had no heart rate at all for that reason (the stream's
notifications ended switched off; see `PolarH10Link.startStream`), and only the empty numeric
showed it. Done when a stream that has started, or has delivered, and then sends nothing for a
set time while connected is recorded as such in the raw file, with the time chosen from each
stream's measured frame interval, and a test shows the record being written.

## Let the user hide heart rate from the lock screen

The capture notification shows heart rate on the lock screen, the owner's chosen default
(`docs/spec.md`, 0.1 item 4). Done when a setting switches the notification to showing only that
a capture is running and for how long, with the full content after unlocking.

## Confirm the `SessionStart` hook in a fresh Claude Code session

It can only fire at session start, so it has not been observed working. Run
`git config --unset core.hooksPath`, start a session, and check that `git config core.hooksPath`
prints `.githooks` again (decision `0002`). Done when seen working; if it fails, record what was
observed.

The other mechanism this entry used to track, the path-scoped pointer, was seen working on
2026-11-27: writing a file under `docs/decisions/` brought the pointer from
`.claude/rules/decisions.md` into the session (decision `0001`).
