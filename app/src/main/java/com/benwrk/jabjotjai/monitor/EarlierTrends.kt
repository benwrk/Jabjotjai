package com.benwrk.jabjotjai.monitor

import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.RawDecoders
import com.benwrk.jabjotjai.raw.RawLine
import com.benwrk.jabjotjai.raw.RawTail
import java.io.File
import kotlin.math.abs

/** How far back heart-rate and RR rows can look: six rows of an hour each. */
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
        val header = file.takeIf { it.isFile }?.let { RawTail.readHeader(it) } ?: break
        found += file to header
        if (header.start.monoNs + clockShiftNs(header, current) < current.start.monoNs - historyNs) break
        session = header.continues
    }
    return found.reversed()
}

private const val MAX_EARLIER_FILES = 200

/**
 * Reads the heart-rate and RR history of the files [current] continues into [model], oldest
 * first, each placed on [current]'s clock and ended with a break, so the time between files is
 * never joined (see [MonitorModel.acceptEarlier]). Only heart-rate, gap and event lines are
 * decoded; a line's type is told from its start, so the waveform lines that make up most of a file
 * cost only their reading.
 */
fun readEarlierTrends(model: MonitorModel, dir: File, current: HeaderRecord) {
    for ((file, header) in earlierCaptures(dir, current)) {
        val shift = clockShiftNs(header, current)
        var last = header.start.monoNs
        file.bufferedReader().useLines { lines ->
            var decode: ((String) -> RawLine)? = null
            for (line in lines) {
                val use = decode ?: RawDecoders.forHeaderLine(line, file).also { decode = it }
                if (!line.startsWith(HR_LINE) && !line.startsWith(GAP_LINE) && !line.startsWith(EVENT_LINE)) continue
                val decoded = use(line)
                model.acceptEarlier(decoded, shift)
                (decoded as? RawLine.Record)?.record?.timeMonoNs()?.let { if (it > last) last = it }
            }
        }
        model.endEarlier(last + shift)
    }
}

private const val HR_LINE = "{\"type\":\"hr\""
private const val GAP_LINE = "{\"type\":\"gap\""
private const val EVENT_LINE = "{\"type\":\"event\""
