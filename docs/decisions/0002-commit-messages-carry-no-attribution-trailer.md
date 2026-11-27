---
number: 0002
title: 'Commit messages carry no attribution trailer, enforced by settings and a commit-msg hook'
status: Accepted
component: repo
area: process
date: 2026-11-27
---

# 0002 — Commit messages carry no attribution trailer, enforced by settings and a commit-msg hook

## Context

No commit in this repository carries a `Co-Authored-By:` line, a `Claude-Session:` line or a
"Generated with Claude Code" line. Coding-agent harnesses add these by default, through their
own session-level attribution guidance. When a written rule and a harness default pull in
opposite directions, whichever the agent followed last is what lands. In another repository
using the same written rule, such trailers still reached the majority of a stretch of commits.

## Decision

Enforce the rule in layers, so that no single layer has to hold:

1. **Settings.** `.claude/settings.json` sets `attribution` to
   `{ "commit": "", "pr": "", "sessionUrl": false }`, and the older `includeCoAuthoredBy` to
   `false`. Per Claude Code's settings schema, an empty string hides that attribution and
   `sessionUrl: false` omits the `Claude-Session` trailer (schema read 2026-11-27).
2. **A commit-msg hook.** `.githooks/commit-msg` deletes every `Co-Authored-By:` line (any
   author, any casing), every `Claude-Session:` line and every "Generated with Claude Code"
   line before the commit is created, whatever tool added them. It **strips rather than
   rejects**, and does not rewrite a message that has no such line. It uses only POSIX `grep`
   options, so it behaves the same under Git for Windows, Linux and macOS.
3. **Installation without a hook manager.** Git's own `core.hooksPath` points at the tracked
   `.githooks/` directory. There is no package-manager install step to set it, so a
   `SessionStart` hook in `.claude/settings.json` runs `git config core.hooksPath .githooks` at
   the start of every Claude Code session, and `AGENTS.md` tells everyone else to run it once
   after cloning. The hook runs `git` in exec form, so it does not depend on which shell Claude
   Code would use on the machine.

## Rejected

**The written rule alone.** That is the arrangement that failed elsewhere: harness guidance
silently overrides a document the agent is told to honour.

**Rejecting the commit instead of stripping the line.** That would block every harness that
appends a trailer by default, turning a cosmetic rule into a workflow obstruction.

**A Gradle task that sets `core.hooksPath`.** It would rewrite git configuration as a side
effect of every Gradle sync and build, CI included, which is surprising behaviour for a build
script.

**A hook manager.** A dependency to install one short shell script.

## Consequences

- An attribution line added by any tool disappears at commit time. An agent that sees its
  trailer missing from the log should not read that as a bug.
- Human co-author trailers are stripped too, consistent with the one-line subject rule in
  `AGENTS.md`.
- A clone where neither a Claude Code session nor the one `git config` command has run has no
  hook. The settings layer still covers Claude Code there.
