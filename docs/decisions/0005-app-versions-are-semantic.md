---
number: 0005
title: 'The app is versioned semantically, with versionCode derived from the version'
status: Accepted
component: app
area: tooling
date: 2026-11-27
---

# 0005 — The app is versioned semantically, with versionCode derived from the version

## Context

Android has two version settings. `versionName` is shown to users. `versionCode` is an integer
the system uses "to protect against downgrades by preventing users from installing an APK with a
lower `versionCode` than the version currently installed", and the documentation asks that "each
successive release of your app uses a greater value", up to 2,100,000,000 on Google Play
(developer.android.com/studio/publish/versioning, read 2026-11-27).

## Decision

- `versionName` is a semantic version, `major.minor.patch`. The first is `0.1.0`; a leading 0
  marks the app as not yet stable.
- `versionCode` is derived in `app/build.gradle.kts` as `major × 1,000,000 + minor × 1,000 +
  patch`, so `0.1.0` is `1000`. The build fails if minor or patch reaches 1000.
- The specification names milestones by the version that delivers them (`docs/spec.md`, "0.1").
- The raw file schema is versioned separately (decision `0004`). The header records both.

## Rejected

**A hand-maintained `versionCode`.** Easy to forget, and a lower value blocks the install.

**One numbering for the app and the schema.** The schema changes far less often than the app,
and files outlive app versions.

## Consequences

- Every version bump moves `versionCode` up, as long as versions only move forward.
