# Raw file format

The definition of every raw file schema this app has written. Raw files are never rewritten, so
every schema version below stays readable for as long as its files exist. A version's section
is never edited to change its meaning: a new version gets a new section. Why the format is shaped
this way is decision `0004`.

## Rules for every version

**A file is UTF-8 JSON Lines**: one JSON object per line, each line ended by `\n`. The first key
of every object is `type`, which names the record.

**The first line is a `header`.** A file that was closed deliberately ends with an `end` line. A
file without one was cut off by a crash or a power cut, and its last line may be torn, with no
trailing newline. A reader ignores a torn last line and treats the file as ending at the last
complete line.

**The header's `schema` is `"major.minor"`.** The minor number grows for additive changes: a new
record type, or a new optional field. The major number grows for anything a reader of the
previous version would misread: a field renamed, removed, or given a different meaning or unit.

**A reader** accepts any file whose major version it knows, whatever the minor; skips fields and
record types it does not know; and reports a malformed line as malformed rather than dropping it.

**Integers are exact 64-bit values.** Sensor timestamps are about 8.5 × 10^17, above 2^53, so a
parser that turns every number into a double rounds them to a multiple of 128 ns. JavaScript's
`JSON.parse` is such a parser. A reader must parse integers exactly.

**Units are in the field names**: `_ms` milliseconds, `_ns` nanoseconds, `uV` microvolts, `_mG`
milli-g, `_1024` units of 1/1024 s, `_pct` percent, `bpm` beats per minute. A field without a
unit suffix is a count, a flag or a label.

**Three clocks:**

| Field     | Clock                                                                                                    |
| --------- | -------------------------------------------------------------------------------------------------------- |
| `wall_ms` | `System.currentTimeMillis()`: milliseconds since 1970-01-01 UTC. Can jump when the phone's clock is set. |
| `mono_ns` | `SystemClock.elapsedRealtimeNanos()`: nanoseconds since the phone booted, including time asleep.         |
| `t_ns`    | The sensor's timestamp of each sample, as the SDK delivered it, in nanoseconds since `sensor_epoch`.     |

That `mono_ns` keeps counting while the phone sleeps is from Android's documentation of elapsed
real time: "The elapsed time includes any time during which the device was asleep"
(developer.android.com/develop/background-work/services/alarms, read 2026-11-27).

## Where files live (not part of the schema)

`files/raw/<session>.jsonl` in the app's private storage. Each Start creates a new file and Stop
closes it; a file is never reopened. `<session>` is the start time in UTC plus a random suffix,
such as `20261127T091530Z-3f9a`, so a directory listing sorts by time.

Each record is written through to the operating system as one write as soon as it exists, so a
reader tailing the file sees it at once. A sync to storage follows within a second.

## Schema 1.0

First written by app 0.1.0.

### `header`

The first line.

| Field          | Type   | Meaning                                                                                |
| -------------- | ------ | -------------------------------------------------------------------------------------- |
| `schema`       | string | `"1.0"`                                                                                |
| `session`      | string | The file's session id                                                                  |
| `app`          | string | App version name and code, such as `"0.1.0 (1000)"`                                    |
| `sdk`          | string | Polar BLE SDK version                                                                  |
| `phone`        | object | `make`, `model`, `android` (release, as a string) and `api` (API level, an integer)    |
| `start`        | object | `wall_ms` and `mono_ns` when the file was created                                      |
| `sensor`       | object | `id`, the Polar device id, and `name`, its Bluetooth name                              |
| `sensor_epoch` | string | `"2000-01-01"`. The SDK documents the date ("The epoch of timestamp is 1.1.2000") but not a time zone. |

### `event`

Something that happened to the connection or a stream. Every event has `wall_ms`, `mono_ns` and
`event`. Only the fields that apply to that event are present.

| `event`          | Fields                                                                                                     |
| ---------------- | ---------------------------------------------------------------------------------------------------------- |
| `connecting`     |                                                                                                            |
| `connected`      |                                                                                                            |
| `disconnected`   | `reason` and `recovery`, the SDK's names; `gatt_status` and `device_command` when the SDK provides them    |
| `feature_ready`  | `feature`, the SDK's feature name                                                                          |
| `device_info`    | `key` and `value`, one Device Information item each                                                        |
| `battery`        | `level_pct`                                                                                                |
| `settings`       | `stream`; `available`, every value offered per setting; `selected`, the value requested per setting         |
| `stream_started` | `stream`                                                                                                   |
| `stream_error`   | `stream`, `message`                                                                                        |

Setting names in `available` and `selected`: `rate_hz`, `range_g`, `bits`, `channels`.

### `ecg`

One ECG frame.

| Field     | Type          | Meaning                                    |
| --------- | ------------- | ------------------------------------------ |
| `wall_ms` | integer       | When the phone received the frame          |
| `mono_ns` | integer       | When the phone received the frame          |
| `t_ns`    | integer array | Sensor timestamp of each sample            |
| `uV`      | integer array | Each sample's voltage, in microvolts        |

The two arrays have the same length.

### `acc`

One accelerometer frame: `wall_ms`, `mono_ns`, `t_ns`, and `x_mG`, `y_mG`, `z_mG`, the
acceleration on each axis in milli-g, including gravity. All four arrays have the same length.

### `hr`

One heart-rate notification. It carries no sensor timestamp: `wall_ms` and `mono_ns`, when the
phone received it, are the only time it has.

| Field               | Type          | Meaning                                                        |
| ------------------- | ------------- | -------------------------------------------------------------- |
| `bpm`               | integer       | Heart rate as the sensor reports it                            |
| `rr_1024`           | integer array | R-R intervals in 1/1024 s, as the Bluetooth heart-rate service sent them |
| `rr_ms`             | integer array | The same intervals, as the SDK converted them to milliseconds  |
| `rr_available`      | boolean       | Whether the notification carried R-R intervals                 |
| `contact`           | boolean       | Whether the sensor reports skin contact                        |
| `contact_supported` | boolean       | Whether the sensor reports contact at all                      |
| `corrected_bpm`     | integer       | The SDK's corrected heart rate; 0 when unavailable             |
| `ppg_quality`       | integer       | The SDK's optical signal quality; 0 on a sensor without one    |

### `gap`

A stretch in which a stream delivered nothing because the connection dropped or the stream
failed. It is written when the stream resumes, so both ends are known.

| Field          | Type    | Meaning                                                              |
| -------------- | ------- | -------------------------------------------------------------------- |
| `stream`       | string  | `ecg`, `acc` or `hr`                                                 |
| `reason`       | string  | `disconnected` or `stream_error`                                     |
| `from_mono_ns` | integer | Receive time of the last frame before the gap                        |
| `to_mono_ns`   | integer | Receive time of the first frame after the gap                        |
| `from_t_ns`    | integer | Sensor time of the last sample before the gap; absent for `hr`       |
| `to_t_ns`      | integer | Sensor time of the first sample after the gap; absent for `hr`       |

A jump in sensor timestamps while the stream stayed connected is not recorded as a gap. Finding
those is analysis of the raw file, not something the writer decides.

### `end`

The last line of a file that was closed deliberately: `wall_ms`, `mono_ns`, `reason`, and
`open_gaps`, a list of `gap` objects for streams that had not resumed, without their `to_` fields.
