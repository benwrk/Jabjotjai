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
   left and numerics on the right; portrait stacks the numerics above the waveforms.

## 0.2: rewinding the running capture

The current scope: the monitor can go back through the capture that is running, the way a live
video stream can be rewound.

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

## Later

Not scheduled. Each becomes a version when it is specified:

- recording sessions, and getting files off the phone;
- a viewer for finished captures;
- detectors, as derived overlays. The monitor's beat beep and heart flash then follow the R-peak
  detector instead of the strap's reported beats (decision `0009`);
- live sharing;
- sync.
