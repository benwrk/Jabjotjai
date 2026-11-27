package com.benwrk.jabjotjai.monitor

import com.benwrk.jabjotjai.raw.AccRecord
import com.benwrk.jabjotjai.raw.EcgRecord
import com.benwrk.jabjotjai.raw.EndRecord
import com.benwrk.jabjotjai.raw.EventRecord
import com.benwrk.jabjotjai.raw.GapRecord
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.HrRecord
import com.benwrk.jabjotjai.raw.RawRecord

/** When a record happened on the phone's monotonic clock: its receive time, or a gap's end, else its start. */
fun RawRecord.timeMonoNs(): Long = when (this) {
    is HeaderRecord -> start.monoNs
    is EventRecord -> monoNs
    is EcgRecord -> monoNs
    is AccRecord -> monoNs
    is HrRecord -> monoNs
    is GapRecord -> toMonoNs ?: fromMonoNs
    is EndRecord -> monoNs
}

/** The capture's connection, strap battery and gap count as they stood at some moment. */
data class CaptureStateAt(val connection: String?, val batteryPercentage: Int?, val gaps: Int) {
    companion object {
        val INITIAL = CaptureStateAt(connection = null, batteryPercentage = null, gaps = 0)
    }
}

/**
 * A map of a running capture's raw file, built by the one reader that reads it from its first
 * line: where in the file each second of the capture starts, and how the connection, battery and
 * gap count changed. A reader that jumps to an earlier moment starts at [markAtOrBefore] and takes
 * the state from [stateAt], rather than reading the whole file again, which takes longer the
 * longer the capture runs.
 *
 * Thread-safe: one reader adds to it while others look things up.
 */
class CaptureTimeline(private val stepNs: Long = STEP_NS) {

    private var times = LongArray(1024)
    private var offsets = LongArray(1024)
    private var count = 0

    private var stateTimes = LongArray(64)
    private val states = ArrayList<CaptureStateAt>()

    /** Notes where a line received at [monoNs] starts, if a step has passed since the last note. */
    @Synchronized
    fun addLine(offset: Long, monoNs: Long) {
        if (count > 0 && monoNs < times[count - 1] + stepNs) return
        if (count == times.size) {
            times = times.copyOf(count * 2)
            offsets = offsets.copyOf(count * 2)
        }
        times[count] = monoNs
        offsets[count] = offset
        count++
    }

    /** Notes the state as of [monoNs], if it differs from the last one noted. */
    @Synchronized
    fun addState(monoNs: Long, state: CaptureStateAt) {
        if (states.lastOrNull() == state) return
        if (states.size == stateTimes.size) stateTimes = stateTimes.copyOf(states.size * 2)
        // Kept in order for the search, even if a line's receive time is a little before the last.
        stateTimes[states.size] = if (states.isEmpty()) monoNs else maxOf(monoNs, stateTimes[states.size - 1])
        states.add(state)
    }

    /** A noted line: when it was received, and where it starts in the file. */
    data class Mark(val monoNs: Long, val offset: Long)

    /**
     * The last noted line received at or before [monoNs], so reading from it covers [monoNs]; null
     * if there is none, in which case read from the start of the file. To start a model there, give
     * it the state from just before the mark, `stateAt(mark.monoNs - 1)`: the mark's own line, and
     * everything after it, is read again.
     */
    @Synchronized
    fun markAtOrBefore(monoNs: Long): Mark? {
        val i = lastAtOrBefore(times, count, monoNs)
        return if (i < 0) null else Mark(times[i], offsets[i])
    }

    /** The state as it stood at [monoNs]: the last one noted at or before it. */
    @Synchronized
    fun stateAt(monoNs: Long): CaptureStateAt {
        val i = lastAtOrBefore(stateTimes, states.size, monoNs)
        return if (i < 0) CaptureStateAt.INITIAL else states[i]
    }

    private fun lastAtOrBefore(sorted: LongArray, size: Int, value: Long): Int {
        var low = 0
        var high = size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (sorted[mid] <= value) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    companion object {
        /** One noted line a second: about 30,000 for an 8-hour capture. */
        const val STEP_NS: Long = 1_000_000_000L
    }
}
