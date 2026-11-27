# Specification

What the app must do, version by version, named by the app version that delivers it (decision
`0005`). Why each choice was made, and what was rejected, is in `docs/decisions/`; the principles
that hold for every version are in `AGENTS.md`; the raw file format is in `docs/raw-format.md`.

## Everywhere

- **Anyone can use it.** No account, and no sensor is built in: the user finds and picks their
  own.
- **Data stays on the phone** for now. Nothing is uploaded or synced.
- **No battery budget** for now.

## 0.1: live monitor

Built and seen working on the test phone, except one thing: staying connected through 30 minutes
on battery with the screen off (item 4), which is in `docs/backlog.md`. The owner chose to go on to
0.2 before that test.

1. **Connect to a Polar H10** that the user picks from a scan of nearby straps.
2. **Show live, in the style of a bedside patient monitor:**
   - the ECG waveform;
   - heart rate as a large numeric, as the strap reports it, beside a heart that flashes at
     each beat the strap reports;
   - the accelerometer as three raw traces, X, Y and Z, in one band on one shared scale and
     centre, so each axis keeps its true level, with nothing derived from them, requested at
     200 Hz and 8 G.

   The user can change how it is drawn, and the choices are kept between runs:
   - the sweep speed: 6.25, 12.5, 25 or 50 mm/s;
   - the ECG gain, from ×0.25 to ×4 of 10 mm/mV, and the accelerometer scale, from 1.25 to
     40 mm/g, each either fixed or automatic. Automatic is the default: it picks one of the same
     steps from the last few seconds of the trace, ignoring a second or two of outliers, and moves
     the band to keep the trace in its middle. The label always states the step in use, and the
     1 mV calibration pulse follows the gain and the speed, rising from the line where the trace
     draws 0 mV over the left of the trace; it is shown by default and can be hidden;
   - a beep at each beat the strap reports, off by default. It sounds while the monitor is on
     screen and, if the user also turns that on, in the background until the capture stops. It
     follows the media volume, never interrupts other audio and never appears as media playing
     (decision `0009`).

   A stretch of trace beyond the edge of its band is drawn on the edge and marked as clipped, so
   a peak the display cut off cannot pass for the shape of the wave.
