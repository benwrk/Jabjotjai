package com.benwrk.jabjotjai.monitor

import com.benwrk.jabjotjai.raw.AccRecord
import com.benwrk.jabjotjai.raw.EcgRecord
import com.benwrk.jabjotjai.raw.EndRecord
import com.benwrk.jabjotjai.raw.EventRecord
import com.benwrk.jabjotjai.raw.GapRecord
import com.benwrk.jabjotjai.raw.HrRecord
import com.benwrk.jabjotjai.raw.RawEvents
import com.benwrk.jabjotjai.raw.RawLine
import com.benwrk.jabjotjai.raw.RawStreams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorModelTest {

    private fun MonitorModel.feed(vararg records: com.benwrk.jabjotjai.raw.RawRecord) = records.forEach { accept(RawLine.Record(it)) }

    @Test
    fun keepsOnlyTheLastStretchOfEachWaveformBySensorTime() {
        val model = MonitorModel(keepNs = 100)
        model.feed(
            EcgRecord(0, 1_000, listOf(10, 20, 30), listOf(1, 2, 3)),
            EcgRecord(0, 2_000, listOf(100, 125), listOf(4, 5)),
        )
        val ecg = model.snapshot().ecg
        // 125 - 100 = 25, so 10 and 20 are older than the window; 30 and later stay.
        assertArrayEquals(longArrayOf(30, 100, 125), ecg.tNs)
        assertArrayEquals(intArrayOf(3, 4, 5), ecg.values)
        assertEquals(FrameTiming(lastSampleTNs = 125, receivedMonoNs = 2_000), model.snapshot().ecgTiming)
    }

    @Test
    fun aSensorClockThatGoesBackStartsTheTraceAfresh() {
        val model = MonitorModel(keepNs = 1_000)
        model.feed(
            EcgRecord(0, 1, listOf(500, 510), listOf(1, 2)),
            EcgRecord(0, 2, listOf(20, 30), listOf(3, 4)),
        )
        assertArrayEquals(longArrayOf(20, 30), model.snapshot().ecg.tNs)
    }

    @Test
    fun splitsAccelerometerFramesIntoTheirThreeAxes() {
        val model = MonitorModel()
        model.feed(AccRecord(0, 7, listOf(1, 2), listOf(-1, -2), listOf(998, 999), listOf(40, 41)))
        val snapshot = model.snapshot()
        assertArrayEquals(intArrayOf(-1, -2), snapshot.accX.values)
        assertArrayEquals(intArrayOf(998, 999), snapshot.accY.values)
        assertArrayEquals(intArrayOf(40, 41), snapshot.accZ.values)
        assertEquals(FrameTiming(2, 7), snapshot.accTiming)
    }

    @Test
    fun showsHeartRateAsTheStrapReportedIt() {
        val model = MonitorModel()
        model.feed(HrRecord(0, 9, bpm = 58, rr1024 = listOf(1060), rrMs = listOf(1035), rrAvailable = true, contact = true, contactSupported = true, correctedBpm = 0, ppgQuality = 0))
        assertEquals(HeartRate(bpm = 58, lastRrMs = 1035, contact = true, receivedMonoNs = 9), model.snapshot().heartRate)
    }

    @Test
    fun contactIsUnknownWhenTheStrapDoesNotReportIt() {
        val model = MonitorModel()
        model.feed(HrRecord(0, 9, 58, emptyList(), emptyList(), rrAvailable = false, contact = false, contactSupported = false, correctedBpm = 0, ppgQuality = 0))
        assertNull(model.snapshot().heartRate!!.contact)
        assertNull(model.snapshot().heartRate!!.lastRrMs)
    }

    @Test
    fun tracksConnectionBatteryGapsAndLinesItCouldNotUse() {
        val model = MonitorModel()
        model.feed(
            EventRecord(0, 1, RawEvents.CONNECTED),
            EventRecord(0, 2, RawEvents.BATTERY, levelPct = 87),
            EventRecord(0, 3, RawEvents.DISCONNECTED, reason = "CONNECTION_LOST"),
            GapRecord(RawStreams.ECG, RawEvents.DISCONNECTED, fromMonoNs = 1, toMonoNs = 4),
        )
        model.accept(RawLine.Unknown("annotation"))
        model.accept(RawLine.Malformed("{", "bad"))
        val snapshot = model.snapshot()
        assertEquals(RawEvents.DISCONNECTED, snapshot.connection)
        assertEquals(87, snapshot.batteryPercentage)
        assertEquals(1, snapshot.gaps)
        assertEquals(1, snapshot.unknownLines)
        assertEquals(1, snapshot.malformedLines)
    }

    @Test
    fun notesTheEndOfTheFile() {
        val model = MonitorModel()
        model.feed(EndRecord(0, 1, "user_stop"))
        assertTrue(model.snapshot().ended)
    }

    private fun hr(monoNs: Long, vararg rr1024: Int, rrAvailable: Boolean = true) = HrRecord(
        wallMs = 0,
        monoNs = monoNs,
        bpm = 60,
        rr1024 = rr1024.toList(),
        // Rounded as the SDK rounds, so a beat placed from these would land on whole milliseconds.
        rrMs = rr1024.map { Math.round(it * 1000.0 / 1024).toInt() },
        rrAvailable = rrAvailable,
        contact = true,
        contactSupported = true,
        correctedBpm = 0,
        ppgQuality = 0,
    )

    @Test
    fun beatsArePlacedFromTheIntervalsIn1024thsOfASecondAsSent() {
        val model = MonitorModel()
        model.feed(hr(5_000_000_000L, 1000, 1000))
        // 1000/1024 s is 976.5625 ms; the rounded 977 ms would give 4_023_000_000.
        assertEquals(listOf(4_023_437_500L, 5_000_000_000L), model.snapshot().beats)
    }

    @Test
    fun noBeatsWhenTheStrapSaysItHasNoIntervals() {
        val model = MonitorModel()
        model.feed(hr(5_000_000_000L, 1024, rrAvailable = false))
        assertEquals(emptyList<Long>(), model.snapshot().beats)
    }

    @Test
    fun aDisconnectionOrAHeartRateGapRestartsTheBeatsButAnEcgGapDoesNot() {
        // Continuing the sequence across the break gives 5, 6 and 7 s; restarting it gives 5 and 6.
        val continued = listOf(5_000_000_000L, 6_000_000_000L, 7_000_000_000L)
        val restarted = listOf(5_000_000_000L, 6_000_000_000L)
        val breaks = listOf(
            EventRecord(0, 5_500_000_000L, RawEvents.DISCONNECTED) to restarted,
            GapRecord(RawStreams.HR, RawEvents.DISCONNECTED, fromMonoNs = 5_000_000_000L) to restarted,
            GapRecord(RawStreams.ECG, RawEvents.STREAM_ERROR, fromMonoNs = 5_000_000_000L) to continued,
        )
        for ((brk, expected) in breaks) {
            val model = MonitorModel()
            model.feed(hr(5_000_000_000L, 1024), brk, hr(6_000_000_000L, 1024, 1024))
            assertEquals("after $brk", expected, model.snapshot().beats)
        }
    }

    @Test
    fun aHeartRateIsShownForFiveSecondsAfterItArrives() {
        val model = MonitorModel()
        model.feed(hr(9, 1024))
        val snapshot = model.snapshot()
        assertEquals(60, snapshot.currentHeartRate(nowMonoNs = 9 + HR_STALE_NS - 1)?.bpm)
        assertNull(snapshot.currentHeartRate(nowMonoNs = 9 + HR_STALE_NS))
    }

    @Test
    fun aSnapshotDoesNotChangeWhenMoreSamplesArrive() {
        val model = MonitorModel()
        model.feed(EcgRecord(0, 1, listOf(1, 2), listOf(1, 2)))
        val before = model.snapshot()
        model.feed(EcgRecord(0, 2, (3L..3_000L).toList(), (3..3_000).toList()))
        assertArrayEquals(longArrayOf(1, 2), before.ecg.tNs)
        assertEquals(3_000, model.snapshot().ecg.size)
    }

    @Test
    fun keepsTheHeartRateAndEachRrIntervalAtTheTimeItEnded() {
        val s = 1_000_000_000L
        val model = MonitorModel()
        // One frame at 10 s with two intervals: 1000 ms, then 500 ms.
        model.feed(hr(10 * s, 1024, 512))
        val snapshot = model.snapshot()
        assertEquals(listOf(10 * s), snapshot.heartRateTrend.tNs.take(snapshot.heartRateTrend.size))
        assertEquals(listOf(60), snapshot.heartRateTrend.values.take(snapshot.heartRateTrend.size))
        // The last interval ended when the frame arrived, the one before it 500 ms earlier.
        assertEquals(listOf(10 * s - 500_000_000L, 10 * s), snapshot.rrTrend.tNs.take(snapshot.rrTrend.size))
        assertEquals(listOf(1000, 500), snapshot.rrTrend.values.take(snapshot.rrTrend.size))
    }

    @Test
    fun aHeartRateGapBreaksTheTrendsOnceAndAnEcgGapDoesNot() {
        val s = 1_000_000_000L
        val model = MonitorModel()
        model.feed(hr(1 * s, 1024), GapRecord(RawStreams.ECG, RawEvents.STREAM_ERROR, fromMonoNs = 2 * s), hr(3 * s, 1024))
        model.feed(
            GapRecord(RawStreams.HR, RawEvents.DISCONNECTED, fromMonoNs = 4 * s),
            GapRecord(RawStreams.HR, RawEvents.DISCONNECTED, fromMonoNs = 4 * s),
            hr(6 * s, 1024),
        )
        val trend = model.snapshot().heartRateTrend
        assertEquals(listOf(60, 60, TrendSnapshot.BREAK, 60), trend.values.take(trend.size))
    }
}
