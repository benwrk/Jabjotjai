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

## See a failed stream start again on the strap

A stream that fails, or ends, while the strap stays connected is now recorded and started again
on the same connection after a growing pause (`keepStreaming`, tested with a fake stream). That
the H10 streams again after such a restart has not been seen: no stream has failed on the test
phone without a disconnection, and the SDK has switched a stream's notifications off after a
restart before (see `PolarH10Link.startStream`). Done when a raw file shows a `stream_error`
followed, on the same connection, by `stream_started` and that stream's frames, with the date;
if the frames do not come, record what logcat shows. A stream that goes silent without an error
is the entry "Record a stream that goes silent" below.

## Handle a full disk and a failed write

- `RawWriter.write` keeps no failed state. A write that stores part of a line and then fails
  would leave a fragment that the next record (the `stream_error`, then the `end`) is appended
  to, making one malformed line that hides the record explaining the failure. Whether a write
  can store part of a buffer before failing on Android is unverified.
- `CaptureRecovery.find` writes the ending note and reads the file without catching a failure,
  so with the disk full the picker's call would throw, likely on every opening of the app (not
  tried).
- Nothing checks free space before or during a capture.

Done when after a failed write the file still ends at a line boundary or the next write starts
a new line, recovery reports a failure instead of throwing, the app warns before starting with
little space and during a capture when space runs low, and tests cover a write that fails.

## Read earlier files by the byte, and report what cannot be read

