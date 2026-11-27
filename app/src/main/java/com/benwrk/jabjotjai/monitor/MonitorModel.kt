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
import com.benwrk.jabjotjai.raw.RawStreams

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
    val batteryPercentage: Int?,
    /** The last connection event seen: one of [RawEvents.CONNECTING], `CONNECTED`, `DISCONNECTED`. */
    val connection: String?,
    val gaps: Int,
    /** When to signal the strap's recent beats, on the phone's monotonic clock, oldest first (see [BeatSchedule]). */
    val beats: List<Long>,
    /** Heart rate as the strap reported it, the whole capture, for the heart-rate rows. */
    val heartRateTrend: TrendSnapshot,
    /** Each RR interval in ms, at the time it ended (see [rrEndTimes]), for the RR rows. */
    val rrTrend: TrendSnapshot,
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
            batteryPercentage = null,
            connection = null,
            gaps = 0,
            beats = emptyList(),
            heartRateTrend = TrendSnapshot.EMPTY,
            rrTrend = TrendSnapshot.EMPTY,
            unknownLines = 0,
            malformedLines = 0,
            ended = false,
        )
    }
}

/**
 * Folds the lines of a raw file into what the monitor shows. It keeps only the last [keepNs] of
 * each waveform, by sensor time, and derives nothing from the samples. Beats are the strap's own,
 * from its heart-rate records.
 *
 * Not thread-safe: one reader feeds it.
 */
class MonitorModel(private val keepNs: Long = DEFAULT_KEEP_NS) {

    private val beats = BeatSchedule()
    private val ecgArrival = ArrivalDelay()
    private val accArrival = ArrivalDelay()
    private val heartRateTrend = TrendSeries()
    private val rrTrend = TrendSeries()
    private val ecg = SampleSeries()
    private val accX = SampleSeries()
    private val accY = SampleSeries()
    private val accZ = SampleSeries()

    private var header: HeaderRecord? = null
    private var ecgTiming: FrameTiming? = null
    private var accTiming: FrameTiming? = null
    private var heartRate: HeartRate? = null
    private var batteryPercentage: Int? = null
    private var connection: String? = null
    private var gaps = 0
    private var unknownLines = 0
    private var malformedLines = 0
    private var ended = false

    /** For a reader that starts near the end of the file, after the header line. */
    fun setHeader(header: HeaderRecord) {
        this.header = header
    }

    /**
     * For a reader that starts part-way through the file: the connection, battery and gap count
     * as they stood there, from what was read before it (see [CaptureTimeline]).
     */
    fun startFrom(state: CaptureStateAt) {
        connection = state.connection
        batteryPercentage = state.batteryPercentage
        gaps = state.gaps
    }

    fun accept(line: RawLine) {
        when (line) {
            is RawLine.Unknown -> unknownLines++
            is RawLine.Malformed -> malformedLines++
            is RawLine.Record -> when (val record = line.record) {
                is HeaderRecord -> header = record
                is EcgRecord -> {
                    ecg.append(record.tNs, record.uV, keepNs)
                    record.tNs.lastOrNull()?.let { ecgTiming = ecgArrival.timing(ecgTiming, it, record.monoNs) }
                }
                is AccRecord -> {
                    accX.append(record.tNs, record.xMg, keepNs)
                    accY.append(record.tNs, record.yMg, keepNs)
                    accZ.append(record.tNs, record.zMg, keepNs)
                    record.tNs.lastOrNull()?.let { accTiming = accArrival.timing(accTiming, it, record.monoNs) }
                }
                is HrRecord -> {
                    heartRate = HeartRate(
                        bpm = record.bpm,
                        lastRrMs = record.rrMs.lastOrNull(),
                        contact = record.contact.takeIf { record.contactSupported },
                        receivedMonoNs = record.monoNs,
                    )
                    // The 1/1024 s values as sent, rather than the SDK's rounded milliseconds.
                    if (record.rrAvailable) beats.accept(record.monoNs, record.rr1024.map { it * NS_PER_SECOND / 1024 })
                    appendTrends(record, shiftNs = 0)
                }
                is EventRecord -> when (record.event) {
                    RawEvents.CONNECTING, RawEvents.CONNECTED -> connection = record.event
                    RawEvents.DISCONNECTED -> {
                        connection = record.event
                        beats.restart()
                        breakTrends(record.monoNs)
                    }
                    RawEvents.BATTERY -> batteryPercentage = record.levelPct
                }
                is GapRecord -> {
                    gaps++
                    if (record.stream == RawStreams.HR) {
                        beats.restart()
                        breakTrends(record.fromMonoNs)
                    }
                }
                is EndRecord -> ended = true
            }
        }
    }

