package com.benwrk.jabjotjai.monitor

import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.RawDecoders
import com.benwrk.jabjotjai.raw.RawLine
import com.benwrk.jabjotjai.raw.RawTail
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
 * The files the capture in [current] continues, oldest first: each header's `continues` followed
 * back through [dir] while the file is there, until one started more than [historyNs] before
 * [current], on its clock; at most [MAX_EARLIER_FILES], so a chain that loops cannot run forever.
 */
fun earlierCaptures(dir: File, current: HeaderRecord, historyNs: Long = TREND_HISTORY_NS): List<Pair<File, HeaderRecord>> {
    val found = mutableListOf<Pair<File, HeaderRecord>>()
    var session = current.continues
    while (session != null && found.size < MAX_EARLIER_FILES) {
        val file = File(dir, "$session.jsonl")
        // A header that cannot be read names nothing further back, so the chain ends there.
        val header = file.takeIf { it.isFile }?.let { runCatching { RawTail.readHeader(it) }.getOrNull() } ?: break
        found += file to header
        if (header.start.monoNs + clockShiftNs(header, current) < current.start.monoNs - historyNs) break
        session = header.continues
    }
    return found.reversed()
}

private const val MAX_EARLIER_FILES = 200

/**
 * How much [readEarlierTrends] read: [files] files of [bytes] in all, [unreadable] of them skipped
 * because they could not be read, and whether it [finished] rather than being stopped.
 */
data class EarlierTrendsRead(val files: Int, val bytes: Long, val unreadable: Int, val finished: Boolean)

/**
 * Reads the heart-rate and RR history of the files [current] continues into [model], oldest
 * first, each placed on [current]'s clock and ended with a break, so the time between files is
 * never joined (see [MonitorModel.acceptEarlier]). Only heart-rate, gap and event lines are
 * decoded; a line's type is told from its start, so the waveform lines that make up most of a file
 * cost only their reading.
 *
 * A file that cannot be read, or stops being readable part-way, is ended with a break where its
 * reading stopped and counted in [EarlierTrendsRead.unreadable]; the rest are still read. A line
 * that cannot be decoded is passed over, as the capture's own reader passes it over. [onProgress]
 * is told the share of all the files' bytes read so far, from 0 to 1, at most a hundred times;
 * reading stops, unfinished, as soon as [keepGoing] says no.
 */
fun readEarlierTrends(
    model: MonitorModel,
    dir: File,
    current: HeaderRecord,
    onProgress: (Float) -> Unit = {},
    keepGoing: () -> Boolean = { true },
): EarlierTrendsRead {
    val earlier = earlierCaptures(dir, current)
    val total = earlier.sumOf { it.first.length() }.coerceAtLeast(1)
    var done = 0L
    var reported = -1
    var unreadable = 0
    for ((file, header) in earlier) {
        if (!keepGoing()) return EarlierTrendsRead(earlier.size, total, unreadable, finished = false)
        val shift = clockShiftNs(header, current)
        var last = header.start.monoNs
        try {
            file.bufferedReader().useLines { lines ->
                var decode: ((String) -> RawLine)? = null
                var count = 0
                for (line in lines) {
                    // Each character of a raw line is one byte, and the newline one more.
                    done += line.length + 1
                    if (++count % CHECK_EVERY_LINES == 0) {
                        if (!keepGoing()) return EarlierTrendsRead(earlier.size, total, unreadable, finished = false)
                        val percent = (done * 100 / total).toInt()
                        if (percent > reported) {
                            reported = percent
                            onProgress(percent / 100f)
                        }
                    }
                    val use = decode ?: RawDecoders.forHeaderLine(line, file).also { decode = it }
                    if (!line.startsWith(HR_LINE) && !line.startsWith(GAP_LINE) && !line.startsWith(EVENT_LINE)) continue
                    val decoded = use(line)
                    model.acceptEarlier(decoded, shift)
                    (decoded as? RawLine.Record)?.record?.timeMonoNs()?.let { if (it > last) last = it }
                }
            }
        } catch (e: Exception) {
            // Unreadable from the start (a schema this reader does not know) or part-way (a read
            // error): what was read stays, broken off here.
            unreadable++
        }
        model.endEarlier(last + shift)
    }
    onProgress(1f)
    return EarlierTrendsRead(earlier.size, total, unreadable, finished = true)
}

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

    /** Read: the history, oldest first, ending in a break, and how many files could not be read. */
    data class Read(override val session: String, val heartRate: TrendSnapshot, val rr: TrendSnapshot, val unreadable: Int) : EarlierTrends
}