`readEarlierTrends` counts a line's characters as its bytes when it maps times to places in an
earlier file. kotlinx-serialization-json 1.9.0 escapes only control characters, the quote and the
backslash, and writes every other character as it is (`StringOps.kt`, `printQuoted`, its sources
jar, read 2026-11-28), so one non-ASCII character in a string field (the phone's model, the
strap's name, an SDK error message) shifts every later place in that file, and the past reader
starts part-way through a line. Every string written on the test phone so far is ASCII. Also,
found by reading and not yet tried:

- `earlierLines` opens the file inside a `sequence`, whose `use` never closes it when reading
  stops early, as every scrub step and every cancelled replay does.
- One earlier file that cannot be read ends the whole past read instead of being skipped, and
  its error stays on screen after going live.
- A missing or unreadable file ends the chain silently; when it is the first, `EarlierTrends.None`
  is published, which reads as "continues nothing" (`AGENTS.md`: a failure never becomes the value
  that means nothing). `aMissingEarlierFileEndsTheChain` pins that.
- `CaptureRecovery.lastLines` decodes with `RawCodec.decode`, not the decoder the header's
  schema names (decision `0006`).

Done when offsets are counted in bytes, a test with a non-ASCII field reads from the right place,
the file is closed however reading stops, an unreadable earlier file is skipped and counted, a
broken chain is shown as a failure, and `lastLines` decodes through `RawDecoders`.

## Say what the stored times measure

- ECG, accelerometer and heart-rate frames are stamped when the capture thread takes them, and
  the same thread runs `fd.sync()` inline from `RawWriter.write`, so their `mono_ns` and
  `wall_ms` include any wait behind a sync rather than being the time the phone received the
  frame (traced 2026-11-28; the size of the wait is not measured).
- In SDK 9.0.0 only the last sample of a frame carries the strap's own time; the others are
  computed from it in `double` and rounded, and on the first frame of a stream from the nominal
  rate (`PmdTimeStampUtils.getTimeStamps`, read from the SDK's bytecode on 2026-11-28, not
  checked again since).

Done when receive times are taken before the sync (on arrival, or with the sync on another
thread), the size of the wait has been measured on the test phone, and `docs/raw-format.md` says
which of `t_ns` is measured, in a note that leaves the 1.0 section as written
(`docs/rules/raw-format.md`).

## Test interrupted captures in CI, without a strap

The stop-and-resume paths of decision `0013` (an update, Force stop, a kill that Android restarts,
swiping the app away) have only been checked by hand, on the test phone with a strap on. Before a
release, that check on a real phone with a real strap is recommended but must not be required,
so the paths that matter need a test that runs on every push.

Done when instrumented tests on an emulator in CI drive a capture fed by a fake strap, interrupt
it each of those ways, and check the raw files (ending note and reason, the resumed file's
`continues`, frames after the resume); the fake strap exists only in the test source set, never
as a switch in a shipped build (`AGENTS.md`); and a decision records the approach.

## Finish proving 0.3 on the test phone

Seen working on 2026-11-27: an update during a capture, with the app off screen, resumed it into a
new file whose header names the old one and wrote the old one's ending note with reason
`PACKAGE_UPDATED`, every time; the offer to resume appeared after a capture was left unfinished;
and swiping the app away from recent apps left the capture running (the same process and file,
129 lines and 15 heart rates in the next 15 s, no `end` record). Force stop during a capture
restarted nothing, the ending note gave `USER_REQUESTED` ("[FORCE STOP]"), and opening the app
offered to resume it as force-stopped; Resume continued it into a new file. After the kill below,
opening the app offered to resume it as stopped by Android, and Resume continued it likewise. Not
yet as specified:

- **A kill that Android restarts did not restart.** The capture's process was killed with
  `adb shell run-as com.benwrk.jabjotjai kill -9 <pid>` (battery optimisation on). The event log
  has `am_proc_died` and no `am_schedule_service_restart`; three and a half minutes later the
  service record was still there with `app=null`, `startRequested=true`, `isForeground=true`, and
  nothing had restarted it. Opening the app then wrote the ending note with reason `SIGNALED`.
  Mechanism unconfirmed: whether a kill from the app's own user, rather than the low-memory
  killer, is why no restart was scheduled, or One UI's battery management.
- **The dead capture's notification stayed up**: the lock screen showed its heart icon with a
  heart rate three and a half minutes after the kill, while nothing was capturing.
- **An update while the app is on screen does not resume by itself.** Seen twice on 2026-11-27:
  Android restarted the app for its on-screen activity before sending the update broadcast, and
  `adb shell dumpsys activity broadcasts history` lists `MY_PACKAGE_REPLACED` to `UpdateReceiver`
  as SKIPPED, reason `mBroadcastConsumerSkip`; the picker then wrote the ending note and offered
  to resume. Mechanism unconfirmed.

Done when each has been seen on the test phone, with the date, and the raw files checked, or the
spec changed to say what happens instead.

## Prove on the phone that 0.6 places an earlier file correctly after a restart

`docs/spec.md` 0.6 item 2 moves an earlier file's times onto the current file's clock when the
phone restarted between them (`clockShiftNs`). It has not been seen on the phone, and the unit
tests would not catch it breaking: every `readEarlierTrends` test uses files whose shift is zero,
so removing the shift from the timeline or from `acceptEarlier` fails nothing; only the
arithmetic of `clockShiftNs` and the shifting in `earlierLines` are tested. Done when a test
reads an earlier file across a restart and fails without the shift, and a capture resumed after
restarting the phone has been seen with the earlier file's trend and timeline in the right place,
with the date.

## Decide whether the capture notification shows icons

Icons for RR, gaps, battery and contact in the notification need a custom layout, and Android
treats a notification as a Live Update only if it has no custom views
(`Notification.hasPromotableCharacteristics`, SDK 37 source, read 2026-11-27). On the test phone
(Galaxy S25, One UI 9) promotion kept the notification open in the shade with Stop showing, where
without it One UI folded it to one line; it drew no status-bar chip and did not keep it first
(seen 2026-11-27). Unknown: what promotion does on other phones, and whether one row of icons fits
the folded custom view.

Done when the folded and open views of a custom layout have been seen on the test phone beside
the current notification, and the owner has chosen one.

## Decide what to call the phone and the strap

The code, comments and docs say "phone" for the device the app runs on and mix "strap" and
"sensor" for the Polar H10. The owner dislikes "phone". Proposed on 2026-11-27, none chosen yet:

- "system" for the phone's clocks and internals, as Android's own `SystemClock` and
  `System.currentTimeMillis` say, and "phone" kept on screen, where people see a phone;
- "device" for the phone and "sensor" for the strap, which reads naturally, but Polar's SDK
  (`deviceId`) and the stored `device_info` events already say "device" for the strap.

Whatever is chosen, raw files keep their field names (`phone`, `sensor`, `device_info`) and an
existing schema's section of `docs/raw-format.md` keeps its wording (`docs/rules/raw-format.md`).

Done when the owner has chosen, one line in `AGENTS.md` defines the terms, and code, comments and
docs use them.

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

## Fit the monitor at the default text size and larger

The test phone runs at a font scale of 0.8 and a display density of 420, so 411 dp across in
portrait (`adb shell settings get system font_scale` and `wm density`, 2026-11-28). On the README
portrait screenshot the top bar's first row takes about 394 dp of the 411, measured from the
image. At a font scale of 1.0 its text alone would need about 60 dp more. Two more things take
room: the beep-failure icon, which adds 40 dp, and the battery-optimisation icon, which shows by
default. In a `Row` the last child measures last, so the Stop button would be squeezed first,
possibly to nothing (not seen on the phone). The one-row layout is used from `WIDE_TOP_BAR =
600.dp`, but by the same estimate it needs about 640 dp at 0.8 and 740 dp at 1.0. The
calibration pulse keeps below the label by `LABEL_SPACE = 20.dp`, but the label is 11 sp text on
a 16 sp line with 2 dp of padding, so from a font scale of about 1.15 the pulse's top passes the
label's bottom.

Done when the monitor has been seen in portrait and landscape on the test phone at the default
and the largest font scale, with the beep failing and battery optimisation on: nothing cut off,
nothing overlapping and Stop always whole. The first row's layout should come from the measured
widths, not a fixed threshold.

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

## Show the third-party licences inside the app

The licences of the software the app includes go with each release as `THIRD_PARTY_NOTICES.md`,
beside the APKs (decision `0015`), but the app itself does not show them. Polar's SDK licence asks
for "adequate copyright markings" on the product, and Apache 2.0 for the licence to go to whoever
receives the binaries. Done when the app has a screen, reachable from the display settings, that
shows the same notices the release carries, generated from the same source so the two cannot
differ.

## Confirm the `SessionStart` hook in a fresh Claude Code session

It can only fire at session start, so it has not been observed working. Run
`git config --unset core.hooksPath`, start a session, and check that `git config core.hooksPath`
prints `.githooks` again (decision `0002`). Done when seen working; if it fails, record what was
observed.

The other mechanism this entry used to track, the path-scoped pointer, was seen working on
2026-11-27: writing a file under `docs/decisions/` brought the pointer from
`.claude/rules/decisions.md` into the session (decision `0001`).

## Keep slower-than-live playback behind live

`docs/spec.md` 0.2 item 2: playing slower than 1× at live falls behind it. Instead, 0.5× at live
returns to live within 200 ms: `Playback.caughtUp` ignores the speed, so a playhead 0.1 s behind
counts as caught up (traced 2026-11-28; so since 0.2). Done when only playback faster than 1×
catches up, and a test sets 0.5× at live and checks it is still in the past a second later.

## Make scrubbing hold up when interrupted

Found by reading `MonitorViewModel`, not yet tried on the phone:

- A drag that starts and never finishes, such as one cut short by rotating the phone mid-drag
  (the activity is recreated and the view model kept), leaves `beforeScrub` and `scrubTarget`
  set. After that, LIVE, the jumps and seeking all return to a paused moment, and playing on
  moves the playhead over a frozen trace, until another drag finishes. Whether Material 3's
  `Slider` skips `onValueChangeFinished` when it leaves the screen is unverified.
- `readMoment` waits for the first line after the target, so a scrub near "now" while the strap
  is disconnected never ends; the scrub targets are collected one after another, so every later
  one waits behind it until the finger lifts.
- A drag from live does not clear the earlier picture the way leaving live does, so a quick
  release shows a moment from an earlier visit until the past reader catches up.

Done when each has a test or has been tried on the phone, and none leaves the monitor stuck or
showing another moment.

## Keep the monitor's indicators current

- The amber battery-optimisation icon is read once and never again
  (`BatteryOptimisationWarning` takes no state), so after the user turns optimisation off in
  Settings and comes back it stays until the screen is rebuilt (read 2026-11-28).
- A tip whose text changes never hides by itself, because its timer restarts on every change
  (`LaunchedEffect(tip)` in `Tip.kt`): the RR tip changes about once a second, within its
  timer. Tapping the icon again closes and at once reopens it (read in Compose UI 1.10.4's popup
  code, not tried).
- `StopQuestion` is process-wide and cleared only when answered, so a question opened from the
  notification and left unanswered can greet the next capture's monitor (not tried).
- The clock's 12- or 24-hour format is remembered, so changing it, or the time zone, shows only
  after the screen is rebuilt.

Done when each follows its source while the monitor is open, tried on the test phone.

## Fix smaller inconsistencies in the monitor

Found by reading, not yet tried on the phone:

- A cascaded or joined row below a row of another signal is stored as `>ECG` without its speed
  (`encodeChartRows`), yet it starts a graph of its own at its own speed, so after a restart it
  comes back at the old default speed, span and motion.
- The gap count shown in the past depends on how the playhead got there: playing from an earlier
  file into the current one adds the two files' counts, while jumping straight into the current
  one shows only its own.
- On opening, the monitor reads back 256 KB (`TAIL_START_BYTES`), about 25 s at the measured
  10 KB/s, less than the 40 s a slow sweep shows.
- The past reader is not restarted when the number of sweeps kept changes, and a scrub reads 10 s
  ahead, less than one sweep at 6.25 mm/s.

Done when each is fixed or shown not to happen.

## Make the app dark from launch, and check the screen's edges on the phone

- The window theme's parent is `android:Theme.Material.Light.NoActionBar` and
  `enableEdgeToEdge()` is called without styles, which follows the phone's light or dark setting
  (activity 1.8.2 `EdgeToEdge.kt`, read 2026-11-28). On a phone in light mode, the launch is white
  and the status-bar icons are dark on the app's black, on the picker and when the hidden bar is
  swiped into view.
- On the README's portrait screenshot the gesture handle crosses the elapsed time below the
  timeline, although the screen pads for the system bars; mechanism unconfirmed.
- The Stop question, the display sheet and the speed menu open windows of their own, and
  Material 3's sheet keeps the system bars visible for its window, so the status bar may come back
  while one is open (not tried).

Done when the app is dark from launch whatever the phone's setting, and the bottom insets and
each of those windows have been checked on the test phone.

## Make the monitor usable with TalkBack and readable in low light

The timeline has no description (TalkBack reads "slider, 100 percent"), Stop and the speed, time
and LIVE controls are not marked as buttons, and the charts have no description. By the WCAG
contrast formula, the red tip text on its grey is 4.05:1, below the 4.5:1 that text of 12 sp
needs; the elapsed time is 3.14:1 and the dash for unknown contact 1.85:1. Done when TalkBack can
reach and name every control on the test phone, and every text and symbol meets 4.5:1 (3:1 for
large text and icons).

## Smaller fixes on the picker and resume screens

- `BluetoothPermissionGate` checks again only after Allow is tapped, so permission granted in
  Settings still shows the gate on return.
- The picker restarts scanning, and empties its list, every time the phone rotates.
- The offer to resume shows a time without a date, formatted with `java.text`, which ignores the
  12- or 24-hour setting, so a capture that stopped yesterday is ambiguous.
- Times and the elapsed time are formatted for the default locale, so a locale with other digits
  leaves the monospaced font; with `supportsRtl` on, a right-to-left locale mirrors the playback
  bar while the waveforms still run left to right.

Done when each is fixed and tried on the test phone.

## Measure the monitor's work and take it off the main thread

Found by reading, not measured:

- The monitor's readers fold every line and copy the whole kept waveform window on the main
  thread, about 350 KB a line for one 40 s sweep by arithmetic; replay at 8× and a cascade of
  several rows multiply that. Only reading and decoding run elsewhere (`flowOn(Dispatchers.IO)`).
  The service's own reader folds on the main thread too.
- The view model starts those readers from `init`, so they keep tailing the file all night while
  the app is in the background and nothing is drawn.
- Each frame, each trace allocates two `Path`s and a list of runs, and each trend row scans its
  whole history, earlier files included, although at 15 to 60 minutes a row moves about a pixel
  every few seconds.
- `PhoneStatus` makes two `BatteryManager` calls every second, also in the background.

Done when an allocation and CPU trace of the monitor on the test phone, live and replaying, has
been recorded before and after; nothing runs for the monitor while it is not on screen; and
folding and copying happen off the main thread, at most once per frame.

## Decide how far back a resumed capture's timeline reaches

`docs/spec.md` 0.6 item 4 says the timeline runs from the start of a resumed capture's first file.
The code follows the chain back only until a file started more than 6 h before the current one
(`TREND_HISTORY_NS`), or 200 files, so a night resumed in its first hour and again after six
loses its start. The bound exists because every resume reads the earlier files again: 60 to
70 MB a second on the test phone, so six hours, about 220 MB, takes 3 to 4 s. Whether a restart
came between two files is guessed from their clocks differing by more than 60 s, so setting the
phone's clock by more than that without a restart would misplace an earlier file. A boot counter
in the header would replace the guess; whether `Settings.Global.BOOT_COUNT` serves is unverified.
None of this is in a decision, and no test pins the 6 h bound.

Done when the owner has chosen, the spec and code agree, a decision records the bound and the
restart test with their rejected options, and tests pin both.

## Record the decision to publish the owner's recordings as test references

`app/src/test/resources/raw-reference/` holds the owner's own ECG, heart rate and motion with
their times, published with the source, with the strap's and phone's identifiers replaced; the
owner chose this on 2026-11-27, and only that folder's README says so. `AGENTS.md` says nothing
personal goes in committed files, with the `LICENSE` copyright line as the one exception
(decision `0014`). Publishing cannot be undone. Done when a decision records what is kept, what
was replaced and the rejected options (synthetic files, keeping them out of the repository), and
`AGENTS.md` names it as a second exception.

## Decide whether the notification shows the time since the start

`docs/spec.md` 0.1 item 4 lists it; 0.3 item 9 and the code leave it out, and a comment in
`CaptureService` ("The elapsed time is part of the text") still describes it. The spec also does
not say that heart rate and RR leave the notification while the screen is off
(`NotificationContent.of`). Done when the owner has chosen, and the spec, the code and its
comments agree.

## Decide whether releases carry the debug APK

Each release carries `jabjotjai-<version>-debug.apk` beside the release build, signed with the
same key and with the same application id, so it installs over the release build. A debuggable
app's private files, the recordings included, can be read over USB with `adb run-as`, which is how
recordings have been copied off the test phone so far. That may be the intended way out until the
app can export (decision `0008` is open on moving recordings), but the release notes do not say
so. Done when the owner has chosen, and the notes say why it is there, or it is gone.

## Decide the notification channel's importance

The channel is `IMPORTANCE_LOW`, which Android documents as showing "potentially in the status
bar (see shouldHideSilentStatusBarIcons())" (`NotificationManager.java`, SDK 37 source, read
2026-11-28), so a user who hides silent notifications' icons does not get the status-bar heart
that `docs/spec.md` 0.1 item 4 promises. A higher importance may make a sound or a heads-up
notification. Done when both have been seen on the test phone with silent icons hidden, and the
owner has chosen.

