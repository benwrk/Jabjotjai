---
number: 0001
title: 'Record decisions and agent knowledge in the repository, readable by every tool'
status: Accepted
component: repo
area: process
date: 2026-11-27
---

# 0001 — Record decisions and agent knowledge in the repository, readable by every tool

## Context

Reasoning that was worked out at cost tends to survive only in a chat transcript, a commit
message nobody re-reads, or one agent's private memory directory. The same question then gets
asked again months later and answered differently, and rejected options, having left no trace,
are proposed again.

More than one coding agent may work on this project, and each tool reads its own instruction
files. Knowledge written for one tool is invisible to the others.

## Decision

- Non-obvious decisions get an immutable numbered entry in `docs/decisions/`, with rejected
  options and open questions recorded. How to write one is in
  [`docs/rules/decisions.md`](../rules/decisions.md).
- **Project knowledge lives only in files any tool or person can read**: `AGENTS.md`,
  `docs/rules/`, `docs/memories/`, `docs/decisions/` and `docs/backlog.md`. Facts about one
  machine or operator live in the config that already owns them.
- **Tool-specific files only adapt that knowledge**: they point at it, load it or enforce it, and
  hold none of their own. For Claude Code:
  - `CLAUDE.md` contains only the import `@AGENTS.md`.
  - `.claude/rules/<area>.md` carries `paths:` frontmatter and one line telling the agent to read
    `docs/rules/<area>.md`, so the pointer appears when a matching file is touched. Every other
    agent gets the same instruction from the rules table in `AGENTS.md`.
  - `AGENTS.md` imports the memory index for Claude Code and also tells every agent, in plain
    words, to read it.
- Claude Code's auto memory is off for this project: `autoMemoryEnabled` is `false` in
  `.claude/settings.json`, which per Claude Code's settings schema stops it reading from or
  writing to the auto-memory directory (schema read 2026-11-27).

## Rejected

**A private agent memory store.** It is invisible to every other person and tool, nothing
corrects it, and stale entries keep reading as fact. The few notes written there before this
repository existed were moved into `AGENTS.md` and the store emptied.

**`CLAUDE.md` as a symlink to `AGENTS.md`.** Both give one file. But with `core.symlinks` false
git checks a symlink out as a small plain file containing the link text (git-config
documentation), and `git init` on this project's Windows checkout set `core.symlinks` to false
(observed 2026-11-27). Claude Code's own documentation recommends the import over a symlink on
Windows ("Share one file with other coding tools", code.claude.com/docs/en/memory, read
2026-11-27).

**Rule text in `.claude/rules/`.** Claude Code would inject it in full, but the rule would become
one tool's property, which every other agent could reach only through a link into a
Claude-specific directory.

**An `@import` in the `.claude/rules/` loader.** A loader holding only `paths:` frontmatter and
`@../../docs/rules/decisions.md` was injected on a matching read, but what reached the session
was the import line itself, not the rule (observed 2026-11-27). Claude Code's memory
documentation describes imports in `CLAUDE.md` files and says a relative path resolves from the
importing file; it does not say whether files in `.claude/rules/` expand imports
(code.claude.com/docs/en/memory, read 2026-11-27). Mechanism unconfirmed. A plain pointer works
either way.

**A copy of each rule in both places.** Two copies drift, and the copy a tool actually reads
becomes the one nobody edits.

**A generated decision index.** Keeping one correct needs a generator and a pre-commit hook.
Until the log is large enough for searching to hurt, frontmatter plus a text search does the job
with nothing to drift.

## Consequences

- Decisions survive long gaps and the end of a chat session, and agents get a searchable record
  of prior reasoning instead of re-deriving it.
- The log is searched, never loaded, so it can grow without costing every session context.
- Every agent reads the same text. Claude Code also gets a pointer at the moment it touches a
  matching file, at the cost of one extra read.
- Every decision costs a little writing, which is cheaper than re-litigating it.
