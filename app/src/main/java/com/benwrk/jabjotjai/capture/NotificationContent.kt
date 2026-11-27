package com.benwrk.jabjotjai.capture

import com.benwrk.jabjotjai.monitor.MonitorSnapshot
import com.benwrk.jabjotjai.monitor.currentHeartRate
import com.benwrk.jabjotjai.monitor.elapsedSeconds

/**
 * What the capture notification says, taken from the raw file as read so far. Equal contents mean
 * the notification need not be posted again.
 *
 * @param bpm the strap's heart rate, or null if there is none, it is stale, or the screen is off
 * @param elapsedSeconds time since the capture started, or null if unknown or the screen is off
 * @param connection the last connection event, one of the `RawEvents` connection values, or null
 * @param readFailed the reader could not read the file
 */
data class NotificationContent(
    val bpm: Int?,
    val elapsedSeconds: Long?,
    val connection: String?,
    val gaps: Int,
    val batteryPct: Int?,
    val readFailed: Boolean,
) {
    companion object {
        /** Before anything has been read. */
        val STARTING = NotificationContent(bpm = null, elapsedSeconds = null, connection = null, gaps = 0, batteryPct = null, readFailed = false)

        /**
         * While the screen is off, the heart rate and the elapsed time are left out. Both change
         * every second, so following them would mean posting every second all night for nobody,
         * and a heart rate left up would show a stale number on an always-on display and for a
         * moment after the screen wakes. The rest changes rarely and is kept.
         */
        fun of(snapshot: MonitorSnapshot, nowMonoNs: Long, readFailed: Boolean, screenOn: Boolean) = NotificationContent(
            bpm = snapshot.currentHeartRate(nowMonoNs)?.bpm?.takeIf { screenOn },
            elapsedSeconds = snapshot.elapsedSeconds(nowMonoNs)?.takeIf { screenOn },
            connection = snapshot.connection,
            gaps = snapshot.gaps,
            batteryPct = snapshot.batteryPct,
            readFailed = readFailed,
        )
    }
}
