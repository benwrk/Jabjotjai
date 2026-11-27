package com.benwrk.jabjotjai.monitor

/**
 * A view of a [TrendSeries] as it stood: its first [size] points, times on the phone's monotonic
 * clock, oldest first, with [BREAK] for a value where a line must not be drawn across.
 */
class TrendSnapshot(val tNs: LongArray, val values: IntArray, val size: Int) {
    companion object {
        val EMPTY = TrendSnapshot(LongArray(0), IntArray(0), 0)

        /** A point that is not a value: a gap or a disconnection, which a trend line breaks at. */
        const val BREAK = Int.MIN_VALUE
    }
}

/**
 * A growing history of one value, for the heart-rate and RR rows, which span up to an hour. It is
 * only ever appended to, and nothing below its end changes, so a snapshot shares its arrays rather
 * than copying hours of points at every line read; growing them makes new arrays and leaves earlier
 * snapshots with theirs.
 */
internal class TrendSeries {
    private var tNs = LongArray(INITIAL_CAPACITY)
    private var values = IntArray(INITIAL_CAPACITY)
    private var size = 0

    fun append(tNs: Long, value: Int) {
        if (size == this.tNs.size) {
            this.tNs = this.tNs.copyOf(size * 2)
            values = values.copyOf(size * 2)
        }
        this.tNs[size] = tNs
        values[size] = value
        size++
    }

    /** No line is drawn across [tNs]; one already broken there is not broken twice. */
    fun breakAt(tNs: Long) {
        if (size > 0 && values[size - 1] != TrendSnapshot.BREAK) append(tNs, TrendSnapshot.BREAK)
    }

    fun snapshot(): TrendSnapshot = TrendSnapshot(tNs, values, size)

    private companion object {
        const val INITIAL_CAPACITY = 256
    }
}

/**
 * When each of a heart-rate frame's RR intervals ended, on the phone's monotonic clock: the last at
 * [receivedMonoNs], when the frame arrived, and each earlier one an interval before the next. The
 * strap sends no time per interval, so this places them as the frame implies.
 */
fun rrEndTimes(receivedMonoNs: Long, rrMs: List<Int>): LongArray {
    val times = LongArray(rrMs.size)
    var t = receivedMonoNs
    for (i in rrMs.indices.reversed()) {
        times[i] = t
        t -= rrMs[i] * 1_000_000L
    }
    return times
}
