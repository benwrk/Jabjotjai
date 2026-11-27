package com.benwrk.jabjotjai.monitor

import com.benwrk.jabjotjai.raw.GapRecord
import com.benwrk.jabjotjai.raw.RawEvents
import com.benwrk.jabjotjai.raw.RawStreams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureTimelineTest {

    private val s = 1_000_000_000L

    @Test
    fun notesOneLineAStepAndFindsTheLastAtOrBeforeATime() {
        val timeline = CaptureTimeline(stepNs = s)
        // Lines every 0.4 s: only the first of each second is noted.
        for (i in 0..10) timeline.addLine(offset = i * 100L, monoNs = 10 * s + i * 400_000_000L)

        assertNull(timeline.markAtOrBefore(10 * s - 1))
        assertEquals(CaptureTimeline.Mark(10 * s, 0), timeline.markAtOrBefore(10 * s))
        // Noted: 10.0 s (offset 0), 11.2 s (300), 12.4 s (600), 13.6 s (900).
        assertEquals(CaptureTimeline.Mark(11_200_000_000L, 300), timeline.markAtOrBefore(12 * s))
        assertEquals(CaptureTimeline.Mark(12_400_000_000L, 600), timeline.markAtOrBefore(12_400_000_000L))
        assertEquals(CaptureTimeline.Mark(13_600_000_000L, 900), timeline.markAtOrBefore(99 * s))
    }

    @Test
    fun keepsGrowingPastItsFirstCapacity() {
        val timeline = CaptureTimeline(stepNs = s)
        for (i in 0 until 5_000) timeline.addLine(offset = i.toLong(), monoNs = i * s)
        assertEquals(CaptureTimeline.Mark(4_321 * s, 4_321), timeline.markAtOrBefore(4_321 * s + 1))
    }

    @Test
    fun theStateAtATimeIsTheLastChangeAtOrBeforeIt() {
        val timeline = CaptureTimeline()
        timeline.addState(5 * s, CaptureStateAt(RawEvents.CONNECTED, batteryPercentage = 100, gaps = 0))
        timeline.addState(6 * s, CaptureStateAt(RawEvents.CONNECTED, batteryPercentage = 100, gaps = 0))
        timeline.addState(9 * s, CaptureStateAt(RawEvents.DISCONNECTED, batteryPercentage = 100, gaps = 1))

        assertEquals(CaptureStateAt.INITIAL, timeline.stateAt(5 * s - 1))
        assertEquals(CaptureStateAt(RawEvents.CONNECTED, 100, 0), timeline.stateAt(8 * s))
        assertEquals(CaptureStateAt(RawEvents.DISCONNECTED, 100, 1), timeline.stateAt(9 * s))
    }

    @Test
    fun aStateNotedWithAnEarlierTimeStillSortsAfterThePreviousOne() {
        val timeline = CaptureTimeline()
        timeline.addState(10 * s, CaptureStateAt(RawEvents.CONNECTED, 100, 0))
        timeline.addState(9 * s, CaptureStateAt(RawEvents.DISCONNECTED, 100, 1))
        // Nothing was noted before 10 s; from 10 s the later-noted state is the current one.
        assertEquals(CaptureStateAt.INITIAL, timeline.stateAt(9 * s))
        assertEquals(CaptureStateAt(RawEvents.DISCONNECTED, 100, 1), timeline.stateAt(10 * s))
    }

    @Test
    fun aGapIsPlacedAtItsEndOnceClosedAndAtItsStartWhileOpen() {
        val open = GapRecord(RawStreams.ECG, RawEvents.DISCONNECTED, fromMonoNs = 3 * s)
        assertEquals(3 * s, open.timeMonoNs())
        assertEquals(7 * s, open.copy(toMonoNs = 7 * s).timeMonoNs())
    }
}
