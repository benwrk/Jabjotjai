package com.benwrk.jabjotjai.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class SweepTest {

    private val window = 1_000L
    private val noErase = 0L

    @Test
    fun aContinuousTraceIsOneRun() {
        val t = longArrayOf(100, 110, 120, 130)
        assertEquals(listOf(0..3), Sweep.runs(t, cursorNs = 130, windowNs = window, eraseNs = noErase, maxJoinNs = 50))
    }

    @Test
    fun samplesAfterTheCursorAreNotDrawnYet() {
        val t = longArrayOf(100, 110, 120, 130)
        assertEquals(listOf(0..1), Sweep.runs(t, cursorNs = 115, windowNs = window, eraseNs = noErase, maxJoinNs = 50))
    }

    @Test
    fun samplesOlderThanOneWindowAndTheEraseGapAreNotDrawn() {
        val t = longArrayOf(100, 200, 300, 1_150, 1_160)
        // The window ends at 1160 and starts 1000 earlier, plus a 100 erase gap: only 300 and later remain.
        assertEquals(listOf(2..2, 3..4), Sweep.runs(t, cursorNs = 1_160, windowNs = window, eraseNs = 100, maxJoinNs = 2_000))
    }

    @Test
    fun theLineBreaksWhereTheSweepWrapsToTheLeftEdge() {
        val t = longArrayOf(980, 990, 1_000, 1_010)
        assertEquals(listOf(0..1, 2..3), Sweep.runs(t, cursorNs = 1_010, windowNs = window, eraseNs = noErase, maxJoinNs = 50))
    }

    @Test
    fun aGapInTheDataIsDrawnAsABreakNotJoinedAcross() {
        val t = longArrayOf(100, 110, 400, 410)
        assertEquals(listOf(0..1, 2..3), Sweep.runs(t, cursorNs = 410, windowNs = window, eraseNs = noErase, maxJoinNs = 50))
    }

    @Test
    fun theCursorRunsBehindTheNewestSampleAndCatchesUpWithoutPassingIt() {
        val timing = FrameTiming(lastSampleTNs = 10_000, receivedMonoNs = 500)
        assertEquals(9_000, Sweep.cursorNs(timing, nowMonoNs = 500, latencyNs = 1_000))
        assertEquals(9_400, Sweep.cursorNs(timing, nowMonoNs = 900, latencyNs = 1_000))
        assertEquals(10_000, Sweep.cursorNs(timing, nowMonoNs = 5_000, latencyNs = 1_000))
    }

    @Test
    fun xIsTheSensorTimeModuloTheWindow() {
        assertEquals(0.25f, Sweep.xFraction(1_250, window), 0f)
        assertEquals(0f, Sweep.xFraction(2_000, window), 0f)
    }

    @Test
    fun aSweepAcross150MillimetresLastsSixSecondsAt25AndThreeAt50() {
        assertEquals(6_000_000_000L, Sweep.windowNs(150f, mmPerSecond = 25f))
        assertEquals(3_000_000_000L, Sweep.windowNs(150f, mmPerSecond = 50f))
    }

    @Test
    fun theSlowestSweepAcrossALargeTabletFitsInWhatTheModelKeeps() {
        assertEquals(true, Sweep.windowNs(250f, SweepSpeed.MM_6_25.mmPerSecond) <= MonitorModel.DEFAULT_KEEP_NS)
    }
}
