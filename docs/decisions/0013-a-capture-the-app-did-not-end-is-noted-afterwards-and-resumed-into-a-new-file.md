---
number: 0013
title: 'A capture the app did not end is noted afterwards and resumed into a new file'
status: Accepted
component: app
area: capture
date: 2026-11-27
---

# 0013 — A capture the app did not end is noted afterwards and resumed into a new file

## Context

When Android kills the app's process (to free memory, to install an update, on Force stop, from
the task manager, or after a crash), none of the app's code runs, so the capture stops and its
file gets no `end` record. Two captures killed by app installs on the test phone (2026-11-27) kept
every line they had written: the last byte of each was a newline, and no line failed to decode.
They recorded nothing after the kill, and nothing in them says why they ended.

What the platform offers, as read on 2026-11-27:

- It keeps a record of why each of the app's processes died,
  `ActivityManager.getHistoricalProcessExitReasons`, with reasons that include `LOW_MEMORY`,
  `USER_REQUESTED` (Force stop), `USER_STOPPED`, `PACKAGE_UPDATED` and `CRASH`
  (`ApplicationExitInfo`, SDK 37 source).
- A service that returns `START_STICKY` and whose process "is killed while it is started" is
  re-created, and `onStartCommand` is called again "with a null intent" (`Service.START_STICKY`,
  SDK 37 source).
- Receiving `ACTION_MY_PACKAGE_REPLACED`, the broadcast an updated app gets, is listed as an
  exemption that allows starting a foreground service from the background
  (developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). The
  foreground service types Android 15 bars from being started at boot do not include
  `connectedDevice` (developer.android.com/about/versions/15/behavior-changes-15).
- Whether a service re-created after `START_STICKY` may start in the foreground from the
  background is not stated on either page. Turning off battery optimisation for the app is a
  listed exemption.

## Decision

- **The raw file is still never reopened.** How a capture that has no `end` record ended is
  written afterwards, as a separate ending note beside its file: the reason Android recorded and
  when, and the last moment the file received.
- **A resumed capture is a new file**, whose header names the capture it continues (schema 1.2,
  an additive field). The time between the two files is a gap and is never stitched across.
- **It resumes by itself when nobody chose the stop**: after the system killed the app (the
  service returns `START_STICKY`), and after an update (on `ACTION_MY_PACKAGE_REPLACED`). Overnight
  nobody is awake to answer a question, and continuity is the point of the recorder.
- **It offers to resume when someone did**: after Force stop or a stop from the task manager,
  which are the user saying stop, and after a crash, where resuming by itself could repeat the
  crash. The offer is made when the app is next opened.
- **The running capture's strap is remembered** (id, name and the address it was advertising
  from), so a resume can connect without a scan; decision `0007` noted this would be needed.
- **Swiping the app away from recent apps must not stop a capture**, which runs in its own
  service; this is to be confirmed on the test phone.

## Rejected

**Appending the missing `end` record to the old file later.** That reopens and changes a file
already written (`AGENTS.md`, "Raw is append-only").

**Continuing in the same file.** The same reason.

**Asking every time.** A capture killed at 3 a.m. would stay stopped until morning.

**Resuming by itself after Force stop.** Force stop is the user asking for the app to stop.

## Consequences

- A recording can be several files. A viewer joins them by the header link and shows the time
  between them as a gap.
- If the phone refuses a re-created service's start in the foreground, resuming after a memory
  kill needs battery optimisation turned off, which the battery walkthrough in
  `docs/backlog.md` then has to ask for. To be observed on the test phone.
