package com.benwrk.jabjotjai.monitor

import kotlin.math.ceil

/**
 * A view of a [TrendSeries] as it stood: its first [size] points, times on the phone's monotonic
 * clock, oldest first, with [BREAK] for a value where a line must not be drawn across, and for each
 * whether the strap had skin [contact] when it sent it.
 */
class TrendSnapshot(val tNs: LongArray, val values: IntArray, val contact: BooleanArray, val size: Int) {
    companion object {
        val EMPTY = TrendSnapshot(LongArray(0), IntArray(0), BooleanArray(0), 0)

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
    private var contact = BooleanArray(INITIAL_CAPACITY)
    private var size = 0

    /** Adds [value] at [tNs], with whether the strap had skin [contact] when it sent it. */
    fun append(tNs: Long, value: Int, contact: Boolean = true) {
        if (size == this.tNs.size) {
            this.tNs = this.tNs.copyOf(size * 2)
            values = values.copyOf(size * 2)
            this.contact = this.contact.copyOf(size * 2)
        }
        this.tNs[size] = tNs
        values[size] = value
        this.contact[size] = contact
        size++
    }

    /** No line is drawn across [tNs]; one already broken there is not broken twice. */
    fun breakAt(tNs: Long) {
        if (size > 0 && values[size - 1] != TrendSnapshot.BREAK) append(tNs, TrendSnapshot.BREAK)
    }

    fun snapshot(): TrendSnapshot = TrendSnapshot(tNs, values, contact, size)

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

/**
 * How a heart-rate or RR row treats values that would mislead it, such as an RR interval of
 * twelve seconds sent while the strap had no skin contact. Only what is drawn changes; the raw file
 * keeps every value.
 *
 * @param robust the scale fits the middle [ROBUST_SHARE] of the values rather than all of them, and
 *   values beyond it are drawn at its edge as clipped
 * @param hideNoContact values the strap sent without skin contact are not drawn; otherwise they are
 *   drawn dimmed. Either way they do not set the scale.
 * @param ceiling values above it are not counted towards the scale, so they are drawn at its top
 *   edge as clipped; null for none
 */
data class TrendFilter(val robust: Boolean = false, val hideNoContact: Boolean = false, val ceiling: Int? = null) {

    /** Whether point [i] of [trend] is drawn at all. */
    fun shows(trend: TrendSnapshot, i: Int): Boolean =
        trend.values[i] != TrendSnapshot.BREAK && (trend.contact[i] || !hideNoContact)

    /** Whether point [i] of [trend], if shown, counts towards the scale. */
    fun scales(trend: TrendSnapshot, i: Int): Boolean =
        shows(trend, i) && trend.contact[i] && (ceiling == null || trend.values[i] <= ceiling)

    companion object {
        /** The share of the values a robust scale fits: all but the lowest and highest percent. */
        const val ROBUST_SHARE = 0.98
    }
}

/**
 * The scale for the values of [trends] after [fromNs] and up to [toNs] that [filter] counts: their
 * lowest and highest, or with [TrendFilter.robust] those of the middle [TrendFilter.ROBUST_SHARE] of
 * them, rounded out to multiples of [step], and at least [minRange] apart, widened evenly about
 * their middle, so a steady value still has room above and below. Where the filter counts none of
 * the values shown, all those shown set it instead, so something is still drawn. Null if there are
 * none.
 */
fun trendRange(
    trends: List<TrendSnapshot>,
    fromNs: Long,
    toNs: Long,
    step: Int,
    minRange: Int,
    filter: TrendFilter = TrendFilter(),
): Pair<Int, Int>? {
    fun valuesWhere(counts: (TrendSnapshot, Int) -> Boolean): IntArray {
        var found = IntArray(64)
        var size = 0
        for (trend in trends) {
            for (i in 0 until trend.size) {
                if (trend.tNs[i] !in (fromNs + 1)..toNs || !counts(trend, i)) continue
                if (size == found.size) found = found.copyOf(size * 2)
                found[size++] = trend.values[i]
            }
        }
        return found.copyOf(size)
    }
    val values = valuesWhere(filter::scales).takeIf { it.isNotEmpty() } ?: valuesWhere(filter::shows)
    if (values.isEmpty()) return null
    values.sort()
    // Rounded up, so a short window with a stray value still leaves it out; never past the middle.
    val cut = if (filter.robust) minOf(ceil((1 - TrendFilter.ROBUST_SHARE) / 2 * values.size).toInt(), (values.size - 1) / 2) else 0
    val low = values[cut]
    val high = values[values.size - 1 - cut]
    var bottom = Math.floorDiv(low, step) * step
    var top = -Math.floorDiv(-high, step) * step
    if (top - bottom < minRange) {
        val middle = (bottom + top) / 2
        bottom = Math.floorDiv(middle - minRange / 2, step) * step
        top = bottom + -Math.floorDiv(-minRange, step) * step
    }
    return bottom to top
}
