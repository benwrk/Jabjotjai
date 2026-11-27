package com.benwrk.jabjotjai.capture

import com.benwrk.jabjotjai.monitor.MonitorSnapshot
import com.benwrk.jabjotjai.monitor.currentHeartRate

/**
 * What the capture notification says, taken from the raw file as read so far. Equal contents mean
 * the notification need not be posted again.
 *
 * @param bpm the strap's heart rate, or null if there is none, it is stale, or the screen is off
 * @param rrMs the last RR interval the strap reported, in ms, under the same conditions as [bpm]
 * @param contact whether the strap reports skin contact, or null while unknown or the heart rate is
 *   stale; kept while the screen is off, since it changes rarely
 * @param connection the last connection event, one of the `RawEvents` connection values, or null
 * @param readFailed the reader could not read the file
 */
data class NotificationContent(
    val bpm: Int?,
    val rrMs: Int?,
    val contact: Boolean?,
    val connection: String?,
    val gaps: Int,
    val batteryPercentage: Int?,
    val readFailed: Boolean,
) {
    companion object {
        /** Before anything has been read. */
        val STARTING = NotificationContent(bpm = null, rrMs = null, contact = null, connection = null, gaps = 0, batteryPercentage = null, readFailed = false)

        /**
         * While the screen is off, the heart rate and the RR interval are left out. Both change
         * at every beat, so following them would mean posting every second all night for nobody,
         * and a heart rate left up would show a stale number on an always-on display and for a
         * moment after the screen wakes. The rest changes rarely and is kept.
         */
        fun of(snapshot: MonitorSnapshot, nowMonoNs: Long, readFailed: Boolean, screenOn: Boolean): NotificationContent {
            val heartRate = snapshot.currentHeartRate(nowMonoNs)?.takeIf { screenOn }
            return NotificationContent(
                bpm = heartRate?.bpm,
                rrMs = heartRate?.lastRrMs,
                contact = snapshot.currentHeartRate(nowMonoNs)?.contact,
                connection = snapshot.connection,
                gaps = snapshot.gaps,
                batteryPercentage = snapshot.batteryPercentage,
                readFailed = readFailed,
            )
        }
    }
}
