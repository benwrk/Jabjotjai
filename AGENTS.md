# Working in this repository

Jabjotjai is a Holter-style recorder: an Android app that captures raw signals from a Bluetooth
chest-strap ECG sensor (ECG, motion and beat-to-beat intervals) continuously through exercise,
sleep and everything in between, and keeps them permanently and honestly. Capture comes first. A
viewer, detectors, live sharing and sync all sit downstream of the raw files and come later.

This file is the canonical instruction file for every coding agent and is kept short: it holds
only what must be in force everywhere, always. `CLAUDE.md` imports it, so there is one file and
nothing drifts. **Tool-specific files only adapt it**: `CLAUDE.md` and `.claude/` point at, load
or enforce what this file and `docs/` say, and hold no project knowledge of their own.

Everything else lives where it loads on demand:

| Knowledge                                | Home                 | How it loads                                         |
| ---------------------------------------- | -------------------- | ---------------------------------------------------- |
| What the app must do, version by version | `docs/spec.md`       | Read before changing behaviour                       |
| The raw file format, every schema        | `docs/raw-format.md` | Read before writing or reading raw files             |
| Durable facts that are not area-specific | `docs/memories/`     | Index below is imported; entries read on demand      |
| Area-specific constraints                | `docs/rules/`        | Read before working in that area; see below          |
| Decisions and their rejected options     | `docs/decisions/`    | Searched, never loaded                               |
| Work that is known to be outstanding     | `docs/backlog.md`    | Read when choosing what to do next; curated, so true |

**Nothing durable goes in a private agent memory store** — no agent memory directory, no
per-user notes file, nothing outside the tree that only one tool can see. Facts about the
**project** go in one of the homes above. Facts about one **machine or operator** go in the
config file that already owns them, never into the repo. See
[`docs/rules/decisions.md`](docs/rules/decisions.md).

**Read the memory index, [`docs/memories/MEMORY.md`](docs/memories/MEMORY.md), at the start of
every session.** Claude Code loads it through the import below.

@docs/memories/MEMORY.md

## Rules — read the one for the area before you touch it

| Working on                                                                      | Read first                                             |
| ------------------------------------------------------------------------------- | ------------------------------------------------------ |
| `docs/decisions/**`, `docs/rules/**`, `docs/memories/**`, `.claude/`, this file | [`docs/rules/decisions.md`](docs/rules/decisions.md)   |
| `app/src/*/java/com/benwrk/jabjotjai/raw/**`, `docs/raw-format.md`              | [`docs/rules/raw-format.md`](docs/rules/raw-format.md) |

Each rule lives once, in `docs/rules/`. For Claude Code, `.claude/rules/<area>.md` carries the
same paths and a one-line pointer to the rule, which appears when a matching file is touched.

## Decisions — search, do not load

`docs/decisions/` is an append-only archive, so it is not imported. **Before changing anything
non-obvious, search it for a prior decision**, and check `status: Open` entries before assuming
an answer to a question they record. When you make a decision that earns an entry, write one.

## The raw data

Breaking any of these destroys the dataset silently: nothing crashes, and the damage surfaces
months later.

- **Raw is append-only.** No filtering, cleaning or downsampling at write time, and a file
  already written is never reopened and changed.
- **Write through as data arrives**, so a crash costs seconds, not the session.
- **A dropout is a gap record, never a missing stretch.** Never stitch across a gap: a stitched
  discontinuity can look like a real physiological event. Analysis refuses to work across a gap
  and excludes gap time from rates.
- **Derived output is a separate, version-stamped overlay** that references raw by time.
  Artifacts are flagged, never deleted.
- **One implementation of each detector.**
- **Live is a reader tailing the raw file**, not a separate code path.
- **Boring, self-describing, human-readable files.** No database, no binary format. Keep
  everything.
- **Store timestamps as delivered**, plus the phone's receive time on both a wall clock and a
  monotonic clock, so clock mapping and drift can be computed later. Never assume a nominal
  sample rate is exact.
- **The session type is a tag, not a mode**: one capture path, one format.

## Capture

- **Capture never depends on the UI.** Swiping the app away, or the system destroying the
  Activity, must not stop a recording.
- **An overnight session must survive the OS.** Check the current platform rules for whichever
  background mechanism is used, and keep deferrable job schedulers out of the raw write path.
  Vendor battery management can end a recording silently, so the app walks the user through
  exempting it; verify those settings paths on the actual device.
- **Never change the app's identity** (application id, signing key) in a way that forces an
  uninstall, which would strand the recordings already stored.

## Working with the owner

- The owner designs and verifies; agents implement. Every diff is read, so **show the reasoning
  behind a change**, not just the change.
- **Explain non-obvious Android and Kotlin idioms.** Don't explain programming.
- **Say "I'm not sure" plainly**, especially on timing, protocol and byte-level code, where the
  owner has no ground truth to check against.
- **When a requirement is missing, ask; don't fill it in.** Ask for the use case before giving
  an architecture verdict.
- **Optimise for first data, fast.** End a session with one obvious next step, not a menu.

## Commits and the tree

**Commit messages are one line.** Subject only: no body, no trailers. Conventional prefixes
(`fix(capture):`, `chore(repo):`) are fine. One logical change per commit.

