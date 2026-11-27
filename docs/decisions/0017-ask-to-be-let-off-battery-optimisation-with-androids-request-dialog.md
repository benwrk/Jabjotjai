---
number: 0017
title: 'Ask to be let off battery optimisation with Android''s request dialog'
status: Accepted
component: app
area: platform
date: 2026-11-28
---

# 0017 — Ask to be let off battery optimisation with Android's request dialog

## Context

While battery optimisation is on for the app, Android may stop a long capture, or refuse the
restart that resumes one it stopped (decision `0013`). The monitor shows an amber battery icon while
it is on, and until now its tip told the user to find the app's battery setting themselves. The
owner asked for the icon to take the user to where it is turned off.

Android has two ways in (`android.provider.Settings`, SDK 37 source, read 2026-11-28):

- `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, a dialog asking the user to let this one app ignore
  battery optimisation. An app must hold the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission to
  use it, and the documentation says "most applications should *not* use this … This is only for
  unusual applications that need to deeply control their own execution".
- `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, the screen listing every app's battery
  optimisation, which needs no permission.

## Decision

- Tapping the amber icon opens the request dialog; a long press still shows why it matters. The app
  declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and the README's permission table lists it.
- Where a phone has no activity for the dialog, the list of apps opens instead.
- The icon is looked at again whenever the monitor comes back on screen, so it goes once the user
  has allowed it.

An overnight recorder is the unusual application the documentation describes: a capture that
Android stops ends the night's recording.

Lint's `BatteryLife` check warns that using the permission "violates the Play Store Content Policy
regarding acceptable use cases" (AGP 9.5.3 lint, 2026-11-28). The app is released through its
GitHub releases (decision `0011`), not Google Play; publishing it on Play would mean checking that
policy's acceptable uses first.

## Rejected

**The list of apps only.** No permission is needed, but the user has to find the app in a long list
and change a setting there, which is what the tip asked of them before.

**Vendor settings pages**, such as Samsung's per-app battery screen. Their paths differ by vendor and
version and are not documented by Android; the walk-through in `docs/backlog.md` ("Walk the user
through exempting the app from battery management") is where those are checked on a device.

## Open

Whether allowing it in the dialog also sets One UI's own battery setting for the app to
Unrestricted has not been seen on the test phone.
