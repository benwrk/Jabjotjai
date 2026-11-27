---
number: 0018
title: 'The app and the README carry the credit "Made with ♥ in Bangkok by benwrk"'
status: Accepted
component: repo
area: process
date: 2026-11-28
---

# 0018 — The app and the README carry the credit "Made with ♥ in Bangkok by benwrk"

Amends: `0014`

## Context

`AGENTS.md` keeps personal details out of committed files, and decision `0014` made the copyright
line in `LICENSE`, which names the owner, the one exception. The owner asked for the credit "Made
with ♥ in Bangkok by benwrk" in the app and in the documentation. It names the owner's GitHub
handle, which the repository's address already shows, and the city they work in.

## Decision

- The README ends with the line "Made with ♥ in Bangkok by benwrk", centred, the handle linking
  to the owner's GitHub profile.
- The app shows it on the monitor's playback bar, with its name and version, as one line:
  "Jabjotjai 0.7.0 by benwrk · Made with ♥ in Bangkok". It is centred in the space between LIVE
  and the beep volume, at 9 sp in the secondary text colour at 35% strength, and drawn only when
  it fits whole with at least 16 dp clear on each side; otherwise the space stays empty. On the
  test phone it shows in landscape and not in portrait.
- In the app the heart is followed by the text presentation selector, U+FE0E. Without it the test
  phone drew a red emoji that ignored the text's colour and stood out from the bar.
- The credit is the second exception to `AGENTS.md`'s rule against personal details in committed
  files, beside the copyright line. Nothing else personal is added with it.

## Rejected

**The owner's full name**, as the copyright line has it. The owner chose the handle.

**The foot of the display settings**, under the app's version, and **two lines** on the playback
bar, the version above the credit. Both were tried on the test phone; the owner chose one faint
line on the monitor itself, and the display settings show neither.

**The hollow heart, ♡.** The owner chose the solid one.

**A screen of its own**, such as an "About" page. The app has none.
