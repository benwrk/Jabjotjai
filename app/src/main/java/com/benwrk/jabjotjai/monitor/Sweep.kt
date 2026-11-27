package com.benwrk.jabjotjai.monitor

/**
 * The geometry of a bedside-monitor sweep. Each sample is placed by its sensor time modulo the
 * sweep window, so the trace writes left to right and wraps; a blank stretch just ahead of the
 * cursor erases the previous pass. Pure, so it can be tested without a screen.
 */
object Sweep {

    /** The conventional ECG paper speed. */
    const val MM_PER_SECOND: Float = 25f

    /** The conventional ECG gain: 10 mm per millivolt. */
    const val MM_PER_MICROVOLT: Float = 10f / 1000f

    /**
     * How far behind the newest sample the cursor runs. Frames arrive in bursts, so drawing each
     * burst at its arrival would jump; running a little behind lets samples be revealed at the pace
     * they were measured.
     */
    const val DISPLAY_LATENCY_NS: Long = 1_000_000_000L

    /** Consecutive samples further apart than this are not joined: a gap is drawn as a break. */
    const val MAX_JOIN_NS: Long = 100_000_000L

    /** How long a sweep across [widthMm] lasts at [MM_PER_SECOND]. */
    fun windowNs(widthMm: Float): Long = (widthMm.toDouble() / MM_PER_SECOND * 1e9).toLong()

    /**
     * The sensor time the cursor is at: the newest sample's time plus the time elapsed since its
     * frame arrived, less [latencyNs], and never ahead of the newest sample.
     */
    fun cursorNs(timing: FrameTiming, nowMonoNs: Long, latencyNs: Long = DISPLAY_LATENCY_NS): Long =
        minOf(timing.lastSampleTNs, timing.lastSampleTNs + (nowMonoNs - timing.receivedMonoNs) - latencyNs)

    /** Where a sample at [tNs] falls across the window, from 0 (left edge) to just under 1. */
    fun xFraction(tNs: Long, windowNs: Long): Float = Math.floorMod(tNs, windowNs).toFloat() / windowNs

    /**
     * The index ranges of [tNs] (oldest first) to draw as connected lines: samples after the
     * cursor's erase gap and up to the cursor, split wherever the sweep wraps to the left edge and
     * wherever consecutive samples are more than [maxJoinNs] apart.
     */
    fun runs(tNs: LongArray, cursorNs: Long, windowNs: Long, eraseNs: Long, maxJoinNs: Long = MAX_JOIN_NS): List<IntRange> {
        val earliest = cursorNs - windowNs + eraseNs
        val runs = ArrayList<IntRange>()
        var runStart = -1
        var previous = -1
        for (i in tNs.indices) {
            val t = tNs[i]
            if (t <= earliest || t > cursorNs) continue
            val breaks = previous < 0 ||
                previous != i - 1 ||
                t - tNs[previous] > maxJoinNs ||
                Math.floorMod(t, windowNs) < Math.floorMod(tNs[previous], windowNs)
            if (breaks) {
                if (runStart >= 0) runs.add(runStart..previous)
                runStart = i
            }
            previous = i
        }
        if (runStart >= 0) runs.add(runStart..previous)
        return runs
    }
}
