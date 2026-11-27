---
number: 0003
title: 'Use the Polar BLE SDK 9.0.0, from JitPack restricted to Polar''s group'
status: Accepted
component: app
area: capture
date: 2026-11-27
---

# 0003 — Use the Polar BLE SDK 9.0.0, from JitPack restricted to Polar's group

## Context

Version 1 connects to a Polar H10 and streams ECG, accelerometer and heart rate
(`docs/spec.md`). Polar's official Android SDK is `polarofficial/polar-ble-sdk`. Every fact below
was read from a clone of that repository at tag `9.0.0` on 2026-11-27.

- The two newest releases are `9.0.0` (2026-11-26) and `8.6.0` (2026-11-13). Each also has a
  `-java17` tag.
- Between them, the Android sources differ in 16 files. The public API changes are additive: a
  new optional `wearLogEnabled` field on `LogConfig`, and a new `BleOperationInProgress`
  exception. The rest is internal: the BLE session, the MDS client, and an API-call guard that
  now keys on the device's Bluetooth address (`git diff --stat 8.6.0 9.0.0 -- sources/Android`).
  No Android build file changed, and the repository has no migration guide for 9.0.0.
- The library is published only through JitPack: `maven { url 'https://jitpack.io' }` and
  `com.github.polarofficial:polar-ble-sdk:<version>` (README, lines 95 to 110). Its own
  dependencies all come from Maven Central and Google (`library/build.gradle`, lines 131 to 139).
- The library is compiled for Java 21, with minSdk 26 and compileSdk 35 (`library/build.gradle`,
  lines 43 to 45 and 74 to 75).

## Decision

- Depend on `com.github.polarofficial:polar-ble-sdk:9.0.0`.
- Add JitPack as a repository with `content { includeGroup("com.github.polarofficial") }`, so it
  serves Polar's artifact and nothing else.
- Compile the app for Java 21. Kotlin cannot inline a library's functions into bytecode that
  targets an older JVM than the library does.

Observed 2026-11-27: with these settings, `gradlew assembleDebug testDebugUnitTest` succeeded and
resolved `polar-ble-sdk-9.0.0.aar` from JitPack.

## Rejected

**8.6.0.** Nothing this app uses differs from 9.0.0, so starting on it would only add a migration
later.

**The `-java17` variant.** It exists for toolchains that cannot build Java 21, and this project's
toolchain can.

**JitPack without a content filter.** JitPack builds an artifact from any GitHub repository on
request, so an unfiltered entry lets a mistyped or hijacked coordinate for some other dependency
resolve from an arbitrary repository.

## Consequences

- A JitPack outage blocks a cold build. Gradle's cache covers warm builds.
- The library's manifest adds `REQUEST_COMPANION_RUN_IN_BACKGROUND` and
  `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` to the merged manifest
  (`library/src/main/AndroidManifest.xml`).
