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
        assertEquals(87, snapshot.batteryPct)
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

    @Test
    fun aSnapshotDoesNotChangeWhenMoreSamplesArrive() {
        val model = MonitorModel()
        model.feed(EcgRecord(0, 1, listOf(1, 2), listOf(1, 2)))
        val before = model.snapshot()
        model.feed(EcgRecord(0, 2, (3L..3_000L).toList(), (3..3_000).toList()))
        assertArrayEquals(longArrayOf(1, 2), before.ecg.tNs)
        assertEquals(3_000, model.snapshot().ecg.size)
    }
}
