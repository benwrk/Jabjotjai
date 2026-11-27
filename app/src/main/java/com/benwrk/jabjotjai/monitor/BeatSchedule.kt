package com.benwrk.jabjotjai.monitor

/**
 * Lays out the strap's reported beats on the phone's monotonic clock, as the times to beep and
 * flash the heart.
 *
 * The strap does not say when a beat happened. It sends a heart-rate record about once a second
 * carrying the R-R intervals that ended since the last one, one or two at resting rates (measured
 * on the test strap, 2026-11-27). So each beat is placed one interval after the previous one, which
 * keeps the rhythm exactly as the strap measured it, and the whole sequence runs late by however
 * long the reports take to arrive: a beat reported after its slot has passed is moved to its
 * arrival, and every later beat follows from there. The first beat after a start, a gap or a
 * silence is placed at its record's arrival.
 *
 * Nothing here comes from the ECG samples, so a beat is not drawn where the R wave is drawn.
 *
 * Not thread-safe: one reader feeds it.
 */
class BeatSchedule {

    private var lastNs: Long? = null
    private var times = LongArray(16)
    private var count = 0

    /** Forget the running sequence, so the next reported beat starts a new one. */
    fun restart() {
        lastNs = null
    }

    /**
     * @param receivedMonoNs when the heart-rate record arrived
     * @param rrNs the R-R intervals it carried, oldest first
     */
    fun accept(receivedMonoNs: Long, rrNs: List<Long>) {
        if (rrNs.isEmpty()) return
        val previous = lastNs
        if (previous == null || receivedMonoNs - previous > RESTART_AFTER_NS) {
            // Place the newest beat at arrival and the others back from it; those are already past.
            var t = receivedMonoNs
            val placed = LongArray(rrNs.size)
            for (i in rrNs.indices.reversed()) {
                placed[i] = t
                t -= rrNs[i]
            }
            placed.forEach(::add)
            lastNs = receivedMonoNs
            return
        }
        var t: Long = previous
        for (rr in rrNs) {
            t += rr
            // A beat due after this record's arrival by more than MAX_LEAD_NS means the sequence
            // has drifted late against the strap's clock: start it again from the arrival.
            if (t < receivedMonoNs || t > receivedMonoNs + MAX_LEAD_NS) t = receivedMonoNs
            add(t)
        }
        lastNs = t
    }

    /** The beats placed in the last [KEEP_NS] before the newest, oldest first. */
    fun recent(): LongArray {
        val newest = if (count > 0) times[count - 1] else return LongArray(0)
        var first = 0
        while (first < count && times[first] < newest - KEEP_NS) first++
        return times.copyOfRange(first, count)
    }

    /**
     * Keeps the times in order. A beat placed no later than one already kept is dropped: that
     * happens only when a sequence starts again behind beats that are still due, and signalling
     * both would sound a double beat.
     */
    private fun add(t: Long) {
        if (count > 0 && t <= times[count - 1]) return
        if (count == times.size) {
            // Drop what [recent] would no longer return before growing.
            val newest = times[count - 1]
            var first = 0
            while (first < count && times[first] < newest - KEEP_NS) first++
            if (first > 0) {
                times.copyInto(times, 0, first, count)
                count -= first
            } else {
                times = times.copyOf(times.size * 2)
            }
        }
        times[count++] = t
    }

    companion object {
        /** A pause in reported beats this long ends the sequence, so it does not resume late. */
        const val RESTART_AFTER_NS: Long = 3_000_000_000L

        /** The most a beat may be due after the arrival of the record that reported it. */
        const val MAX_LEAD_NS: Long = 2_000_000_000L

        const val KEEP_NS: Long = 10_000_000_000L
    }
}
