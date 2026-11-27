---
number: 0012
title: 'Release notes are generated from commit subjects, whose type says who the change is for'
status: Accepted
component: repo
area: process
date: 2026-11-27
---

# 0012 — Release notes are generated from commit subjects, whose type says who the change is for

## Context

The owner asked for a changelog generated with every release. Each version tag already drafts a
GitHub Release (decision `0011`), but its notes said nothing about what changed.

Every commit is pushed straight to master, and the repository has had no pull requests. GitHub's
own generator builds notes from merged pull requests, so for `v0.1.0` it produced nothing but a
"Full Changelog" link (asked through `POST /repos/{owner}/{repo}/releases/generate-notes`,
2026-11-27).

The history already used conventional prefixes, `type(scope): description`, on one-line subjects:
all 35 commits up to `v0.1.0` were in that form. `AGENTS.md` allowed the prefixes but did not
require them.

## Decision

- Every commit subject is `type(scope): description` or `type: description`, with `!` before the
  colon for a change a user has to act on. The types are `feat`, `fix`, `build`, `ci`, `docs`,
  `chore`, `refactor` and `test`. The scope is lowercase letters, digits and hyphens, naming the
  area.
- **The type says who the change is for.** `feat` and `fix` change what the app does. The others
  change only the project, so a fix to CI is `ci:`.
- `scripts/commits.sh` holds the form in one place. It checks a subject, checks every non-merge
  commit in a range, and writes the release notes for a tag.
- The notes cover the commits since the previous `v*` tag, or the whole history for the first
  one. They list `!` changes under "Read first", `feat` under "Added" and `fix` under "Fixed", and
  count the project changes without listing them. A subject not in the form is listed under "Not
  in the usual form", so it is shown rather than lost.
- The tag run puts the notes at the top of the draft release (decision `0011`), where the owner
  can edit them before publishing. Nothing is committed per release.
- `.githooks/commit-msg` rejects a subject not in the form, after stripping attribution lines as
  before (decision `0002`). It lets through git's own merge subjects and the `fixup!`, `squash!`
  and `amend!` subjects that `git rebase --autosquash` consumes.
- CI checks every non-merge commit a push or pull request brings in, since a hook can be skipped
  or not installed.

## Rejected

**GitHub's generated release notes.** They list merged pull requests, and there are none.

**release-please or semantic-release.** Both decide version numbers and automate tagging or
release pull requests. Here the owner sets the version by hand (decision `0005`) and tags it.

**git-cliff.** It does this well, but it is a new dependency for what a short POSIX script does.

**A committed `CHANGELOG.md`.** It would have to be committed before each tag, adding a step to
every release, and git already keeps the history. The owner chose release notes only.

**Checking the form in CI only.** A malformed subject would be caught only after it is pushed,
when it can no longer be reworded without rewriting history.

**A fixed list of scopes.** Areas appear as the app grows. A list would need updating with each
one, for little gain over the type, which is what the notes depend on.

## Consequences

- Whether `feat`, `fix` or another type is right cannot be checked by a pattern. That part stays a
  written rule in `AGENTS.md`. Four pushed commits that change only the project predate it: two
  `fix(ci):`, one `fix(repo):` and one `feat(build):`. They appear under "Fixed" and "Added" in the
  notes for `v0.1.0` unless the draft is edited.
- Merge commits are neither checked nor listed, so the commits inside a pull request must
  themselves be in the form.
- A commit made in GitHub's web editor fails CI unless its subject is in the form.
