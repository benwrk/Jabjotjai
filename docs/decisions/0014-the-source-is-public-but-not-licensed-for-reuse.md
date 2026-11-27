---
number: 0014
title: 'The source is public but not licensed for reuse, and the software comes as is'
status: Accepted
component: repo
area: process
date: 2026-11-27
---

# 0014 — The source is public but not licensed for reuse, and the software comes as is

Amended by: `0018`

## Context

The repository is public on GitHub, and testers install the APKs it releases (decision `0011`).
Until now it had no licence file, so nothing said what anyone may do with the code or the APKs,
or what the software promises. The app records and shows ECG, which readers can easily mistake for
a medical function.

## Decision

`LICENSE`, chosen by the owner:

- **All rights reserved.** The source is published to be read, not licensed: no open-source licence
  applies, and copying, modifying, distributing or building on it needs the copyright holder's
  written permission. The owner may choose an open-source licence later.
- **Testers may use the releases.** Anyone may install and run the APKs on the Releases page on
  their own devices, for their own use. Nothing else is granted.
- **Not a medical device**, not for diagnosis, treatment or any medical decision.
- **As is, without warranty or guarantee**, including of the accuracy, completeness or continuity
  of the data recorded, and with no liability for damages, the loss of recordings included.
- Third-party components keep their own licences.
- **The copyright line names the owner**, Benjapol Worakan, at the owner's choice. It is the one
  exception to `AGENTS.md`'s rule against personal names in committed files, and that rule now
  says so.

The text was written for this project, not taken from a standard licence, and has not had legal
review.

## Rejected

**An open-source licence now.** The owner is not ready to license reuse.

**Granting nothing at all.** Testers installing a release would then be using it without a
licence.

**Naming the holder as "the owner of the repository"** to keep within the no-personal-names rule.
The owner chose their name.

## Consequences

- GitHub cannot match the file to a known licence, so it shows no standard licence badge.
- Any contribution from someone else would need its own agreement, since no licence covers it.
