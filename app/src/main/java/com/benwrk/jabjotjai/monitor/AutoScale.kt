package com.benwrk.jabjotjai.monitor

import kotlin.math.abs

/**
 * Chooses a band's scale from its recent trace, the way a monitor's auto size does, and moves the
 * band to keep the trace in its middle. It only ever picks a step of the band's [ScaleLadder], so
 * the label can always state the gain in use.
 *
 * It looks at the last [LOOKBACK_NS] in one-second bins and ignores the [OUTLIER_BINS] most
 * extreme bins at each end, so one artifact does not shrink the trace for everyone else. It steps
 * down as soon as the trace would not fit, and steps up only once a larger step has fitted for
 * [RAISE_AFTER_NS], so the trace does not pump with every beat.
 *
 * Pure apart from its own state, and not thread-safe.
 */
class AutoScale(private val ladder: ScaleLadder, initial: BandScale = BandScale(ladder.standardIndex, centre = 0)) {

    var scale: BandScale = initial
        private set

    private var largerFitsSinceNs: Long? = null

    /**
     * @param traces the band's traces, all drawn at one scale
     * @param bandMm the band's height on the screen
     * @param nowNs any monotonic clock, used only to time how long a larger step has fitted
     */
    fun update(traces: List<TraceSnapshot>, bandMm: Float, nowNs: Long): BandScale {
        if (bandMm <= 0f) return scale
        val range = robustRange(traces) ?: return scale
        val span = maxOf(range.last - range.first, ladder.minSpanSamples)
        val fitting = ladder.largestFitting(span, bandMm * FILL)

        var index = scale.index
        when {
            fitting < index -> {
                index = fitting
                largerFitsSinceNs = null
            }
            fitting > index -> {
                val since = largerFitsSinceNs ?: nowNs.also { largerFitsSinceNs = it }
                if (nowNs - since >= RAISE_AFTER_NS) {
                    index = fitting
                    largerFitsSinceNs = null
                }
            }
            else -> largerFitsSinceNs = null
        }

        // Moving the band only when the trace has drifted noticeably keeps it still at rest. With
        // the trace filling at most FILL of the band, a drift under RECENTRE_FRACTION cannot clip it.
        val target = range.first + (range.last - range.first) / 2
        val driftMm = abs(target - scale.centre) * ladder.mmPerSample(index)
        val centre = if (driftMm > bandMm * RECENTRE_FRACTION) target else scale.centre

        scale = BandScale(index, centre)
        return scale
    }

    companion object {
        const val LOOKBACK_NS: Long = 8_000_000_000L
        const val BIN_NS: Long = 1_000_000_000L
        const val OUTLIER_BINS: Int = 2
        const val RAISE_AFTER_NS: Long = 8_000_000_000L

        /** The share of the band's height the trace may fill. */
        const val FILL: Float = 0.8f

        /** How far, as a share of the band's height, the trace may drift before the band follows. */
        const val RECENTRE_FRACTION: Float = 0.1f

        /**
         * The lowest and highest value of the last [LOOKBACK_NS] of [traces], each measured back
         * from its own newest sample, after setting aside the [OUTLIER_BINS] bins with the most
         * extreme values at each end. Null if there are no samples.
         */
        fun robustRange(traces: List<TraceSnapshot>): IntRange? {
            val bins = (LOOKBACK_NS / BIN_NS).toInt()
            val lows = IntArray(bins) { Int.MAX_VALUE }
            val highs = IntArray(bins) { Int.MIN_VALUE }
            for (trace in traces) {
                if (trace.size == 0) continue
                val newest = trace.tNs[trace.size - 1]
                for (i in trace.size - 1 downTo 0) {
                    val age = newest - trace.tNs[i]
                    if (age >= LOOKBACK_NS) break
                    val bin = (age / BIN_NS).toInt()
                    val value = trace.values[i]
                    if (value < lows[bin]) lows[bin] = value
                    if (value > highs[bin]) highs[bin] = value
                }
            }
            val filledLows = lows.filter { it != Int.MAX_VALUE }.sorted()
            val filledHighs = highs.filter { it != Int.MIN_VALUE }.sortedDescending()
            if (filledLows.isEmpty()) return null
            // Never set aside more than a minority of the bins, as there are few just after a start.
            val skip = minOf(OUTLIER_BINS, (filledLows.size - 1) / 2)
            val low = filledLows[skip]
            val high = filledHighs[skip]
            return minOf(low, high)..maxOf(low, high)
        }
    }
}
