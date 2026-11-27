# Memory index

Durable facts that are not tied to one area of the tree, so a path-scoped rule cannot reach
them. This index is imported and loads every session; the entries themselves are read on demand.

Memory is **curated, not accumulated**, the opposite of `docs/decisions/`, which is append-only.
Delete an entry when it stops being true rather than adding a correction. One line per entry:
`- [Title](file.md) — what it saves you from`.

Anything that binds one area belongs in [`docs/rules/`](../rules/); anything with a rejected
alternative belongs in [`docs/decisions/`](../decisions/).

- [PowerShell pipes can change the bytes they pass on](powershell-pipes-change-bytes.md) — piping text into git added an invisible byte-order mark, and captured output gains `\r\n`; pass text as arguments and check raw bytes
- [In CI, "plugin was not found" can be a failed download](ci-plugin-not-found-can-be-a-network-failure.md) — Gradle prints the same message for a network error as for a missing plugin, and only `--debug` shows which; what was ruled out on 2026-11-27 and what to capture next time
