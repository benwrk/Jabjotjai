package com.benwrk.jabjotjai.capture

import com.benwrk.jabjotjai.monitor.HR_STALE_NS
import com.benwrk.jabjotjai.monitor.MonitorModel
import com.benwrk.jabjotjai.monitor.MonitorSnapshot
import com.benwrk.jabjotjai.monitor.elapsedText
import com.benwrk.jabjotjai.raw.EventRecord
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.HrRecord
import com.benwrk.jabjotjai.raw.Phone
import com.benwrk.jabjotjai.raw.RAW_SCHEMA
import com.benwrk.jabjotjai.raw.RawEvents
import com.benwrk.jabjotjai.raw.RawLine
import com.benwrk.jabjotjai.raw.Sensor
import com.benwrk.jabjotjai.raw.Stamp
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationContentTest {

    private val second = 1_000_000_000L
    private val startMonoNs = 100 * second

    /** A capture that started at [startMonoNs], connected, reported battery, and sent one heart rate a second later. */
    private fun snapshot(): MonitorSnapshot {
        val model = MonitorModel(keepNs = 0)
        model.setHeader(
            HeaderRecord(
                schema = RAW_SCHEMA,
                session = "20261127T120000Z-0000",
                app = "0.1.0 (1000)",
                sdk = "9.0.0",
                phone = Phone(make = "samsung", model = "SM-S931B", android = "17", api = 37),
                start = Stamp(wallMs = 0, monoNs = startMonoNs),
                sensor = Sensor(id = "A1B2C3D4", name = "Polar H10 A1B2C3D4", address = "00:11:22:33:44:55"),
                sensorEpoch = "2000-01-01",
            ),
        )
        listOf(
            EventRecord(0, startMonoNs, RawEvents.CONNECTED),
            EventRecord(0, startMonoNs, RawEvents.BATTERY, levelPct = 90),
            HrRecord(0, startMonoNs + second, bpm = 72, rr1024 = listOf(850), rrMs = listOf(830), rrAvailable = true, contact = true, contactSupported = true, correctedBpm = 0, ppgQuality = 0),
        ).forEach { model.accept(RawLine.Record(it)) }
        return model.snapshot()
    }

    @Test
    fun withTheScreenOnItShowsTheHeartRateAndTheTimeSinceTheStart() {
        val content = NotificationContent.of(snapshot(), nowMonoNs = startMonoNs + 3_725 * second, readFailed = false, screenOn = true)
        // 3725 s after the start; the heart rate arrived long before, so it is stale by then.
        assertEquals(NotificationContent(bpm = null, elapsedSeconds = 3_725, connection = RawEvents.CONNECTED, gaps = 0, batteryPct = 90, readFailed = false), content)
        assertEquals("1:02:05", elapsedText(content.elapsedSeconds!!))

        val fresh = NotificationContent.of(snapshot(), nowMonoNs = startMonoNs + 2 * second, readFailed = false, screenOn = true)
        assertEquals(72, fresh.bpm)
        assertEquals(2L, fresh.elapsedSeconds)
    }

    @Test
    fun withTheScreenOffItLeavesOutWhatChangesEverySecondAndKeepsTheRest() {
        val content = NotificationContent.of(snapshot(), nowMonoNs = startMonoNs + 2 * second, readFailed = false, screenOn = false)
        assertEquals(NotificationContent(bpm = null, elapsedSeconds = null, connection = RawEvents.CONNECTED, gaps = 0, batteryPct = 90, readFailed = false), content)
    }

    @Test
    fun theHeartRateGoesStaleOnTheMonitorsSchedule() {
        val arrived = startMonoNs + second
        assertEquals(72, NotificationContent.of(snapshot(), arrived + HR_STALE_NS - 1, readFailed = false, screenOn = true).bpm)
        assertEquals(null, NotificationContent.of(snapshot(), arrived + HR_STALE_NS, readFailed = false, screenOn = true).bpm)
    }

    @Test
    fun beforeTheHeaderIsReadThereIsNoElapsedTime() {
        val content = NotificationContent.of(MonitorSnapshot.EMPTY, nowMonoNs = 5 * second, readFailed = true, screenOn = true)
        assertEquals(NotificationContent(bpm = null, elapsedSeconds = null, connection = null, gaps = 0, batteryPct = null, readFailed = true), content)
    }
}
