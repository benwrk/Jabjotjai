---
number: 0008
title: 'No cloud backup of app data; whether raw files move to a new phone is open'
status: Open
component: app
area: storage
date: 2026-11-27
---

# 0008 — No cloud backup of app data; whether raw files move to a new phone is open

## Context

The template enabled Android Auto Backup. Per Android's documentation, Auto Backup uploads files in
`getFilesDir()` to the user's Google Drive, and "During Auto Backup, the system shuts down the app
to make sure it is no longer writing to the file system"
(developer.android.com/identity/data/autobackup, read 2026-11-27). Raw files live in
`files/raw/`, the specification keeps data on the phone (`docs/spec.md`), and a capture can run all
night.

The same page says that for apps targeting Android 12 or higher, "On devices from some device
manufacturers, specifying `android:allowBackup="false"` disables cloud-based backup and restore …
but doesn't disable device-to-device transfers for the app."

## Decision

- `android:allowBackup="false"`.
- `data_extraction_rules.xml` also excludes `files/raw/` from `cloud-backup`, for any path by which
  cloud backup could still apply.

## Open

**Should raw files move to a new phone in a device-to-device transfer?** The rules leave
`device-transfer` unconfigured, so on devices where it still runs, the app's files, raw files
included, may be transferred. Moving recordings to a user's own new phone does not upload them,
but it can be hundreds of megabytes per night recorded, and it is the user's data to decide about.
Until this is answered, whatever the device does by default stands.

## Rejected

**Leaving Auto Backup on.** It contradicts "data stays on the phone", and a nightly backup run can
stop the app mid-capture.
