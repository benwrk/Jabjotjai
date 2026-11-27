package com.benwrk.jabjotjai.capture

import com.benwrk.jabjotjai.monitor.MonitorSnapshot
import com.benwrk.jabjotjai.monitor.currentHeartRate

/**
 * What the capture notification says, taken from the raw file as read so far. Equal contents mean
 * the notification need not be posted again.
 *
 * @param bpm the strap's heart rate, or null if there is none or it is stale
 * @param connection the last connection event, one of the `RawEvents` connection values, or null
 * @param readFailed the notification's own reader could not read the file
 */
data class NotificationContent(
    val bpm: Int?,
    val connection: String?,
    val gaps: Int,
    val batteryPct: Int?,
    val readFailed: Boolean,
) {
    companion object {
        /** Before anything has been read. */
        val STARTING = NotificationContent(bpm = null, connection = null, gaps = 0, batteryPct = null, readFailed = false)

        fun of(snapshot: MonitorSnapshot, nowMonoNs: Long, readFailed: Boolean) = NotificationContent(
            bpm = snapshot.currentHeartRate(nowMonoNs)?.bpm,
            connection = snapshot.connection,
            gaps = snapshot.gaps,
            batteryPct = snapshot.batteryPct,
            readFailed = readFailed,
        )
    }
}
