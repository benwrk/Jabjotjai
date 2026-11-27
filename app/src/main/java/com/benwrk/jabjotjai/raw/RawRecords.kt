package com.benwrk.jabjotjai.raw

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The raw file schema this build writes, as `major.minor`. Every version's definition is in
 * `docs/raw-format.md`. The minor number grows for additive changes, the major number for any
 * change a reader of the previous version would misread.
 */
const val RAW_SCHEMA: String = "1.0"

/**
 * One line of a raw file. The serialised form of each subtype is the file format, so within one
 * schema major version a field's name, unit and meaning never change.
 */
@Serializable
sealed interface RawRecord

/** The phone's two clocks, read at one instant. */
@Serializable
data class Stamp(
    /** `System.currentTimeMillis()`. Wall-clock time, which the user or the network can move. */
    @SerialName("wall_ms") val wallMs: Long,
    /** `SystemClock.elapsedRealtimeNanos()`. Monotonic since boot, including time asleep. */
    @SerialName("mono_ns") val monoNs: Long,
)

@Serializable
data class Phone(
    val make: String,
    val model: String,
    val android: String,
    val api: Int,
)

@Serializable
data class Sensor(
    val id: String,
    val name: String,
)

/** The first line of every raw file. */
@Serializable
@SerialName("header")
data class HeaderRecord(
    val schema: String,
    val session: String,
    val app: String,
    val sdk: String,
    val phone: Phone,
    val start: Stamp,
    val sensor: Sensor,
    /** The date sensor timestamps count from. The SDK documents the date but not a time zone. */
    @SerialName("sensor_epoch") val sensorEpoch: String,
) : RawRecord

/** Something that happened to the connection or to a stream. Fields that do not apply are absent. */
@Serializable
@SerialName("event")
data class EventRecord(
    @SerialName("wall_ms") val wallMs: Long,
    @SerialName("mono_ns") val monoNs: Long,
    /** One of [RawEvents]. */
    val event: String,
    /** One of [RawStreams], for stream events. */
    val stream: String? = null,
    /** SDK feature name, for [RawEvents.FEATURE_READY]. */
    val feature: String? = null,
    /** SDK disconnect reason, for [RawEvents.DISCONNECTED]. */
    val reason: String? = null,
    /** SDK recovery action, for [RawEvents.DISCONNECTED]. */
    val recovery: String? = null,
    @SerialName("gatt_status") val gattStatus: Int? = null,
    @SerialName("device_command") val deviceCommand: String? = null,
    /** Device information name and value, for [RawEvents.DEVICE_INFO]. */
    val key: String? = null,
    val value: String? = null,
    @SerialName("level_pct") val levelPct: Int? = null,
    /** Every value the sensor offers per setting, for [RawEvents.SETTINGS]. */
    val available: Map<String, List<Int>>? = null,
    /** The value requested per setting, for [RawEvents.SETTINGS]. */
    val selected: Map<String, Int>? = null,
    val message: String? = null,
) : RawRecord

/** The values of [EventRecord.event]. */
object RawEvents {
    const val CONNECTING = "connecting"
    const val CONNECTED = "connected"
    const val DISCONNECTED = "disconnected"
    const val FEATURE_READY = "feature_ready"
    const val DEVICE_INFO = "device_info"
    const val BATTERY = "battery"
    const val SETTINGS = "settings"
    const val STREAM_STARTED = "stream_started"
    const val STREAM_ERROR = "stream_error"
}

/** The values of [EventRecord.stream] and [GapRecord.stream]. */
object RawStreams {
    const val ECG = "ecg"
    const val ACC = "acc"
    const val HR = "hr"
}

/** One ECG frame, with its samples exactly as the SDK delivered them. */
@Serializable
@SerialName("ecg")
data class EcgRecord(
    @SerialName("wall_ms") val wallMs: Long,
    @SerialName("mono_ns") val monoNs: Long,
    /** Sensor timestamp of each sample, in nanoseconds since [HeaderRecord.sensorEpoch]. */
    @SerialName("t_ns") val tNs: List<Long>,
    /** Each sample's voltage, in microvolts. */
    @SerialName("uV") val uV: List<Int>,
) : RawRecord {
    init {
        require(uV.size == tNs.size) { "ecg frame has ${tNs.size} timestamps but ${uV.size} samples" }
    }
}

/** One accelerometer frame, with its samples exactly as the SDK delivered them. */
@Serializable
@SerialName("acc")
data class AccRecord(
    @SerialName("wall_ms") val wallMs: Long,
    @SerialName("mono_ns") val monoNs: Long,
    /** Sensor timestamp of each sample, in nanoseconds since [HeaderRecord.sensorEpoch]. */
    @SerialName("t_ns") val tNs: List<Long>,
    /** Acceleration per axis in milli-g, including gravity. */
    @SerialName("x_mG") val xMg: List<Int>,
    @SerialName("y_mG") val yMg: List<Int>,
    @SerialName("z_mG") val zMg: List<Int>,
) : RawRecord {
    init {
        require(xMg.size == tNs.size && yMg.size == tNs.size && zMg.size == tNs.size) {
            "acc frame has ${tNs.size} timestamps but ${xMg.size}/${yMg.size}/${zMg.size} samples"
        }
    }
}

/**
 * One heart-rate notification, with every field the SDK delivered. It carries no sensor
 * timestamp, so the phone's receive time is the only time it has.
 */
@Serializable
@SerialName("hr")
data class HrRecord(
    @SerialName("wall_ms") val wallMs: Long,
    @SerialName("mono_ns") val monoNs: Long,
    val bpm: Int,
    /** R-R intervals in 1/1024 s, as the Bluetooth heart-rate service sent them. */
    @SerialName("rr_1024") val rr1024: List<Int>,
    /** The same intervals as the SDK converted them to milliseconds. */
    @SerialName("rr_ms") val rrMs: List<Int>,
    @SerialName("rr_available") val rrAvailable: Boolean,
    val contact: Boolean,
    @SerialName("contact_supported") val contactSupported: Boolean,
    @SerialName("corrected_bpm") val correctedBpm: Int,
    @SerialName("ppg_quality") val ppgQuality: Int,
) : RawRecord

/**
 * A stretch in which a stream delivered nothing because something went wrong. It is written
 * when the stream resumes, so both ends are known. In [EndRecord.openGaps] the `to` fields are
 * absent because the stream never resumed.
 */
@Serializable
@SerialName("gap")
data class GapRecord(
    /** One of [RawStreams]. */
    val stream: String,
    /** Why the stream stopped: [RawEvents.DISCONNECTED] or [RawEvents.STREAM_ERROR]. */
    val reason: String,
    /** Receive time of the last frame before the gap. */
    @SerialName("from_mono_ns") val fromMonoNs: Long,
    /** Receive time of the first frame after the gap. */
    @SerialName("to_mono_ns") val toMonoNs: Long? = null,
    /** Sensor time of the last sample before the gap. Absent for streams without sensor time. */
    @SerialName("from_t_ns") val fromTNs: Long? = null,
    /** Sensor time of the first sample after the gap. */
    @SerialName("to_t_ns") val toTNs: Long? = null,
) : RawRecord

/** The last line of a raw file that was closed deliberately. */
@Serializable
@SerialName("end")
data class EndRecord(
    @SerialName("wall_ms") val wallMs: Long,
    @SerialName("mono_ns") val monoNs: Long,
    val reason: String,
    /** Gaps still open when the file was closed. */
    @SerialName("open_gaps") val openGaps: List<GapRecord> = emptyList(),
) : RawRecord
