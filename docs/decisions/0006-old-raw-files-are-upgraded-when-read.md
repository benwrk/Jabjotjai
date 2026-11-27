---
number: 0006
title: 'Old raw files are upgraded when read, never migrated on disk'
status: Accepted
component: app
area: storage
date: 2026-11-27
---

# 0006 — Old raw files are upgraded when read, never migrated on disk

## Context

Raw files are kept, and the schema will change (decision `0004`). Something has to let a build
that writes a newer schema read the files older builds wrote. `AGENTS.md` makes raw data
append-only: a file already written is never reopened and changed.

## Decision

- **A raw file is never rewritten**, on app update, on open, or for any other reason.
- **A reader reads the header's `schema` first**, without assuming any schema's decoder, and
  picks the decoder for that major (`RawDecoders.forSchema`). A decoder for an older major
  upgrades each record into the current model as it reads it. That is where a migration lives.
- **A major this build does not know is refused** with `UnreadableRawFileException`, never read
  with the wrong decoder. Any minor of a known major is read, skipping what it does not know.

Only schema 1.0 exists, so its decoder is the identity. What is tested now is the dispatch: a
newer minor is read, an unknown major and a file without a header are refused, and the schema is
found without decoding the rest of the header.

## Rejected

**Migrating files on app update.** It rewrites raw data, the one copy there is. A bug in the
migration, or a process killed halfway through it, corrupts recordings that nothing can rebuild.
It also costs time and storage proportional to every file ever recorded, on every major bump.

**Migrating a file when it is opened.** The same rewrite, done lazily.

**Converting to a new file and deleting the original.** The original is the data; a converted
copy is at best a faithful derivative of it.

## Consequences

- Each major ever written keeps a decoder for as long as its files exist.
- A converted copy, if one is ever wanted (for a viewer, say), is a derived output: separate,
  version-stamped, and never a replacement for the original.