    /**
     * Takes [line] from a file this capture continues into the heart-rate and RR history only, its
     * times moved by [shiftNs] onto this file's clock (see [clockShiftNs]): nothing else of an
     * earlier file is shown. Its gaps and disconnections break the history, as this file's do.
     */
    fun acceptEarlier(line: RawLine, shiftNs: Long) {
        when (val record = (line as? RawLine.Record)?.record) {
            is HrRecord -> appendTrends(record, shiftNs)
            is GapRecord -> if (record.stream == RawStreams.HR) breakTrends(record.fromMonoNs + shiftNs)
            is EventRecord -> if (record.event == RawEvents.DISCONNECTED) breakTrends(record.monoNs + shiftNs)
            else -> Unit
        }
    }

    /** Ends an earlier file at [atNs]: the time until the next file is a break, never joined. */
    fun endEarlier(atNs: Long) = breakTrends(atNs)

    private fun appendTrends(record: HrRecord, shiftNs: Long) {
        // A strap that does not report contact at all is taken to have it.
        val contact = record.contact || !record.contactSupported
        if (record.bpm > 0) heartRateTrend.append(record.monoNs + shiftNs, record.bpm, contact)
        if (record.rrAvailable) {
            val ends = rrEndTimes(record.monoNs + shiftNs, record.rrMs)
            record.rrMs.forEachIndexed { i, rr -> rrTrend.append(ends[i], rr, contact) }
        }
    }

    private fun breakTrends(atNs: Long) {
        heartRateTrend.breakAt(atNs)
        rrTrend.breakAt(atNs)
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
        batteryPercentage = batteryPercentage,
        connection = connection,
        gaps = gaps,
        beats = beats.recent().asList(),
        heartRateTrend = heartRateTrend.snapshot(),
        rrTrend = rrTrend.snapshot(),
        unknownLines = unknownLines,
        malformedLines = malformedLines,
        ended = ended,
    )

    companion object {
        /** Forty seconds: a sweep 250 mm wide, a large tablet's, at the slowest speed, 6.25 mm/s. */
        const val DEFAULT_KEEP_NS: Long = 40_000_000_000L

        private const val NS_PER_SECOND = 1_000_000_000L
    }
}

/** A heart rate older than this is shown as no value rather than as a current reading. */
const val HR_STALE_NS: Long = 5_000_000_000L

/** The heart rate to show at [nowMonoNs]: the latest report, or null once it is [HR_STALE_NS] old. */
fun MonitorSnapshot.currentHeartRate(nowMonoNs: Long): HeartRate? =
    heartRate?.takeIf { nowMonoNs - it.receivedMonoNs < HR_STALE_NS }

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

/** Time since the capture started, from the header's monotonic stamp, in whole seconds; null before the header is read. */
fun MonitorSnapshot.elapsedSeconds(nowMonoNs: Long): Long? =
    header?.let { ((nowMonoNs - it.start.monoNs) / 1_000_000_000L).coerceAtLeast(0) }

/** [seconds] as `h:mm:ss`. */
fun elapsedText(seconds: Long): String = "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

/**
 * The least delay seen, over the last [WINDOW_NS] of frames, between a stream's last sample being
 * taken and its frame arriving. Frames are only ever delayed on the way, by amounts that vary from
 * frame to frame, so the least delay is the steadiest map from the strap's clock to the phone's.
 * The sweep's cursor, anchored to each frame's own arrival, jumped by that variation at every
 * frame; anchored to this, it moves at an even pace.
 */
internal class ArrivalDelay {
    private val received = ArrayDeque<Long>()
    private val delays = ArrayDeque<Long>()

    /** The timing to draw [lastSampleTNs] with, its frame having arrived at [receivedMonoNs]. */
    fun timing(previous: FrameTiming?, lastSampleTNs: Long, receivedMonoNs: Long): FrameTiming {
        // The strap's clock went back, as on reconnecting: delays measured against it no longer hold.
        if (previous != null && lastSampleTNs < previous.lastSampleTNs) {
            received.clear()
            delays.clear()
        }
        received.addLast(receivedMonoNs)
        delays.addLast(receivedMonoNs - lastSampleTNs)
        while (received.first() < receivedMonoNs - WINDOW_NS) {
            received.removeFirst()
            delays.removeFirst()
        }
        return FrameTiming(lastSampleTNs, lastSampleTNs + delays.min())
    }

    private companion object {
        /** Long enough to see a frame that came through quickly, short enough to follow clock drift. */
        const val WINDOW_NS = 30_000_000_000L
    }
}
