# Changing the raw format

Applies to: `app/src/*/java/com/benwrk/jabjotjai/raw/**`, `docs/raw-format.md`

The serialised form of the record classes is the raw file format defined in
`docs/raw-format.md`. Raw files are kept and never rewritten, so a change that slips through is
read wrongly by every later reader, and nothing crashes to say so.

**Change the record classes and `docs/raw-format.md` in the same commit.** A change to a record's
serialised form without the matching change to the format document is a defect.

**Every change to the serialised form gets a new schema version**: `RAW_SCHEMA` moves, and the
format document gains a section for the new version. Minor for an additive change, major for
anything an older reader would misread (decision `0004`). An existing version's section is never
edited to change its meaning.

**A reader keeps every major version it has ever read.** Dropping one strands the files written in
it.

**Never migrate a file on disk.** When a new major arrives, the old major's decoder in
`RawDecoders.forSchema` upgrades its records into the new model as they are read, with tests that
feed it real lines of the old schema (decision `0006`).

**`RawCodecTest` pins the exact text of every record type.** Update it on purpose when the schema
changes. If it fails after a change that was not meant to touch the format, the format drifted.
