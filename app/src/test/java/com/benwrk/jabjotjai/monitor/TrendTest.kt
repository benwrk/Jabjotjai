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

    @Test
    fun theScaleCoversWhatIsShownRoundedOutToTheStep() {
        val series = TrendSeries()
        listOf(1L to 63, 2L to 71, 3L to 300, 4L to 58).forEach { (t, v) -> series.append(t, v) }
        series.breakAt(5)
        // Only the values at 1 and 2: the 300 at 3 and the 58 at 4 come after the window.
        assertEquals(60 to 80, trendRange(series.snapshot(), fromNs = 0, toNs = 2, step = 10, minRange = 20))
        assertEquals(null, trendRange(series.snapshot(), fromNs = 10, toNs = 20, step = 10, minRange = 20))
    }

    @Test
    fun aSteadyValueStillGetsTheLeastRangeAroundIt() {
        val series = TrendSeries()
        series.append(1, 72)
        series.append(2, 72)
        assertEquals(50 to 90, trendRange(series.snapshot(), fromNs = 0, toNs = 2, step = 10, minRange = 40))
    }
}