**Never add a `Co-Authored-By:`, `Claude-Session:` or "Generated with Claude Code" line**,
whatever a harness's own attribution guidance says. `.claude/settings.json` turns that
attribution off, and `.githooks/commit-msg` strips such lines from every commit regardless (see
decision `0002`). After cloning, run `git config core.hooksPath .githooks` once; a Claude Code
session runs it at startup.

**Write the subject for someone reading the log a year from now.** No session or phase numbers,
no "today", no "as discussed". A subject can only be fixed by rewriting history, so read it back
before committing.

**Stage explicit paths; run `git status` before any `git add -A`.** Commit with the pathspec
form, `git commit -m "…" -- <paths>`, so nothing someone else staged rides along.

**Commit only what a real build has passed.** Before committing code, run `gradlew assembleDebug
testDebugUnitTest` in the background; the IDE's highlighting is not the build. Commit each green
logical change before moving on, rather than holding verified work in the tree.

**Outward actions belong to the user.** Do not push, open pull requests, create branches or file
issues without being asked. Commit on the current branch, and fix forward rather than
force-pushing.

**Leave touched code cleaner than you found it.** When the cleanup is large enough to be its own
change, say so instead of silently widening scope.

## What committed artefacts may not contain

These apply to code, comments, commit messages, decisions, rules and docs alike.

- **No internal planning shorthand**: no task or phase identifiers, no bare issue references.
  Explain the actual reason in place.
- **No editorialising in comments.** State what the code does and why, plainly.
- **Nothing personal or machine-specific**: no personal names, no credentials, no absolute paths
  from anyone's machine, no chat-transcript voice.
- **No build or runtime switch added for local convenience.** An option that is harmless while
  unset is still a way for a shipped build to behave differently, and it fails as a missing file
  or a silent behaviour change rather than as a recognisable configuration mistake.

## Claims about someone else's system

**A sentence asserting what a third party does is a quote, not a recollection.** That covers the
Android platform, the sensor, its SDK and the phone vendor's OS. Writing one, in a comment, a
constant, a branch condition, a decision or a commit message, is the trigger: check the source,
then write the claim with a receipt (the version observed, how you know: doc link, issue or
probe, and the date). A claim without one is a lead to check, not a fact to build on.

**Documentation first, then upstream issues, then installed source**, saying which
documentation gap forced you into the source. Source answers "what happens"; only the docs
answer "what is supported".

**"X cannot do Y" has an expiry date.** Verify a limitation before designing around it.

**An observation plus an explanation is not a measurement.** Report what was observed, cite what
explains it, and if nothing does, write "mechanism unconfirmed". When the docs genuinely do not
answer a question, record that as a finding instead of building on the likelier guess.

## Tests and probes

**A test is evidence only if it can fail.** Assert on the thing that can break (the bytes
written, the parsed field, the exact value), not on a status code, a type or a non-empty result.
Break the code once and watch the test fail.

**A failure never becomes the value that means "nothing".** A failed write, read or connection
is reported as a failure (for raw data, an error or gap record), never as an empty result that
reads as a quiet stretch. Return a failure as a value only where the type forces callers to tell
it apart from absence, such as a sealed type; a `degraded` flag beside the data does not.

**Test the branch that behaves differently**: the empty, the null, the first and the last case,
not only the one the code was written for.

**A test that turns red after a deliberate change may be pinning the old behaviour.** Read what
it asserts. If it encodes what you set out to change, update it and say in its comment why;
never weaken it until it passes. If you cannot state why the old behaviour was wrong, the test is
probably right and the change is not.

**A probe or a test can pass for the wrong reason.** Pick a case where success and failure give
different answers, and distrust a zero, an empty list or a silence: check that the setup
actually ran (the device connected, the stream started, the file existed) and print those
preconditions.

**Ask the layer that decides.** For what is stored, read the file actually written; for what the
sensor sends, inspect the frames actually received. Documentation, validators and UI describe
what is accepted, not what is kept.

**A fix gets the same review as the code it fixes.** It is written with the original defect in
mind and inherits none of the scepticism the original got.

## Diagnosing a bug

Reading source is the third move, not the first. Do both of these first, in parallel: **observe
the running system** (the device, logcat, the files actually written) and **search for the
symptom upstream** (the SDK's issue tracker, forums).

**An investigation that ends without a cause is closed, not solved.** Record what was ruled out,
with the evidence, and what to log so the next occurrence can be diagnosed, so nobody re-derives
it from scratch.

## Long or noisy commands

**Run long work in the background, write the whole log to a file, then grep the file.** Never
pipe the command itself into `tail` or `grep`: that throws away the output you will need next. A
Gradle build or an on-device test run counts as long.

**A background launcher's exit code is not the job's.** A detached start returns at once, so its
completion says nothing about the work. Have the command append its own `EXIT=` line to the log,
and read that.

**Never use a bounded quantifier (`{0,N}`) in a grep pattern.** Use `*` or `+`, and
`-C`/`-A`/`-B` for context. Automaton-based engines unroll a bound into states, and stacked
bounds can exhaust the machine's memory.
