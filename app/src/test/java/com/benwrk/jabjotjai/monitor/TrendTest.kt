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
        assertEquals(60 to 80, trendRange(listOf(series.snapshot()), fromNs = 0, toNs = 2, step = 10, minRange = 20))
        assertEquals(null, trendRange(listOf(series.snapshot()), fromNs = 10, toNs = 20, step = 10, minRange = 20))
    }

    @Test
    fun aSteadyValueStillGetsTheLeastRangeAroundIt() {
        val series = TrendSeries()
        series.append(1, 72)
        series.append(2, 72)
        assertEquals(50 to 90, trendRange(listOf(series.snapshot()), fromNs = 0, toNs = 2, step = 10, minRange = 40))
    }

    /** RR intervals of 750 to 849 ms, one a time step, as a quiet night gives, with contact. */
    private fun steadyRr(series: TrendSeries, count: Int = 200) = repeat(count) { series.append(it + 1L, 750 + it % 100) }

    @Test
    fun aRobustScaleIsNotSetByAFewOutliers() {
        // As seen on 2026-11-28: two intervals over ten seconds among ordinary ones.
        val series = TrendSeries()
        steadyRr(series)
        series.append(201, 12_142)
        series.append(202, 10_374)
        val all = trendRange(listOf(series.snapshot()), 0, 1_000, step = 100, minRange = 400)
        val robust = trendRange(listOf(series.snapshot()), 0, 1_000, step = 100, minRange = 400, TrendFilter(robust = true))
        // Without it, the scale stretches to the outliers and flattens the rest.
        assertEquals(700 to 12_200, all)
        assertEquals(600 to 1_000, robust)
    }

    @Test
    fun valuesWithoutContactDoNotSetTheScaleWhetherOrNotTheyAreShown() {
        val series = TrendSeries()
        steadyRr(series)
        series.append(201, 12_142, contact = false)
        val shown = TrendFilter(hideNoContact = false)
        assertEquals(600 to 1_000, trendRange(listOf(series.snapshot()), 0, 1_000, step = 100, minRange = 400, shown))
        val snapshot = series.snapshot()
        assertEquals(true, shown.shows(snapshot, 200))
        assertEquals(false, TrendFilter(hideNoContact = true).shows(snapshot, 200))
    }

    @Test
    fun anIntervalAboveTheCeilingDoesNotSetTheScale() {
        // As seen on 2026-11-28, an interval of four seconds the strap sent with skin contact.
        val series = TrendSeries()
        steadyRr(series, count = 20)
        series.append(21, 4_038)
        assertEquals(700 to 4_100, trendRange(listOf(series.snapshot()), 0, 1_000, step = 100, minRange = 400))
        // 750 to 769 ms, widened to the least range of 400 about its middle.
        assertEquals(500 to 900, trendRange(listOf(series.snapshot()), 0, 1_000, step = 100, minRange = 400, TrendFilter(ceiling = 2_000)))
    }

    @Test
    fun whenNoValueCountsTowardsTheScaleTheShownOnesSetIt() {
        // A stretch with no contact at all still draws, dimmed, on a scale of its own.
        val series = TrendSeries()
        series.append(1, 800, contact = false)
        series.append(2, 900, contact = false)
        assertEquals(600 to 1_000, trendRange(listOf(series.snapshot()), 0, 10, step = 100, minRange = 400, TrendFilter()))
        assertEquals(null, trendRange(listOf(series.snapshot()), 0, 10, step = 100, minRange = 400, TrendFilter(hideNoContact = true)))
    }

    @Test
    fun eachValueKeepsItsContactAsTheSeriesGrows() {
        val series = TrendSeries()
        repeat(1_000) { series.append(it.toLong(), 60, contact = it % 3 != 0) }
        val snapshot = series.snapshot()
        assertEquals(List(1_000) { it % 3 != 0 }, snapshot.contact.take(snapshot.size))
    }
}
