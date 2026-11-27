# Specification

What the app must do, version by version. Why each choice was made, and what was rejected, is in
`docs/decisions/`; the principles that hold for every version are in `AGENTS.md`.

## Everywhere

- **Anyone can use it.** No account, and no sensor is built in: the user finds and picks their
  own.
- **Data stays on the phone** for now. Nothing is uploaded or synced.
- **No battery budget** for now.

## Version 1: live monitor

The current scope. Nothing beyond it is built until it works on a real phone with a real strap.

1. **Connect to a Polar H10** that the user picks from a scan of nearby straps.
2. **Show live, in the style of a bedside patient monitor:**
   - the ECG waveform;
   - heart rate as a large numeric, as the strap reports it;
   - the accelerometer as three raw traces, X, Y and Z, with nothing derived from them.
3. **The display reads the raw file.** The connection writes everything the strap sends to an
   append-only raw file, and the monitor draws by tailing that file, so what is shown is exactly
   what is stored (`AGENTS.md`, "Live is a reader tailing the raw file"). There is no recording
   feature yet: no session list, no export.
4. **The connection stays up when the app is in the background**, in a foreground service, until
   the user stops it.
5. **The layout adapts to orientation.** Landscape is the monitor layout, with waveforms on the
   left and numerics on the right; portrait stacks the numerics above the waveforms.

### Open for version 1

- The raw file's record shape, and where the file lives on the phone.
- The accelerometer's sample rate and range.
- What happens to version 1's raw files: kept, or deleted when the connection ends.

## Later

Not scheduled. Each becomes a version when it is specified:

- recording sessions, and getting files off the phone;
- a viewer;
- detectors, as derived overlays;
- live sharing;
- sync.
