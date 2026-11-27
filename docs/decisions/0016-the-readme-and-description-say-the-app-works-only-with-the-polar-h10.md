---
number: 0016
title: 'The README and repository description say the app works only with the Polar H10'
status: Accepted
component: repo
area: process
date: 2026-11-28
---

# 0016 — The README and repository description say the app works only with the Polar H10

Amends: `0015`

## Context

Decision `0015` kept Polar's product names out of the README's headline and pitch and out of the
repository description. Section 4.4 of Polar's SDK licence forbids using Polar's product names "for
its marketing or advertising purposes" without Polar's written consent, and section 9.3 forbids
implying any affiliation. The owner asked for the README and the repository description to make
clear that the app works only with the Polar H10.

## Decision

- The README states it as a requirement. "Polar H10 only" leads its quick facts, and the opening
  paragraph, "Get the app" and the App info requirement each say that no other sensor works.
- The repository description says the app works only with the Polar H10 chest strap, and that it
  is not affiliated with Polar.
- Polar's name still appears only as a fact: what the app requires, and whose licence covers the
  SDK. The bold pitch names no product, and the README's non-affiliation line stays.

## Rejected

**Keeping Polar's name out of the description.** A reader could not tell which sensor the app needs
without opening the README.

**Naming the Polar H10 in the pitch.** That is the most promotional place on the page, and the
requirement says the same thing there without it.

## Consequences

- Treating a statement of what the app requires as something other than marketing use is a reading
  of section 4.4 that has not had legal review. Polar's written consent would settle it.
