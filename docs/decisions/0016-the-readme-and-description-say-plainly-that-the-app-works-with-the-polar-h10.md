---
number: 0016
title: 'The README and repository description say plainly that the app works with the Polar H10'
status: Accepted
component: repo
area: process
date: 2026-11-28
---

# 0016 — The README and repository description say plainly that the app works with the Polar H10

Amends: `0015`

## Context

Decision `0015` kept Polar's product names out of the README's headline and pitch and out of the
repository description. Section 4.4 of Polar's SDK licence forbids using Polar's product names "for
its marketing or advertising purposes" without Polar's written consent, and section 9.3 forbids
implying any affiliation. The owner asked for the README and the repository description to say
which strap the app works with.

## Decision

- The README's quick facts say "Works with a Polar H10", its opening paragraph says "It works with
  the Polar H10 chest strap", and its App info lists the strap as a requirement.
- The repository description says the app "works with the Polar H10 chest strap", and that it is
  not affiliated with Polar.
- Polar's name still appears only as a fact: what the app works with, what it requires, and whose
  licence covers the SDK. The bold pitch names no product, and the README's non-affiliation line
  stays.

## Rejected

**Keeping Polar's name out of the description.** A reader could not tell which strap the app needs
without opening the README.

**Stressing that no other sensor works**, with "only" and "no other sensor" repeated across the
page. Saying what the app works with already tells a reader which strap to have.

**Naming the Polar H10 in the pitch.** That is the most promotional place on the page, and the
quick facts and the paragraph below say the same thing.

## Consequences

- Treating a statement of what the app works with as something other than marketing use is a
  reading of section 4.4 that has not had legal review. Polar's written consent would settle it.
