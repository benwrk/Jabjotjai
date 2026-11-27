# Memory index

Durable facts that are not tied to one area of the tree, so a path-scoped rule cannot reach
them. This index is imported and loads every session; the entries themselves are read on demand.

Memory is **curated, not accumulated**, the opposite of `docs/decisions/`, which is append-only.
Delete an entry when it stops being true rather than adding a correction. One line per entry:
`- [Title](file.md) — what it saves you from`.

Anything that binds one area belongs in [`docs/rules/`](../rules/); anything with a rejected
alternative belongs in [`docs/decisions/`](../decisions/).

No entries yet.
