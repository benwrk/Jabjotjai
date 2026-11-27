# Backlog

Work that is known to be outstanding. **Curated, not accumulated**: delete an entry when it is
done or stops being true (git keeps the history), so every entry here is still true.

An entry says what is missing, why it matters, and what done looks like. An open question goes in
`docs/decisions/` with `status: Open` instead, and work that waits on one links to it.

## Confirm the agent conventions in a fresh Claude Code session

Two mechanisms can only fire at session start or once per session, so neither has been observed
working:

- **The path-scoped pointer arrives and is followed.** Touch a file under `docs/decisions/` and
  check that the pointer in `.claude/rules/decisions.md` reaches the session and that the agent
  then reads `docs/rules/decisions.md` (decision `0001`).
- **The `SessionStart` hook sets `core.hooksPath`.** Run `git config --unset core.hooksPath`,
  start a session, and check that `git config core.hooksPath` prints `.githooks` again (decision
  `0002`).

Done when both have been seen working; if either fails, record what was observed.
