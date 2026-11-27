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

## Confirm the agent conventions in a fresh Claude Code session

Two mechanisms can only fire at session start or once per session, so neither has been observed
working:

- **The path-scoped pointer arrives and is followed.** Touch a file under `docs/decisions/` and
  check that the pointer in `.claude/rules/decisions.md` reaches the session and that the agent
  then reads `docs/rules/decisions.md` (decision `0001`).
- **The `SessionStart` hook sets `core.hooksPath`.** Run `git config --unset core.hooksPath`,
  start a session, and check that `git config core.hooksPath` prints `.githooks` again (decision
  `0002`).

Done when both have been seen working; if either fails, record what was observed.
