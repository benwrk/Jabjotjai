# PowerShell pipes can change the bytes they pass on

Observed 2026-11-27 in the Windows PowerShell that Android Studio's agent shell runs:

- **Piping a string into a native command prepended a UTF-8 byte-order mark.** Commit messages
  built with `$msg | git commit-tree …` began with the bytes `EF BB BF`, invisible in
  `git log` but part of every subject. Passing the message as an argument (`-m $msg`) wrote it
  cleanly. Mechanism unconfirmed; PowerShell's `$OutputEncoding` is the likely cause.
- **Capturing native output turns `\n` into `\r\n`.** `git cat-file commit … | Out-String` came back
  with CRLF line endings, so a search for `"`n`n"` found nothing, and a check built on it reported
  plausible-looking nonsense.

So, for anything where exact bytes matter (commit messages, hook input, file content):

- Hand text to git as an argument or a file, never through a PowerShell pipe.
- Check the result from bytes git wrote itself, for example
  `cmd /c "git cat-file commit <sha> > file"` followed by `[IO.File]::ReadAllBytes`.
- Run the check once on a case known to be bad, to see that it can fail.
