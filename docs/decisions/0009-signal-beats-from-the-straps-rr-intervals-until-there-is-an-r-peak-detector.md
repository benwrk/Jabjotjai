---
number: 0009
title: "Signal beats from the strap's R-R intervals until the app has an R-peak detector"
status: Accepted
component: app
area: ui
date: 2026-11-27
---

# 0009 — Signal beats from the strap's R-R intervals until the app has an R-peak detector

## Context

The monitor beeps and flashes a heart at each beat, as a bedside monitor does. Something has to
say when a beat happened. The strap does not: in an 11-minute capture on the test strap (H10,
firmware 5.0.0, 2026-11-27) it sent a heart-rate record every 929 to 1,075 ms, carrying the R-R
intervals that had ended since the last one, one in 512 records and two in 152.

## Decision

- Beats come from those R-R intervals, using the 1/1024 s values as sent rather than the SDK's
  rounded milliseconds. They are laid out one interval after another on the phone's monotonic
  clock, so the rhythm is the strap's own. A beat reported after its slot has passed moves to its
  report's arrival, and later beats follow from there. The sequence starts again after a
  disconnection, a heart-rate gap or 3 s without a beat.
- The beep is optional and off by default, and sounds only while the monitor is on screen. It
  follows the media volume, but never requests audio focus and has no media session, so it
  neither interrupts other audio nor appears as media playing.
- Nothing about beats is written to the raw file.

## Rejected

**A beat detector on the ECG, for the display only.** It would put the beep on the R wave as
drawn, but it would be a second implementation of a detector the project needs anyway, against
`AGENTS.md` ("One implementation of each detector").

**Building the R-peak detector now.** Detectors are scheduled after 0.1 in `docs/spec.md`, and the
real one has to be validated and written as a version-stamped overlay. The owner chose to keep
it for later.

**Beeping when each record arrives.** One or two beeps a second at once, not the heart's rhythm.

## Consequences

- The beep and the flash are not locked to the drawn R wave. Replaying the same capture through
  these rules, a beep came a median 357 ms after its R wave was drawn (5th to 95th percentile 218
  to 509 ms), steady over the 11 minutes. The lag is how long the strap's reports take to arrive;
  the spread is unexplained so far (mechanism unconfirmed).
- When the R-peak detector exists, the beep and the flash move to it (`docs/spec.md`, "Later").
