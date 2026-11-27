package com.benwrk.jabjotjai.monitor

import com.benwrk.jabjotjai.raw.AccRecord
import com.benwrk.jabjotjai.raw.EcgRecord
import com.benwrk.jabjotjai.raw.EndRecord
import com.benwrk.jabjotjai.raw.EventRecord
import com.benwrk.jabjotjai.raw.GapRecord
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.HrRecord
import com.benwrk.jabjotjai.raw.RawDecoders
import com.benwrk.jabjotjai.raw.RawEvents
import com.benwrk.jabjotjai.raw.RawFileLines
import com.benwrk.jabjotjai.raw.RawLine
import com.benwrk.jabjotjai.raw.RawRecord
import com.benwrk.jabjotjai.raw.RawTail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import kotlin.math.abs

/**
 * How far back heart-rate and RR rows can look: six rows of an hour each. Reading that far back
 * holds up the capture's reader once, when a capture resumes: on the test phone (Galaxy S25)
 * reading went at 60 to 70 MB a second (a 36 MB recording of about an hour in 0.5 to 0.6 s, a
 * chain of six files and 20 MB in 0.55 s, 2026-11-27), so six hours, about 220 MB, takes 3 to 4 s.
 */
const val TREND_HISTORY_NS: Long = 6 * 60 * 60_000_000_000L

/**
 * What to add to [earlier]'s monotonic times to place them on [current]'s clock. Within one run of
 * the phone both files share one monotonic clock, so nothing; a restart in between starts it again
 * from zero, which shows as the gap between wall-clock and monotonic time at each file's start
 * differing, and that difference moves the earlier times across. Network time corrections move
 * the wall clock by far less than [RESTART_THRESHOLD_NS], so they are not taken for a restart.
 */
fun clockShiftNs(earlier: HeaderRecord, current: HeaderRecord): Long {
    val shift = (earlier.start.wallMs * 1_000_000 - earlier.start.monoNs) - (current.start.wallMs * 1_000_000 - current.start.monoNs)
    return if (abs(shift) > RESTART_THRESHOLD_NS) shift else 0L
}

private const val RESTART_THRESHOLD_NS = 60_000_000_000L

/**
 * The files a capture continues, oldest first, and [brokenAt], the session where the chain broke
 * because its file is missing or its header cannot be read, so nothing further back is known.
 */
data class EarlierChain(val files: List<Pair<File, HeaderRecord>>, val brokenAt: String? = null)

/**
 * The files the capture in [current] continues: each header's `continues` followed back through
 * [dir] until one started more than [historyNs] before [current], on its clock; at most
 * [MAX_EARLIER_FILES], so a chain that loops cannot run forever.
 */
fun earlierCaptures(dir: File, current: HeaderRecord, historyNs: Long = TREND_HISTORY_NS): EarlierChain {
    val found = mutableListOf<Pair<File, HeaderRecord>>()
    var session = current.continues
    while (session != null && found.size < MAX_EARLIER_FILES) {
        val file = File(dir, "$session.jsonl")
        val header = file.takeIf { it.isFile }?.let { runCatching { RawTail.readHeader(it) }.getOrNull() }
            ?: return EarlierChain(found.reversed(), brokenAt = session)
        found += file to header
        if (header.start.monoNs + clockShiftNs(header, current) < current.start.monoNs - historyNs) break
        session = header.continues
    }
    return EarlierChain(found.reversed())
}

private const val MAX_EARLIER_FILES = 200

/**
 * An earlier file of a resumed capture, as [readEarlierTrends] found it: [shiftNs] places its times
 * on the current file's clock (see [clockShiftNs]), on which it runs from [startNs] to [endNs], its
 * last line; [timeline] maps those times to places in the file, as the capture's own reader maps
 * its file, so the past reader can start part-way through it.
 */
class EarlierFile(val file: File, val header: HeaderRecord, val shiftNs: Long, val startNs: Long, val endNs: Long, val timeline: CaptureTimeline)

