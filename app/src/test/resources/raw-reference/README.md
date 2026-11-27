# Reference recordings

Real recordings from the test phone (Galaxy S25, One UI 9) and a Polar H10, made on 2026-11-27,
kept so every later reader is tested against the files earlier versions actually wrote. Raw files
are kept for good, so the app has to read every schema it has ever written (`docs/raw-format.md`,
`docs/rules/raw-format.md`).

Each is the whole file as the app wrote it, with one change: the strap's identifiers are replaced
by values of the same length, so every byte offset is as it was.

| Replaced                                           | By                  |
| -------------------------------------------------- | ------------------- |
| The strap's id, in its name and serial number      | `A1B2C3D4`          |
| The strap's Bluetooth address                      | `00:11:22:33:44:55` |
| Its system id (`device_info` key `SYSTEM_ID_HEX`)  | `001122FFFE334455`  |

The owner agreed to publishing their ECG, heart rate and motion in these files; the replaced
identifiers are the only personal data taken out.

| File                                    | Schema | App   | What it shows                                                                                       |
| --------------------------------------- | ------ | ----- | --------------------------------------------------------------------------------------------------- |
| `1.0/20261127T100423Z-3cf1.jsonl`       | 1.0    | 0.1.0 | The first recording: a header and an end, the strap never connected                                 |
| `1.1/20261127T112124Z-4b29.jsonl`       | 1.1    | 0.1.0 | A short capture stopped from the app, ending with an `end` record                                   |
| `1.2/20261127T131351Z-3af1.jsonl`       | 1.2    | 0.3 * | A capture an update stopped: no `end` record, and an ending note with `PACKAGE_UPDATED`             |
| `1.2/20261127T131715Z-5794.jsonl`       | 1.2    | 0.3 * | The capture that resumed it, its header `continues` naming `3af1`; stopped by an update in turn      |
| `1.2/20261127T152036Z-31fd.jsonl`       | 1.2    | 0.3.0 | A capture force-stopped, note `USER_REQUESTED`; it continues a file not kept here                   |

* Written by 0.3 while it was being built, before the version moved on, so their headers say
`0.2.0 (2000)`.

Add a recording here for every new schema version, from the first app version that writes it.
