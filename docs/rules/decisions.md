# Writing decisions and instruction files

Applies to: `docs/decisions/**`, `docs/rules/**`, `docs/memories/**`, `.claude/rules/**`,
`AGENTS.md`, `CLAUDE.md`

## Where a fact goes, and where it must not

**Never a private agent memory store**: an agent memory directory, a per-user notes file,
anything outside the tree that only one tool can see. Nothing ever corrects such a store, so an
entry goes stale silently while still reading as fact, and the next reader cannot even see it to
disagree. Claude Code's auto memory is off in `.claude/settings.json` for this reason.

**Tool-specific files only adapt what is written elsewhere.** `CLAUDE.md`, `.claude/` and any
other tool's configuration may point at, load or enforce what `AGENTS.md` and `docs/` say, but
hold no project knowledge of their own, so every tool and every person reads the same copy. See
decision `0001`.

Which home depends on what the fact is **about**:

- **About the project**: how it behaves, why something is the way it is. Into the repository:
  an always-in-force constraint into `AGENTS.md`, an area-specific one into `docs/rules/`, a
  durable fact into `docs/memories/`, a decision into `docs/decisions/`, outstanding work into
  `docs/backlog.md`. It is then corrected by the same commit that invalidates it, and everyone
  who clones the repo sees it.
- **About one machine or operator**: SDK locations, which device is attached, personal
  preferences. Into the config file that already owns it (`local.properties`, the user-level
  agent settings), **not** into the repository, which would make it state something untrue of
  every other clone.

When a fact has both halves, split it.

Machine **capability** (whether a tool is installed, whether a device is connected) belongs
nowhere: it changes without anyone updating a note, so check it rather than record it.

## Keep `AGENTS.md` short

It is loaded in full at the start of every session, and past some number of instructions a model
follows the surplus ones less reliably, so each instruction added makes every other one less
likely to be obeyed. Where that threshold lies is an estimate, not a measurement: dozens are
fine, hundreds are not.

**Count instructions, not lines.** The unit is the `**Imperative.**` lead. Rationale earns its
place where it changes what someone does; otherwise it goes behind a link, into a decision, or
nowhere.

**A rule that has been broken despite being written down gets enforcement, not stronger
wording**: a hook, a setting or a test. Decision `0002` is the example: the attribution rule is
enforced by settings and a commit-msg hook because the written rule alone did not hold.

| Mechanism                                          | Reduces always-loaded context?           |
| -------------------------------------------------- | ---------------------------------------- |
| A rule in `docs/rules/` with a path-scoped pointer | **yes**: the pointer loads on match only |
| A plain link to a doc                              | **yes**: followed on demand              |
| Deleting                                           | **yes**                                  |
| An `@import`                                       | no: expands at launch                    |
| A `.claude/rules/` file without `paths:`           | no: loads at launch anyway               |

## Adding a rule

1. Write the rule in `docs/rules/<area>.md`, readable by every tool and every person.
2. Add a row to the table in `AGENTS.md`, which is how every agent finds it.
3. For Claude Code, add `.claude/rules/<area>.md`: `paths:` frontmatter naming the files the
   rule governs, then one line telling the agent to read `docs/rules/<area>.md` before going on.
   Claude Code shows that line when a matching file is touched.

**Use a pointer, not an `@import`.** A loader that held only frontmatter and
`@../../docs/rules/decisions.md` was injected on a matching read, but what reached the session
was the import line itself, not the rule (observed 2026-11-27, mechanism unconfirmed; decision
`0001`). A plain instruction to read the file works whether or not imports expand there.

## Write a decision only when it earns one

The decision should be **hard to reverse**, **surprising without context**, or **the result of
a real tradeoff**, preferably more than one. A log full of trivia stops being read.

Record **rejected** options with the reasoning; those are the ones that get re-proposed a year
later. Record **open questions** with `status: Open` rather than silently resolving an ambiguity
in whichever direction is convenient today.

Write it when the decision is made, not at the end of the task.

## Entries are append-only

Never rewrite an entry to change its meaning. To change a decision, write a **new** numbered
entry and add a single pointer line to the old one's header. That pointer is the only edit ever
made to an existing entry's text:

| Relationship             | New entry says | Old entry gains  |
| ------------------------ | -------------- | ---------------- |
| Replaces it entirely     | `Supersedes:`  | `Superseded by:` |
| Changes part of it       | `Amends:`      | `Amended by:`    |
| Fixes a factual error    | `Corrects:`    | `Corrected by:`  |
| Answers an open question | `Resolves:`    | `Resolved by:`   |

The old entry's `status` also changes, to `Superseded` or `Resolved`: that is metadata about
whether the entry is still current, not a change to what it says. Write both pointers every
time: a missing back-pointer makes a superseded entry look current at the place someone reads it.
Superseded entries are kept, not deleted, because "why we changed our mind" is often the most
useful thing in the log.

## Every entry needs frontmatter

```yaml
---
number: 0003
title: 'Short imperative title'
status: Accepted # Accepted | Proposed | Superseded | Open | Resolved
component: app # app repo
area: capture # capture storage ui platform tooling process
date: 2026-11-27
---
```

The filename is `NNNN-kebab-case-title.md`, and `number` matches it. Keep both taxonomies
**coarse**; add a `component` only when a new part of the tree (a viewer, an analysis package)
appears.

There is no index to maintain. Find entries by searching the frontmatter (`status: Open`,
`area: capture`) or the text.

## Never import the decision log

It grows without bound, and an import expands at launch. Constraints that must always be in
force go in `AGENTS.md`; area-specific ones go in `docs/rules/`. The always-loaded set then grows
with the number of areas, not the number of decisions.
