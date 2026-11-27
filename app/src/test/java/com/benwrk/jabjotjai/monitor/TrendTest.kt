package com.benwrk.jabjotjai.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendTest {

    @Test
    fun anEarlierSnapshotKeepsWhatItHadWhileTheSeriesGrows() {
        val series = TrendSeries()
        repeat(3) { series.append(it.toLong(), it) }
        val before = series.snapshot()
        // Well past the first arrays, so they are replaced while the snapshot still holds them.
        repeat(1_000) { series.append(it + 3L, it + 3) }
        assertEquals(3, before.size)
        assertEquals(listOf(0, 1, 2), before.values.take(before.size))
        assertEquals(1_003, series.snapshot().size)
        assertEquals(1_002, series.snapshot().values[1_002])
    }

    @Test
    fun aBreakIsWrittenOnceAndNeverBeforeAnyValue() {
        val series = TrendSeries()
        series.breakAt(1)
        assertEquals(0, series.snapshot().size)
        series.append(2, 60)
        series.breakAt(3)
        series.breakAt(4)
        val snapshot = series.snapshot()
        assertEquals(listOf(60, TrendSnapshot.BREAK), snapshot.values.take(snapshot.size))
    }

    @Test
    fun rrIntervalsEndOneAfterAnotherUpToTheFramesArrival() {
        assertEquals(listOf(7_000_000_000L, 7_800_000_000L, 8_500_000_000L), rrEndTimes(8_500_000_000L, listOf(900, 800, 700)).toList())
        assertEquals(emptyList<Long>(), rrEndTimes(1, emptyList()).toList())
    }
}
