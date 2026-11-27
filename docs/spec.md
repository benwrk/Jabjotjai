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

The current scope. Nothing beyond it is built until it works on a real phone with a real strap.

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
   - a beep at each beat the strap reports, off by default. It sounds only while the monitor is
     on screen, follows the media volume, never interrupts other audio and never appears as media
     playing (decision `0009`).

   A stretch of trace beyond the edge of its band is drawn on the edge and marked as clipped, so
   a peak the display cut off cannot pass for the shape of the wave.
3. **The display reads the raw file.** The connection writes everything the strap sends to an
   append-only raw file (schema 1.0, `docs/raw-format.md`), and the monitor draws by tailing that
   file, so what is shown is exactly what is stored (`AGENTS.md`, "Live is a reader tailing the
   raw file"). The files are kept. There is no recording feature yet: no session list, no export.
4. **The connection stays up when the app is in the background**, in a foreground service, until
   the user stops it. Its notification shows the strap's heart rate, the connection, the gap
   count, the strap's battery and the time since the start, read by tailing the raw file like the
   monitor. It shows all of that on the lock screen, and while the heart rate is current its
   status-bar icon is a heart with the heart rate in it. It asks to be shown as a Live Update,
   with the heart rate as the status-bar chip where the phone draws one.
5. **The layout adapts to orientation.** Landscape is the monitor layout, with waveforms on the
   left and numerics on the right; portrait stacks the numerics above the waveforms.

## Later

Not scheduled. Each becomes a version when it is specified:

- recording sessions, and getting files off the phone;
- a viewer;
- detectors, as derived overlays. The monitor's beat beep and heart flash then follow the R-peak
  detector instead of the strap's reported beats (decision `0009`);
- live sharing;
- sync.
