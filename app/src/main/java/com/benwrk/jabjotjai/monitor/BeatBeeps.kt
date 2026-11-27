package com.benwrk.jabjotjai.monitor

import android.os.SystemClock
import kotlinx.coroutines.delay

/** A beat noticed later than this after its time is passed over rather than sounded late. */
private const val LATE_BEEP_NS = 150_000_000L

/** How often to look for new beats when none is waiting to sound. */
private const val IDLE_POLL_MS = 20L

/** The longest sleep while a beat is waiting, so a restarted sequence is noticed promptly. */
private const val MAX_SLEEP_MS = 200L

/**
 * Sounds one beep for each beat in [beats] as it comes due, until cancelled. Beats already past
 * when it starts are not sounded. It sleeps until the next beat rather than polling, so leaving it
 * running all night costs little. Call it from a background dispatcher: [BeatBeeper]'s calls block.
 *
 * @param beats the beats to sound, on the phone's monotonic clock, oldest first; read on each pass
 * @throws UnsupportedOperationException if the phone cannot open an audio track
 */
suspend fun beepAtBeats(beats: () -> List<Long>) {
    BeatBeeper().use { beeper ->
        var signalled = SystemClock.elapsedRealtimeNanos()
        while (true) {
            val now = SystemClock.elapsedRealtimeNanos()
            val current = beats()
            val due = current.lastOrNull { it in (signalled + 1)..now }
            if (due != null) {
                if (now - due < LATE_BEEP_NS) beeper.beep()
                signalled = due
            }
            val next = current.firstOrNull { it > maxOf(signalled, now) }
            val sleepMs = if (next == null) {
                IDLE_POLL_MS
            } else {
                ((next - SystemClock.elapsedRealtimeNanos()) / 1_000_000).coerceIn(1, MAX_SLEEP_MS)
            }
            delay(sleepMs)
        }
    }
}