/**
 * How much [readEarlierTrends] read: [files] files of [bytes] in all, and [unreadable] that could
 * not be read, wholly or in part, or were missing from the chain; whether it [finished] rather than
 * being stopped; and the files read, oldest first, for the past reader.
 */
data class EarlierTrendsRead(
    val files: Int,
    val bytes: Long,
    val unreadable: Int,
    val finished: Boolean,
    val read: List<EarlierFile> = emptyList(),
)

/**
 * Reads the heart-rate and RR history of the files [current] continues into [model], oldest
 * first, each placed on [current]'s clock and ended with a break, so the time between files is
 * never joined (see [MonitorModel.acceptEarlier]), and maps each file for the past reader (see
 * [EarlierFile]). Only heart-rate, gap and event lines are decoded; a line's type is told from its
 * start, and its time from its `"mono_ns":` field, so the waveform lines that make up most of a
 * file cost little more than their reading.
 *
 * A file that cannot be read, or stops being readable part-way, is ended with a break where its
 * reading stopped and counted in [EarlierTrendsRead.unreadable], as is a file the chain names that
 * is missing or whose header cannot be read; the rest are still read. A file that could not be read
 * at all is not passed on to the past reader. A line that cannot be decoded is passed over, as the
 * capture's own reader passes it over. [onProgress] is told the share of all the files' bytes read
 * so far, from 0 to 1, at most a hundred times; reading stops, unfinished, as soon as [keepGoing]
 * says no.
 */
fun readEarlierTrends(
    model: MonitorModel,
    dir: File,
    current: HeaderRecord,
    onProgress: (Float) -> Unit = {},
    keepGoing: () -> Boolean = { true },
): EarlierTrendsRead {
    val chain = earlierCaptures(dir, current)
    val earlier = chain.files
    val files = earlier.size + if (chain.brokenAt != null) 1 else 0
    val total = earlier.sumOf { it.first.length() }.coerceAtLeast(1)
    var before = 0L
    var reported = -1
    var unreadable = if (chain.brokenAt != null) 1 else 0
    val read = mutableListOf<EarlierFile>()
    for ((file, header) in earlier) {
        if (!keepGoing()) return EarlierTrendsRead(files, total, unreadable, finished = false, read)
        val shift = clockShiftNs(header, current)
        var last = header.start.monoNs
        val timeline = CaptureTimeline()
        var connection: String? = null
        var battery: Int? = null
        var gaps = 0
        var decode: ((String) -> RawLine)? = null
        try {
            RawFileLines(file).use { lines ->
                var count = 0
                for ((lineOffset, line) in lines) {
                    if (++count % CHECK_EVERY_LINES == 0) {
                        if (!keepGoing()) return EarlierTrendsRead(files, total, unreadable, finished = false, read)
                        val percent = ((before + lineOffset) * 100 / total).toInt()
                        if (percent > reported) {
                            reported = percent
                            onProgress(percent / 100f)
                        }
                    }
                    monoNsOf(line)?.let { time ->
                        timeline.addLine(lineOffset, time + shift)
                        if (time > last) last = time
                    }
                    val use = decode ?: RawDecoders.forHeaderLine(line, file).also { decode = it }
                    if (!line.startsWith(HR_LINE) && !line.startsWith(GAP_LINE) && !line.startsWith(EVENT_LINE)) continue
                    val decoded = use(line)
                    model.acceptEarlier(decoded, shift)
                    val record = (decoded as? RawLine.Record)?.record ?: continue
                    // Where the state changes, noted as the capture's own reader notes it.
                    val changed = when (record) {
                        is EventRecord -> when (record.event) {
                            RawEvents.CONNECTING, RawEvents.CONNECTED, RawEvents.DISCONNECTED -> true.also { connection = record.event }
                            RawEvents.BATTERY -> true.also { battery = record.levelPct }
                            else -> false
                        }
                        is GapRecord -> true.also { gaps++ }
                        else -> false
                    }
                    if (changed) timeline.addState(record.timeMonoNs() + shift, CaptureStateAt(connection, battery, gaps))
                    if (record.timeMonoNs() > last) last = record.timeMonoNs()
                }
            }
        } catch (e: Exception) {
            // Unreadable from the start (a schema this reader does not know) or part-way (a read
            // error): what was read stays, broken off here.
            unreadable++
        }
        before += file.length()
        model.endEarlier(last + shift)
        // A file whose header could not be read has nothing the past reader could read either.
        if (decode != null) read += EarlierFile(file, header, shift, header.start.monoNs + shift, last + shift, timeline)
    }
    onProgress(1f)
    return EarlierTrendsRead(files, total, unreadable, finished = true, read)
}