## Harden what the build downloads

- The Gradle 9.9.2 upgrade dropped `distributionSha256Sum`, so the distribution, which runs with
  the signing secrets in CI, is not checked, and the wrapper jar is still the one from 9.0 or 9.1.
- `settings.gradle.kts` limits JitPack to Polar's group but not the other way, so Google and
  Maven Central are asked for `com.github.polarofficial` first (Gradle 9.9.2 docs, "Filtering
  repository content": content filters "are not exclusive"), and there is no
  `gradle/verification-metadata.xml`.
- Polar's SDK brings in old versions with published advisories (OSV, 2026-11-28): gson 2.8.5,
  okhttp 3.14.9, okio 1.17.2, commons-io 2.11.0, protobuf-javalite 3.20.0. Without the INTERNET
  permission the HTTP stack is inert; protobuf parses what the strap sends.
- Nothing proposes updates to the pinned actions or dependencies.

Done when the wrapper is regenerated with its checksum, JitPack is an exclusive repository,
verification metadata covers every artifact, each advisory is either resolved by a newer version
or recorded as not reachable, and Dependabot or the like watches at least the actions.

## Close smaller gaps in CI and the repository

- `THIRD_PARTY_NOTICES.md` does not list the Material Icons that several drawables come from
  (`ic_settings`, `ic_pause`, `ic_heart`, `ic_volume_on`, `ic_battery_alert`,
  `ic_link_connected`, matched against google/material-design-icons, Apache 2.0); the others and
  the launcher icon, still Android Studio's, are not checked.
- The version is in `app/build.gradle.kts`, which is part of CI's cache key, so every release
  builds from an empty cache, and setup-gradle saves a cache after a failed build too.
- `AGENTS.md` says the hook and CI reject a commit message with a body; both check only the
  subject. The hook also lets any subject starting "Merge " through, and its attribution pattern
  is not anchored, so a subject containing it is deleted.
- `.gitattributes` leaves `app/src/main/assets/` to line-ending conversion, so a Windows build
  ships the licence text with CRLF where CI's has LF; `gradlew`, `*.bat` and `*.jar` have no rule.
- `.gitignore` has no `*.jks` or `*.keystore`; R8's `packageScope` and the foojay plugin are kept
  from the template without a reason given; `signers_of` in `ci.yml` checks output, not exit
  status.

Done when each is fixed or recorded as intended.

## Read raw files through one reader

Four readers handle raw lines differently: `RawTail` (bytes, holds back a torn last line, decodes
by the header's schema), `readEarlierTrends` (characters, picks lines by their opening text),
`earlierLines` (seeks without finding the next line's start) and `CaptureRecovery.lastLines`
(ignores the header's schema). The running file's timeline and state are built from
`MonitorModel` snapshots in `CaptureService`, while an earlier file's are built by separate code
in `readEarlierTrends` that repeats the model's rules for connection, battery and gaps; a change
to one and not the other makes rewinding into an earlier file disagree with the running one. A
viewer, an export or a detector would each add another. Done before the viewer: one reader in
`raw`, with a mode for a finished file and a filter by type; one indexer of time, state and
trends, used for the running file and earlier ones alike; and a test per record type that the
quick filter matches `RawCodec.encode`.

## Let the past reader serve a finished capture

Scrubbing, playing at a speed and crossing from file to file live in `MonitorViewModel`, which
takes its files and start from the running capture (`Capture.live`, `Capture.earlierTrends`),
`Playback` is defined against now and live, and `MonitorScreen` takes `CaptureState.Running` and
reads `Capture` itself. A viewer of finished captures cannot use any of it, and none of it is
unit-tested: the view model reads `SystemClock`, hard-codes `Dispatchers.IO` and reads
process-wide state, and the project has no `kotlinx-coroutines-test`. Its `init` also starts
coroutines that use properties declared after it. Done before the viewer: a plain Kotlin player
over a description of a capture's files (each file, its clock shift, start and timeline, and
whether it is still growing), given its clock and dispatcher, with an end bound for a finished
capture, and tests for scrubbing and crossing files.

## Split CaptureService and narrow Capture

`CaptureService` (514 lines) holds the service's life and wake lock, the header, the live reader
and the earlier-file reader, the notification and the beep, and loads display settings before
`startForeground`. `Capture` carries monitor types (`MonitorSnapshot`, a mutable
`CaptureTimeline`), and the screen writes into it for the service to read (monitor visibility,
replay beats), so `capture` and `monitor` depend on each other. The SDK is created inside the
service with `PolarApi.get`, though `PolarH10Link` already takes the `PolarBleApi` interface, so
a fake strap cannot be supplied. The session-to-file-name mapping is written in four places.
`UpdateReceiver` and `onStartCommand` read files and Android's exit records on the main thread,
and `onDestroy` waits for the capture thread with no time limit. Done when the service composes a
live reader, a notifier and a beep loop, each plain Kotlin and tested; `Capture` holds only the
capture's state and live reading, with no dependency back from `capture` to `monitor`; one class
names the raw directory's files; the SDK is supplied from outside the service (a decision says
how, under `AGENTS.md`'s rule against switches in shipped builds); and that main-thread work
moves off it.

## Choose the time base for derived output before the first detector

The mapping from a record to a time (`timeMonoNs`, and `stampOf` in `CaptureResume`), the shift
between files (`clockShiftNs`) and the mapping from the strap's clock to the phone's
(`ArrivalDelay` in `MonitorModel`) are each written where the monitor needed them. A detector
works on the strap's clock, while the beep and the heart's flash run on the phone's
(decision `0009`), so without one shared mapping a detector-driven beep would need a second one.
Done when one module in `raw` holds these mappings, with tests, and a decision says which clock
derived overlays are stamped in.

## Make the tests able to fail

- No reference recording contains a gap, a disconnection, a `stream_error` or open gaps, so the gap
  path has never been checked against a file the real SDK produced.
- `ReferenceRecordingsTest` checks that every line decodes, not that it encodes back the same, so
  a field dropped from a record class would pass, hidden by `ignoreUnknownKeys`.
- `CaptureRecovery` and `PolarH10Link` have no tests: a missing file, a note reused, `end` found,
  a stale stream, a restart after reconnecting.
- Untested: the order of several earlier files, the 6 h bound and `unreadable` above zero;
  `ArrivalDelay` letting frames older than 30 s go; `SampleSeries` growing after a trim.
- `ExampleUnitTest` asserts that 2 + 2 is 4, and `ExampleInstrumentedTest` is the template's.

Done when a reference recording with a disconnection and a gap is in `raw-reference` (identifiers
replaced), each listed case has a test that fails when the code is broken on purpose, and the
template tests are gone.

## Tidy the comments, docs and leftovers

- Claims about other systems without a source, version and date: the permission dialog stopping
  after two refusals (`PermissionGate`), how H10s advertise (`ScanViewModel`), the strap's epoch
  (`RawRecords.kt`, `docs/raw-format.md`), the strap's clock going back on reconnecting
  (`MonitorModel`), network time corrections being far below 60 s (`EarlierTrends`), the status
  bar's battery warning and its 15 % (`MonitorTopBar`), the H10's highest accelerometer range
  (`PolarH10Link`), the back-gesture observation in `PlaybackBar` without a date, the strap
  sending no time per RR interval (`Trend.kt`), and Doze not starting while charging (this
  file).
- Comments describing what is gone: two orphaned doc comments (`CaptureService` above
  `readEarlierTrendsOf`, `MonitorScreen` above a function that no longer exists), "Landscape puts
  the numerics on the right" and "the two layouts" in `MonitorScreen`, "(0.6)" used as shorthand
  in comments.
- Template leftovers: `Theme.kt`'s purple schemes and dynamic colour, which colour the Stop
  dialog's button and the sheet's chips; `colors.xml`, unused; `backup_rules.xml`, which applies
  only up to Android 11; the unused string `monitor_battery_optimised`.
- Names: `raf`, `prefs`, `dir`, `mid` (`AGENTS.md`: whole words).
- `docs/raw-format.md` does not list the values of `end.reason` (`user_stop`, `error`,
  `service_destroyed`); `ExitReasons.NAMES` maps exit reasons by position rather than by
  Android's constants.
- `docs/spec.md` 0.3's header names one exception where this file lists three, and `README.md`
  says an update resumes the recording by itself without saying it does not while the app is on
  screen.
- `AGENTS.md`'s rules table covers `.claude/`, but its pointer matches only `.claude/rules/`; the
  raw-format pointer lists `main` and `test` where the table says any source set.
- Decisions not recorded: timing the sweep by the least arrival delay, and folding a cascade into
  one sweep, each with the option it replaced.

Done when each is fixed, sourced or removed.
