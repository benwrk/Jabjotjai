package com.benwrk.jabjotjai.monitor

import com.benwrk.jabjotjai.raw.AccRecord
import com.benwrk.jabjotjai.raw.EcgRecord
import com.benwrk.jabjotjai.raw.EndRecord
import com.benwrk.jabjotjai.raw.EventRecord
import com.benwrk.jabjotjai.raw.GapRecord
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.HrRecord
import com.benwrk.jabjotjai.raw.RawEvents
import com.benwrk.jabjotjai.raw.RawLine

/** One channel's samples, oldest first: sensor timestamps and values as the file holds them. */
class TraceSnapshot(val tNs: LongArray, val values: IntArray) {
    val size: Int get() = tNs.size

    companion object {
        val EMPTY = TraceSnapshot(LongArray(0), IntArray(0))
    }
}

/** When the latest frame of a stream was received, and the sensor time of its last sample. */
data class FrameTiming(val lastSampleTNs: Long, val receivedMonoNs: Long)

/** The latest heart-rate record, as the strap reported it. */
data class HeartRate(val bpm: Int, val lastRrMs: Int?, val contact: Boolean?, val receivedMonoNs: Long)

/** Everything the monitor screen shows, as of the last line read. */
data class MonitorSnapshot(
    val header: HeaderRecord?,
    val ecg: TraceSnapshot,
    val accX: TraceSnapshot,
    val accY: TraceSnapshot,
    val accZ: TraceSnapshot,
    val ecgTiming: FrameTiming?,
    val accTiming: FrameTiming?,
    val heartRate: HeartRate?,
    val batteryPct: Int?,
    /** The last connection event seen: one of [RawEvents.CONNECTING], `CONNECTED`, `DISCONNECTED`. */
    val connection: String?,
    val gaps: Int,
    val unknownLines: Int,
    val malformedLines: Int,
    val ended: Boolean,
) {
    companion object {
        val EMPTY = MonitorSnapshot(
            header = null,
            ecg = TraceSnapshot.EMPTY,
            accX = TraceSnapshot.EMPTY,
            accY = TraceSnapshot.EMPTY,
            accZ = TraceSnapshot.EMPTY,
            ecgTiming = null,
            accTiming = null,
            heartRate = null,
            batteryPct = null,
            connection = null,
            gaps = 0,
            unknownLines = 0,
            malformedLines = 0,
            ended = false,
        )
    }
}

/**
 * Folds the lines of a raw file into what the monitor shows. It keeps only the last [keepNs] of
 * each waveform, by sensor time, and derives nothing from the samples.
 *
 * Not thread-safe: one reader feeds it.
 */
class MonitorModel(private val keepNs: Long = DEFAULT_KEEP_NS) {

    private val ecg = SampleSeries()
    private val accX = SampleSeries()
    private val accY = SampleSeries()
    private val accZ = SampleSeries()

    private var header: HeaderRecord? = null
    private var ecgTiming: FrameTiming? = null
    private var accTiming: FrameTiming? = null
    private var heartRate: HeartRate? = null
    private var batteryPct: Int? = null
    private var connection: String? = null
    private var gaps = 0
    private var unknownLines = 0
    private var malformedLines = 0
    private var ended = false

    /** For a reader that starts near the end of the file, after the header line. */
    fun setHeader(header: HeaderRecord) {
        this.header = header
    }

    fun accept(line: RawLine) {
        when (line) {
            is RawLine.Unknown -> unknownLines++
            is RawLine.Malformed -> malformedLines++
            is RawLine.Record -> when (val record = line.record) {
                is HeaderRecord -> header = record
                is EcgRecord -> {
                    ecg.append(record.tNs, record.uV, keepNs)
                    record.tNs.lastOrNull()?.let { ecgTiming = FrameTiming(it, record.monoNs) }
                }
                is AccRecord -> {
                    accX.append(record.tNs, record.xMg, keepNs)
                    accY.append(record.tNs, record.yMg, keepNs)
                    accZ.append(record.tNs, record.zMg, keepNs)
                    record.tNs.lastOrNull()?.let { accTiming = FrameTiming(it, record.monoNs) }
                }
                is HrRecord -> heartRate = HeartRate(
                    bpm = record.bpm,
                    lastRrMs = record.rrMs.lastOrNull(),
                    contact = record.contact.takeIf { record.contactSupported },
                    receivedMonoNs = record.monoNs,
                )
                is EventRecord -> when (record.event) {
                    RawEvents.CONNECTING, RawEvents.CONNECTED, RawEvents.DISCONNECTED -> connection = record.event
                    RawEvents.BATTERY -> batteryPct = record.levelPct
                }
                is GapRecord -> gaps++
                is EndRecord -> ended = true
            }
        }
    }

    fun snapshot(): MonitorSnapshot = MonitorSnapshot(
        header = header,
        ecg = ecg.snapshot(),
        accX = accX.snapshot(),
        accY = accY.snapshot(),
        accZ = accZ.snapshot(),
        ecgTiming = ecgTiming,
        accTiming = accTiming,
        heartRate = heartRate,
        batteryPct = batteryPct,
        connection = connection,
        gaps = gaps,
        unknownLines = unknownLines,
        malformedLines = malformedLines,
        ended = ended,
    )

    companion object {
        /** Fifteen seconds: more than a sweep across a large tablet at 25 mm/s. */
        const val DEFAULT_KEEP_NS: Long = 15_000_000_000L
    }
}

/** A growable window of samples, oldest first, trimmed by sensor time. */
internal class SampleSeries(initialCapacity: Int = 1024) {

    private var tNs = LongArray(initialCapacity)
    private var values = IntArray(initialCapacity)
    private var start = 0
    private var end = 0

    val size: Int get() = end - start

    /**
     * Appends a frame and drops samples more than [keepNs] older than the newest. A frame that
     * starts before the newest sample already held means the sensor clock went back, so the old
     * samples are dropped rather than drawn out of order.
     */
    fun append(frameTNs: List<Long>, frameValues: List<Int>, keepNs: Long) {
        if (frameTNs.isEmpty()) return
        if (size > 0 && frameTNs.first() < tNs[end - 1]) {
            start = 0
            end = 0
        }
        ensureRoom(frameTNs.size)
        for (i in frameTNs.indices) {
            tNs[end] = frameTNs[i]
            values[end] = frameValues[i]
            end++
        }
        val oldest = tNs[end - 1] - keepNs
        while (start < end && tNs[start] < oldest) start++
    }

    fun snapshot(): TraceSnapshot = TraceSnapshot(tNs.copyOfRange(start, end), values.copyOfRange(start, end))

    private fun ensureRoom(extra: Int) {
        if (end + extra <= tNs.size) return
        val count = end - start
        val capacity = maxOf(tNs.size, (count + extra) * 2)
        val newTNs = LongArray(capacity)
        val newValues = IntArray(capacity)
        tNs.copyInto(newTNs, 0, start, end)
        values.copyInto(newValues, 0, start, end)
        tNs = newTNs
        values = newValues
        start = 0
        end = count
    }
}
