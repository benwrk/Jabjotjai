package com.benwrk.jabjotjai.monitor

/**
 * Where the monitor is in the running capture: at live, or at a moment in the past, played at a
 * speed or paused, like a live video stream that can be rewound. All times are on the phone's
 * monotonic clock, the one every raw record's receive time uses.
 *
 * Pure: every change takes the current time as an argument, so it can be tested without a clock.
 */
sealed interface Playback {

    /** Showing what has just arrived. */
    data object Live : Playback

    /**
     * Showing the past: at [anchorNs] of the capture when the real clock read [anchorNowNs], and
     * moving on from there at [speed] unless [paused].
     */
    data class Past(val anchorNs: Long, val anchorNowNs: Long, val speed: Float, val paused: Boolean) : Playback

    companion object {
        /** The speeds offered. Live plays at 1×; slower falls behind it, faster catches up. */
        val SPEEDS = listOf(0.5f, 1f, 2f, 4f, 8f)

        /** Within this of live counts as live, so a catch-up or a seek to the end lands there. */
        const val LIVE_WITHIN_NS: Long = 1_000_000_000L
    }
}

/** The moment of the capture to show at real time [nowNs]. */
fun Playback.positionNs(nowNs: Long): Long = when (this) {
    Playback.Live -> nowNs
    is Playback.Past -> if (paused) anchorNs else anchorNs + ((nowNs - anchorNowNs) * speed.toDouble()).toLong()
}

/** The speed playing at: 1× at live. */
val Playback.speed: Float get() = when (this) {
    Playback.Live -> 1f
    is Playback.Past -> speed
}

/** Whether time is standing still. */
val Playback.paused: Boolean get() = this is Playback.Past && paused

/**
 * Moves to [targetNs], kept between the capture's [startNs] and now; a target within
 * [Playback.LIVE_WITHIN_NS] of now goes to live. Seeking keeps the speed and whether it is paused.
 */
fun Playback.seek(targetNs: Long, startNs: Long, nowNs: Long): Playback {
    if (targetNs >= nowNs - Playback.LIVE_WITHIN_NS) return Playback.Live
    return Playback.Past(targetNs.coerceAtLeast(startNs), nowNs, speed, paused)
}

/** Plays at [newSpeed] from where it is now. Live stays live at 1× or faster, and falls behind slower. */
fun Playback.withSpeed(newSpeed: Float, nowNs: Long): Playback = when (this) {
    Playback.Live -> if (newSpeed >= 1f) Playback.Live else Playback.Past(nowNs, nowNs, newSpeed, paused = false)
    is Playback.Past -> Playback.Past(positionNs(nowNs), nowNs, newSpeed, paused)
}

/** Pauses, or plays on from where it was paused. Pausing live holds the current moment. */
fun Playback.togglePause(nowNs: Long): Playback = when (this) {
    Playback.Live -> Playback.Past(nowNs, nowNs, 1f, paused = true)
    is Playback.Past -> Playback.Past(positionNs(nowNs), nowNs, speed, !paused)
}

/**
 * Live once playback at real time or faster is within a second of live; otherwise unchanged.
 * Slower playback stays in the past even there: 0.5× chosen at live starts at live and falls
 * behind it from then on (`docs/spec.md` 0.2 item 2).
 */
fun Playback.caughtUp(nowNs: Long): Playback =
    if (this is Playback.Past && !paused && speed >= 1f && positionNs(nowNs) >= nowNs - Playback.LIVE_WITHIN_NS) Playback.Live else this

/**
 * Moves the playhead [byNs] from where it is now, back if negative, as [seek] does: kept to the
 * capture, live if it lands within a second of now, and playing or paused as before.
 */
fun Playback.jump(byNs: Long, startNs: Long, nowNs: Long): Playback = seek(positionNs(nowNs) + byNs, startNs, nowNs)

/** How far the jump buttons move the playhead. */
const val JUMP_NS: Long = 15_000_000_000L
