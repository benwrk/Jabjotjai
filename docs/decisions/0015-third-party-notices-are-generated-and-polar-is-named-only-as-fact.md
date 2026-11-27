---
number: 0015
title: 'Third-party notices are generated from the build, and Polar is named only as a fact'
status: Accepted
component: repo
area: process
date: 2026-11-27
---

# 0015 — Third-party notices are generated from the build, and Polar is named only as a fact

Amended by: `0016`

## Context

The released APKs include third-party software. The release runtime classpath held 112 libraries
when this was written, besides the JetBrains Mono font:

- Most libraries are under the Apache License 2.0. Its section 4 requires a copy of the licence to
  go to recipients of the binaries, along with the attribution notices of any NOTICE file.
  commons-io carries one.
- protobuf-javalite is under the BSD 3-Clause License, which requires its copyright notice and
  conditions in the documentation or other materials that go with a binary distribution.
- reactive-streams is under CC0 1.0.
- JetBrains Mono is under the SIL Open Font License 1.1, as stated in the font file itself.
- The Polar BLE SDK declares no licence in its POM. Its repository's `Polar_SDK_License.txt` (read
  at tag 9.0.0 on 2026-11-27) is the licence. Its summary asks that "the original copyright and
  license notice" go with any copy. Section 3.1 allows distributing the SDK "in object code form …
  together with Developer Software to End-Users solely for purpose of enabling Data transfer
  between POLAR product and Developer Software". Section 4.3 asks for "adequate copyright markings"
  on the product. Section 4.4 forbids using Polar's product names "for its marketing or advertising
  purposes, or indicate any association with Polar … without Polar's specific written consent".
  Section 9.3 forbids implying "any business relationship, partnership or affiliation with Polar".

None of these licences conflicts with the project's own all-rights-reserved licence (decision
`0014`). Until now, though, nothing carried their notices, and the README's headline named the
Polar H10.

## Decision

- `scripts/third-party-notices.sh` writes `THIRD_PARTY_NOTICES.md`:
  - every library in the release runtime classpath, read from Gradle itself, under the licence its
    POM declares;
  - the NOTICE files their archives carry;
  - the font;
  - the full licence texts, kept in `docs/licenses/` as fetched from their sources.
- A licence the script does not recognise stops it with an error, so a new one is added by hand,
  with its text, rather than guessed. BSD text is accepted only for protobuf, whose own copyright
  line the stored text carries.
- CI regenerates the file on every run and fails if the committed copy differs. A tag run attaches
  `THIRD_PARTY_NOTICES.md` and `LICENSE` to the release beside the APKs.
- Polar is named only as a fact: what the app works with, what the app requires, whose licence
  covers the SDK. A statement that the project is not affiliated with Polar Electro Oy, and that
  Polar's names are its trademarks, appears in the README and in the notices. The headline,
  pitch and repository description do not name Polar's products.

## Rejected

**A hand-written notices file.** It would fall out of date with the first dependency change, and
nothing would notice.

**A Gradle plugin that collects licences**, such as Google's oss-licenses plugin or AboutLibraries.
Either is a new build dependency. The first also brings in Google Play services for its screen.
A short script does what is needed now, and an in-app screen is a separate piece of work
(`docs/backlog.md`).

**Keeping "for the Polar H10" in the headline.** It reads as marketing use of a Polar product name,
which section 4.4 reserves for Polar's written consent.

## Consequences

- Adding or upgrading a dependency means running the script and committing its output, or CI
  fails.
- The APK itself does not yet show the notices. They go with each release instead, beside the
  APKs.
- This reading of the licences has not had legal review.
