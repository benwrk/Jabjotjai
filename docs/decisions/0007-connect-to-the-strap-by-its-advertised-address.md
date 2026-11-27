---
number: 0007
title: 'Connect to the strap by the address it is advertising from, not by its device id'
status: Accepted
component: app
area: capture
date: 2026-11-27
---

# 0007 — Connect to the strap by the address it is advertising from, not by its device id

## Context

The first capture on the test phone (Galaxy S25, Android 17) stayed at `connecting` for three
minutes. The phone held two bonds named "Polar H10 600D732D", at addresses ending `D8:23` and
`0D:73`, and the Bluetooth stack showed the connection attempt going to `D8:23` (observed
2026-11-27, `dumpsys bluetooth_manager` and logcat).

The SDK resolves a device id to the first session whose advertised id matches
(`PolarServiceClientUtils.sessionByDeviceId`, SDK 9.0.0, read from source because the
documentation does not say how an id is resolved). With two sessions for one strap, that can be the
stale one, and the connection then waits for a device that never answers.

## Decision

- The scan records the address each strap is advertising from. Every SDK call for the capture
  (connect, stream settings, streams, disconnect) names the strap by that address.
- Callbacks that carry device information are matched on the address. Callbacks that carry only
  an identifier get the session's device id from the SDK, so they are matched on either.
- The header records the address, as `sensor.address` (schema 1.1).

Observed after the change, on the same phone: connected 2.4 s after `connecting`, to
`A0:9E:1A:60:0D:73`, with ECG at 130.08 Hz and the accelerometer at 200.68 Hz measured from the
file's sensor timestamps.

## Rejected

**Connecting by device id.** What the SDK's own README shows, and what failed here.

**Asking the user to remove the stale bond.** It works for one phone, but the app is for anyone,
and nothing tells a user which of two identically named bonds is the stale one.

## Consequences

- A strap must be seen in a scan before a capture can start, because the scan is what supplies
  the address. Reconnecting to a known strap without scanning would need the address stored.
