---
number: 0010
title: 'Every build is signed with one stable key, for now the debug key that signed the installed app'
status: Accepted
component: app
area: tooling
date: 2026-11-27
---

# 0010 — Every build is signed with one stable key, for now the debug key that signed the installed app

## Context

Raw files live in the app's private storage, and nothing exports them yet. Android accepts an
update only if "the signing certificate of the update must be the same as the signing
certificate of the installed app, or it must contain a valid proof-of-rotation"; otherwise "a
user must first uninstall the currently installed version, which erases all app data from the
device" (developer.android.com/google/play/app-updates, read 2026-11-27).

Until now debug builds were signed with the debug keystore of whichever machine built them, and
release builds were not signed. The test phone's installed build was signed with the debug key
of the development machine, certificate SHA-256
`325a1e6688323ec147ea2eeb2dcd2c44d900b1e4301d2c996735863fcb5b4799` (read with `keytool` from
the keystore and with `apksigner` from a debug APK built there, 2026-11-27). CI (decision `0011`)
has to produce APKs that install over it.

Android's documentation says that when the debug certificate expires, the fix is to delete
`debug.keystore`, and that it is regenerated on the next build and run in Android Studio
(developer.android.com/studio/publish/app-signing, read 2026-11-27). A command-line build with
an empty Android user home also created a new `debug.keystore` (observed 2026-11-27, AGP 9.5.3).

## Decision

- One signing config, `stable`, signs both debug and release builds (`app/build.gradle.kts`).
- Its store file, store password, key alias and key password come from four Gradle properties:
  `jabjotjaiSigningStoreFile`, `jabjotjaiSigningStorePassword`, `jabjotjaiSigningKeyAlias` and
  `jabjotjaiSigningKeyPassword`. They are set outside the repository: in `gradle.properties` in
  the Gradle user home on a workstation, and as `ORG_GRADLE_PROJECT_<name>` environment
  variables in CI. Gradle reads both (observed 2026-11-27, Gradle 9.9.2).
- If any of them is missing or empty, or the store file is not a file, configuration fails with
  a message naming what to set. Every task fails, Android Studio's sync included.
- For now the stable key is the debug key that signed the installed build, so that build updates
  in place. The store file is a copy of that keystore kept outside the Android user home, so
  Android tooling can never replace it with a newly generated key.
- The owner intends to replace it with a dedicated key later.

## Rejected

**A new key now.** The installed build would not accept it as an update, and uninstalling erases
the recordings on the phone. Moving to a new key without an uninstall needs a proof-of-rotation,
which has not been tried yet.

**Pointing at the Android user home's `debug.keystore` itself.** Android tooling regenerates that
file when it is missing, so losing it would quietly put a new key in its place, and every APK
after that would fail to install over the existing app.

**Falling back to the default debug signing when the properties are missing.** That is the
silent wrong-key build this decision exists to prevent.

**Failing only the tasks that sign.** Tests and lint would then run without the key, which would
help a contributor who does not have it. Nobody builds without the key today, and it takes more
build logic than one check at configuration.

**The properties in the repository's `gradle.properties`.** The repository is public.

## Consequences

- A new machine or a fresh clone cannot build or sync until the four properties are set. The
  error says which are missing.
- Release builds are now signed, so the release APK is `app-release.apk` rather than
  `app-release-unsigned.apk`.
- The debug keystore uses the well-known default passwords, so the key is protected only by
  keeping the file private.
- The keystore has no backup that would survive losing the machine (`docs/backlog.md`).
