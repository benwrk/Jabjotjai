# Backlog

Work that is known to be outstanding. **Curated, not accumulated**: delete an entry when it is
done or stops being true (git keeps the history), so every entry here is still true.

An entry says what is missing, why it matters, and what done looks like. An open question goes in
`docs/decisions/` with `status: Open` instead, and work that waits on one links to it.

## Back up the signing keystore somewhere that survives losing the machine

Every build is signed with one stable key (decision `0010`), and the installed app accepts an
update only from that key, so losing the keystore means the next build forces an uninstall,
which erases every recording on the phone. The keystore lives only on the development machine.
Done when a copy is kept somewhere that survives losing that machine, and a copy restored from it
shows certificate SHA-256 `325a1e6688323ec147ea2eeb2dcd2c44d900b1e4301d2c996735863fcb5b4799` in
`keytool -list`.

## Replace the adopted debug key with a dedicated signing key

The stable key is, for now, the development machine's debug key, with Android's default debug
keystore passwords (decision `0010`). Android accepts an update signed with a different key only
if it carries "a valid proof-of-rotation" (developer.android.com/google/play/app-updates, read
2026-11-27). Done when a dedicated key signs every build, the test phone's installed build has
updated to it without an uninstall (seen on the device, with the date), CI's secrets and expected
certificate digest name the new key, and a decision records it.

## Finish proving 0.3 on the test phone

Seen working on 2026-11-27: an update during a capture resumed it into a new file whose header
names the old one, and wrote the old one's ending note with reason `PACKAGE_UPDATED`, twice; and
the offer to resume appeared after a capture was left unfinished. Not yet seen:

- **A kill that Android restarts** (`START_STICKY`): kill the app's process during a capture,
  for example with `adb shell run-as com.benwrk.jabjotjai kill -9 <pid>`, and see whether the
  service is re-created and allowed to start in the foreground with battery optimisation on,
  which the documentation does not say (decision `0013`).
- **Force stop**: the capture must not resume by itself, and the offer must appear on next open.
- **Swiping the app away** from recent apps must not stop a capture.
- **The first update test resumed nothing.** The app was on screen, Android reopened it, and its
  picker wrote the note and showed the offer, but the update receiver left no sign it ran. That
  build did not log from the receiver; the receiver has logged since, and both later updates
  resumed. Mechanism unconfirmed: repeat an update with the monitor on screen and read
  `UpdateReceiver` in logcat.

Done when each has been seen on the test phone, with the date, and the raw files checked.

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
