---
number: 0004
title: 'Raw data is JSON Lines with a major.minor schema, written through and tailed by the monitor'
status: Accepted
component: app
area: storage
date: 2026-11-27
---

# 0004 — Raw data is JSON Lines with a major.minor schema, written through and tailed by the monitor

## Context

`AGENTS.md` fixes the principles: raw data is append-only and written through, gaps are records,
files are plain and self-describing, timestamps are stored as delivered alongside the phone's
wall and monotonic clocks, and live display is a reader tailing the raw file. Version 0.1
(`docs/spec.md`) draws its monitor from that file, so the format had to exist before the first
screen. The record shape was reviewed and accepted on 2026-11-27. The files are kept, so every
schema ever written must stay readable.

## Decision

The format is defined in `docs/raw-format.md`. In short:

- One file per run, `files/raw/<session>.jsonl` in private storage, never reopened.
- JSON Lines, `type` first, units in field names, every SDK field kept as delivered.
- Each record is one write with no buffer in the app; a sync to storage follows within a second.
- The header carries `schema` as `"major.minor"`. Minor grows for additive changes, major for
  anything an older reader would misread. Readers skip unknown fields and types and report
  malformed lines.
- A disconnect or stream error becomes a `gap` record, written when the stream resumes.

## Rejected

**A file per stream**, such as CSV per channel. Events and gaps would need a home of their own, and
a reader would have to merge several files to see one moment.

**A binary format or a database.** Ruled out by the plain-files principle.

**A single integer schema version.** A reader could not tell an additive change it can ignore
from a breaking one it must refuse.

**Timestamps as strings, so a JavaScript reader keeps them exact.** It makes every file uglier to
spare one kind of reader a requirement. Readers must instead parse integers as 64-bit values.

**A frame's first timestamp plus per-sample offsets.** Smaller, and lossless, but no longer the
timestamps as delivered, and one more decoding step to get wrong.

**Gap detection from sensor timestamps in the writer.** Deciding that a jump is a gap is
analysis; the writer records only what it knows to have gone wrong.

## Consequences

- About 10 KB/s with the accelerometer at 200 Hz: roughly 38 MB an hour, 310 MB a night.
  Compression when a file is closed can come later.
- Every schema version needs a reader for as long as its files exist.
- `docs/raw-format.md` is the contract. A change to a record class without a matching change
  there, and a new schema version where needed, is a defect.