/**
 * The time in a raw line's `"mono_ns":` field, found as text rather than by decoding the line; for
 * a header, its start. A gap line has none: its times are `from_mono_ns` and `to_mono_ns`.
 */
internal fun monoNsOf(line: String): Long? {
    val at = line.indexOf(MONO_FIELD)
    if (at < 0) return null
    var i = at + MONO_FIELD.length
    var value = 0L
    var digits = 0
    while (i < line.length && line[i] in '0'..'9') {
        value = value * 10 + (line[i] - '0')
        i++
        digits++
    }
    return value.takeIf { digits > 0 }
}

private const val MONO_FIELD = "\"mono_ns\":"

/** How many lines pass between looks at whether to stop and how far reading has got. */
private const val CHECK_EVERY_LINES = 256

private const val HR_LINE = "{\"type\":\"hr\""
private const val GAP_LINE = "{\"type\":\"gap\""
private const val EVENT_LINE = "{\"type\":\"event\""

/** Where the history of the files a capture continues stands, for the capture in [session]. */
sealed interface EarlierTrends {
    val session: String?

    /** Nothing to read: no capture, or one that continues nothing. */
    data object None : EarlierTrends {
        override val session: String? = null
    }

    /** Being read in the background; [fraction] of the files' bytes read so far. */
    data class Reading(override val session: String, val fraction: Float) : EarlierTrends

    /** Read: the history, oldest first, ending in a break; how many files could not be read; and the files, for the past reader. */
    data class Read(
        override val session: String,
        val heartRate: TrendSnapshot,
        val rr: TrendSnapshot,
        val unreadable: Int,
        val files: List<EarlierFile> = emptyList(),
    ) : EarlierTrends
}

/**
 * [this] with its times on the phone's monotonic clock moved by [ns], placing a record of an
 * earlier file on the current file's clock (see [clockShiftNs]); the strap's own sample times stay
 * as it sent them. Nothing is copied when there is nothing to move, which is the usual case.
 */
internal fun RawRecord.shiftedBy(ns: Long): RawRecord = if (ns == 0L) this else when (this) {
    is HeaderRecord -> copy(start = start.copy(monoNs = start.monoNs + ns))
    is EventRecord -> copy(monoNs = monoNs + ns)
    is EcgRecord -> copy(monoNs = monoNs + ns)
    is AccRecord -> copy(monoNs = monoNs + ns)
    is HrRecord -> copy(monoNs = monoNs + ns)
    is GapRecord -> copy(fromMonoNs = fromMonoNs + ns, toMonoNs = toMonoNs?.plus(ns))
    is EndRecord -> copy(monoNs = monoNs + ns)
}

/**
 * The lines of an earlier, finished file from [offset], a line start such as a timeline mark (or
 * else the next line's start), decoded as its header says and with their times moved by
 * [shiftNs]; it ends with the file's last complete line. The file is closed however collecting
 * stops, as it does at every step of a scrub.
 *
 * @throws java.io.IOException if the file cannot be read, or its header names a schema this build
 *   cannot read
 */
internal fun earlierLines(file: File, offset: Long, shiftNs: Long): Flow<RawLine> = flow {
    val headerLine = RawFileLines(file).use { lines -> if (lines.hasNext()) lines.next().text else null } ?: return@flow
    val decode = RawDecoders.forHeaderLine(headerLine, file)
    RawFileLines(file, offset).use { lines ->
        for ((_, line) in lines) {
            val decoded = decode(line)
            emit(if (decoded is RawLine.Record) RawLine.Record(decoded.record.shiftedBy(shiftNs)) else decoded)
        }
    }
}