3. **The display reads the raw file.** The connection writes everything the strap sends to an
   append-only raw file (schema 1.0, `docs/raw-format.md`), and the monitor draws by tailing that
   file, so what is shown is exactly what is stored (`AGENTS.md`, "Live is a reader tailing the
   raw file"). The waveforms are read from near the end of the file, so a sweep fills at once;
   connection, heart rate, battery, gaps and beats come from one reader that starts at the file's
   first line, shared by the monitor, the notification and the beep, so all three agree whenever
   they start looking. The files are kept. There is no recording feature yet: no session list, no
   export.
4. **The connection stays up when the app is in the background**, in a foreground service, until
   the user stops it. Its notification shows the strap's heart rate, the connection, the gap
   count, the strap's battery and the time since the start, read by tailing the raw file like the
   monitor. It shows all of that on the lock screen, and while the heart rate is current its
   status-bar icon is a heart with the heart rate in it. It asks to be shown as a Live Update,
   with the heart rate as the status-bar chip where the phone draws one.
5. **The layout adapts to orientation.** Landscape is the monitor layout, with waveforms on the
   left and numerics on the right; portrait stacks the numerics above the waveforms. 0.3 replaces
   this layout (0.3, item 8).

## 0.2: rewinding the running capture

Built and seen working on the test phone: the monitor can go back through the capture that is
running, the way a live video stream can be rewound.

1. **A timeline under the monitor** runs from the capture's start to now. Releasing it after a
   drag moves the playhead to that moment; within a second of now counts as live. A LIVE button,
   lit at live, goes back to live.
2. **Play, pause and speed.** Pausing at live holds that moment. Speeds are 0.5×, 1×, 2×, 4× and
   8×; faster than 1× is offered only in the past and becomes live once it catches up, and slower
   than 1× at live falls behind it.
3. **The monitor shows the playhead's moment**: waveforms, heart rate, heart flash, contact,
   battery and gaps, as they were then. The beep follows it up to 2× and is silent faster than
   that or while paused. The status bar and the notification always show the capture as it is now.
4. **The past is read from the raw file**, like the live view: starting a little before the
   playhead, at a point the capture's own reader noted while reading the file from its first line,
   and never further than the playhead has reached, so the screen holds exactly what had been
   received by then.

## 0.3: captures that outlast the app being stopped

Built and seen working on the test phone, except the restart after Android stops the app (item
2): when the app was killed, Android did not restart it, and the offer to resume appeared instead
(`docs/backlog.md`, "Finish proving 0.3 on the test phone"). The owner chose to release 0.3
before that was settled.

When Android stops the app, nothing of it runs, so a capture cannot end itself properly; it is
noted and resumed afterwards instead (decision `0013`).

1. **A capture that has no `end` record is noted afterwards**, in a separate ending note beside
   its file, which is never reopened: why it ended, from Android's record of why the app's
   process died, and the last moment the file received.
2. **It resumes by itself when nobody chose the stop**: after Android stops the app to free
   memory, and after the app is updated. If Android refuses the restart, which battery
   optimisation may cause, the app offers to resume instead, as below. While battery optimisation
   is on, the monitor says so during a capture, so the user can turn it off before an overnight
   recording.
3. **The app offers to resume when someone did**: after Force stop, a stop from the task manager,
   or a crash, when the app is next opened.
4. **A resumed capture is a new file** whose header names the capture it continues, connecting
   to the same strap without a scan. The time between the two files is a gap, never stitched
   across.
5. **Swiping the app away from recent apps does not stop a capture.**
6. **Scrubbing the timeline** (also in 0.3): while the timeline is dragged, the monitor shows the
   moment under the finger, paused; letting go moves the playhead there and plays on, or stays
   paused, as before the drag.
7. **The timeline is laid out like a video player's** (also in 0.3), quiet so the waveforms keep
   the eye: a thin line across the full width over a row of small grey controls. Arrows either
   side of play jump 15 seconds back or forward, playing or paused as before; forward is off at
   live, and a jump to within a second of now goes live. The time counts back from live or forward
   from the capture's start, and a tap switches between the two. A speaker mutes or unmutes the
   beat beep, and a slider beside it sets the beep's own volume, under the media volume, which it
   still follows; dragging it to nothing mutes. A cog opens the display settings. The choices are
   kept between runs.
8. **The monitor fills the screen** (also in 0.3). The system status bar is hidden while it is
   shown, and the waveforms take the full width under one bar that holds, from the left: the
   strap's id and its connection as an icon; the phone's date and time to the second, as the phone
   is set to show them, and its battery, with a bolt while charging; an amber icon while battery
   optimisation is on, and one if the beep cannot play; skin contact as the strap with OK, OFF
   when the strap reports none, or a dash while unknown; the strap's battery, marked with the strap's
   icon, the gap count and the last RR interval; heart rate and the beat heart; and a stop icon,
   which asks before stopping. Every icon says what it is when tapped or held. Numbers are in a
   monospaced font that ships with the app, so the monitor looks the same on every phone, and each
   keeps a fixed width, so nothing moves as values change. On a narrow screen the bar takes two
   rows. How long the capture has run is shown under the timeline's time, aligned to it.
9. **The notification** (also in 0.3): its first line is the heart rate, the last RR interval
   and the connection, its second the gap count, the strap's battery and its skin contact. Its Stop
   opens the app and asks first, as the monitor's stop does.

## 0.4: chart rows

Built and seen working on the test phone: rows of one signal joined into one graph, cascaded
across rows with one cursor, and up to five ECG rows cascaded.

1. **The chart is a column of rows**, set from the cog menu: how many, from one to six, and what
   each shows: ECG, the accelerometer with X, Y and Z together, or one accelerometer axis.
2. **Neighbouring rows of the same signal are joined or cascaded.** Joined rows are one graph as
   tall as the rows together. Cascaded rows are one sweep folded across them, as a bedside
   monitor's cascaded ECG is: the trace runs across the top row and on into the next, with one
   cursor, so two ECG rows show twice as many seconds. A cascade's label and calibration pulse are
   shown once, on its top row.
3. **Each signal has one scale** across all its rows, which its automatic scale fits to its
   shortest graph; a single accelerometer axis is centred on its own level.
4. **The choices are kept between runs.** The default is the layout 0.3 has: one ECG row above one
   accelerometer row.

## 0.5: rows with their own speeds, and heart-rate and RR rows

Built and seen working on the test phone.

1. **Each row is separate, cascaded or joined** with the row above. A separate row is a graph of
   its own, which may show the same signal as the row above at another speed; cascaded and joined
   rows are as in 0.4.
2. **Speed is set per row.** A separate row has its own sweep speed; a cascaded or joined row has
   the speed of the row it continues or joins, since a cascade at two speeds would break its trace.
   A new row starts at the speed of the row above it, and the single sweep speed setting goes. Each
   row's label gives its own speed.
3. **Heart-rate and RR rows** plot the values the strap reported. Their speed is the time across
   the row, set per row: 1, 5, 15, 30 or 60 minutes. Each sweeps by default, like the waveforms,
   or scrolls with the newest value at its right edge, set per row. Their history comes from the
   capture's own reader, which reads the whole file, so going back in time does not need minutes
   of the file read again.
4. **Gain stays one per signal**, so every ECG row is drawn at the same millivolts per millimetre
   and rows can be compared directly.

## 0.6: trends across a resumed capture

The current scope.

1. **Heart-rate and RR rows reach back through a resumed capture.** A capture resumed after Android
   stopped the app, an update or the offer to resume is a new file whose header names the one it
   continues (0.3); its heart-rate and RR rows show those earlier files' values too, oldest first,
   following each header back, with the time between files left as a break and never joined.
2. **Earlier files are placed on the current file's clock.** If the phone restarted between two
   files, its monotonic clock started again, so an earlier file's times are moved by the difference
   between the wall-clock and monotonic times both headers record; otherwise they are used as they
   are.

## Later

Not scheduled. Each becomes a version when it is specified:

- recording sessions, and getting files off the phone;
- a viewer for finished captures;
- detectors, as derived overlays. The monitor's beat beep and heart flash then follow the R-peak
  detector instead of the strap's reported beats (decision `0009`);
- live sharing;
- sync.
