package com.benwrk.jabjotjai.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrendReadoutTest {

    private val s = 1_000_000_000L

    private fun graph(motion: TrendMotion, cascadeIndex: Int = 0, cascadeSize: Int = 1) =
        ChartGraph(RowSignal.HR, 1, cascadeIndex = cascadeIndex, cascadeSize = cascadeSize, span = TrendSpan.MIN_1, motion = motion)

    @Test
    fun aScrollingGraphShowsTheNewestTimeAtItsRightEdge() {
        val scroll = graph(TrendMotion.SCROLL)
        assertEquals(600 * s, trendTimeAt(1f, 600 * s, scroll))
        assertEquals(570 * s, trendTimeAt(0.5f, 600 * s, scroll))
        assertEquals(540 * s, trendTimeAt(0f, 600 * s, scroll))
    }

    @Test
    fun aSweepShowsThisSweepLeftOfItsCursorAndTheOneBeforeRightOfIt() {
        // 90 s into one-minute sweeps, the cursor is halfway across.
        val sweep = graph(TrendMotion.SWEEP)
        assertEquals(75 * s, trendTimeAt(0.25f, 90 * s, sweep))
        assertEquals(45 * s, trendTimeAt(0.75f, 90 * s, sweep))
    }

    @Test
    fun aCascadedSweepReadsAcrossItsRowsAsOneStrip() {
        // Two one-minute rows, 150 s in: the cursor is halfway across the top row.
        assertEquals(135 * s, trendTimeAt(0.25f, 150 * s, graph(TrendMotion.SWEEP, cascadeIndex = 0, cascadeSize = 2)))
        assertEquals(90 * s, trendTimeAt(0.5f, 150 * s, graph(TrendMotion.SWEEP, cascadeIndex = 1, cascadeSize = 2)))
    }

    @Test
    fun aTimeReadOffASweepIsWhereTheGraphDrawsIt() {
        val sweep = graph(TrendMotion.SWEEP, cascadeIndex = 1, cascadeSize = 3)
        val now = 1_000 * s + 123_456_789L
        for (t in listOf(now - s, now - 20 * s, now - 40 * s)) {
            val along = Sweep.cascadeAcross(t, TrendSpan.MIN_1.ns, 3)
            assertEquals(1, along.toInt())
            // Within 50 microseconds: a position across the graph is a float.
            val read = trendTimeAt(along - 1, now, sweep)
            assertEquals(t.toDouble(), read.toDouble(), 50_000.0)
        }
    }

    private fun trend(vararg points: Pair<Long, Int>): TrendSnapshot {
        val series = TrendSeries()
        for ((t, value) in points) if (value == TrendSnapshot.BREAK) series.breakAt(t) else series.append(t, value)
        return series.snapshot()
    }

    @Test
    fun theNearestValueWithinReachIsRead() {
        val trend = trend(10 * s to 60, 11 * s to 61, 13 * s to 63)
        assertEquals(TrendPoint(11 * s, 61), nearestTrendPoint(listOf(trend), 11 * s + 400_000_000, 0, 20 * s, withinNs = s))
        assertEquals(TrendPoint(13 * s, 63), nearestTrendPoint(listOf(trend), 12 * s + 600_000_000, 0, 20 * s, withinNs = s))
        // Nothing within reach.
        assertNull(nearestTrendPoint(listOf(trend), 16 * s, 0, 20 * s, withinNs = s))
    }

    @Test
    fun aBreakAndAValueOutsideTheShownStretchAreNotRead() {
        val trend = trend(10 * s to 60, 12 * s to TrendSnapshot.BREAK, 14 * s to 64)
        assertEquals(TrendPoint(10 * s, 60), nearestTrendPoint(listOf(trend), 11_900_000_000, 0, 20 * s, withinNs = 3 * s))
        assertNull(nearestTrendPoint(listOf(trend), 10 * s, fromNs = 10 * s, toNs = 13 * s, withinNs = s))
    }

    @Test
    fun theEarlierFilesHistoryIsReadAsWellAsTheCurrentOne() {
        val earlier = trend(5 * s to 55)
        val current = trend(20 * s to 70)
        assertEquals(TrendPoint(5 * s, 55), nearestTrendPoint(listOf(earlier, current), 6 * s, 0, 30 * s, withinNs = 2 * s))
    }
}
