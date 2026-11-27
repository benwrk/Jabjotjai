package com.benwrk.jabjotjai.monitor

import com.benwrk.jabjotjai.monitor.RowSignal.ACC
import com.benwrk.jabjotjai.monitor.RowSignal.ACC_X
import com.benwrk.jabjotjai.monitor.RowSignal.ECG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartRowsTest {

    @Test
    fun theDefaultIsOneEcgGraphAboveOneAccelerometerGraph() {
        assertEquals(listOf(ChartGraph(ECG, 1, 0), ChartGraph(ACC, 1, 0)), chartGraphs(DEFAULT_CHART_ROWS))
    }

    @Test
    fun joinedRowsAreOneTallerGraph() {
        val rows = listOf(ChartRow(ECG), ChartRow(ECG, joinedAbove = true), ChartRow(ACC))
        assertEquals(listOf(ChartGraph(ECG, 2, 0), ChartGraph(ACC, 1, 0)), chartGraphs(rows))
    }

    @Test
    fun unjoinedRowsOfOneSignalCascadeWithTheNewestAtTheBottom() {
        val rows = listOf(ChartRow(ECG), ChartRow(ECG), ChartRow(ECG), ChartRow(ACC))
        assertEquals(
            listOf(ChartGraph(ECG, 1, 2), ChartGraph(ECG, 1, 1), ChartGraph(ECG, 1, 0), ChartGraph(ACC, 1, 0)),
            chartGraphs(rows),
        )
    }

    @Test
    fun aCascadeCanHoldJoinedGraphs() {
        val rows = listOf(ChartRow(ECG), ChartRow(ECG, joinedAbove = true), ChartRow(ECG))
        assertEquals(listOf(ChartGraph(ECG, 2, 1), ChartGraph(ECG, 1, 0)), chartGraphs(rows))
    }

    @Test
    fun aJoinToADifferentSignalIsIgnored() {
        val rows = listOf(ChartRow(ECG), ChartRow(ACC_X, joinedAbove = true))
        assertEquals(listOf(ChartGraph(ECG, 1, 0), ChartGraph(ACC_X, 1, 0)), chartGraphs(rows))
    }

    @Test
    fun theSameSignalApartIsNotACascade() {
        val rows = listOf(ChartRow(ECG), ChartRow(ACC), ChartRow(ECG))
        assertEquals(listOf(ChartGraph(ECG, 1, 0), ChartGraph(ACC, 1, 0), ChartGraph(ECG, 1, 0)), chartGraphs(rows))
        assertEquals(0, deepestCascade(chartGraphs(rows)))
    }

    @Test
    fun rowsAreKeptAsTextAndReadBack() {
        val rows = listOf(ChartRow(ECG), ChartRow(ECG, joinedAbove = true), ChartRow(ACC_X))
        assertEquals("ECG,+ECG,ACC_X", encodeChartRows(rows))
        assertEquals(rows, decodeChartRows("ECG,+ECG,ACC_X"))
    }

    @Test
    fun textThatIsNotARowListReadsAsNothing() {
        assertNull(decodeChartRows(""))
        assertNull(decodeChartRows("ECG,PPG"))
        assertNull(decodeChartRows(List(MAX_CHART_ROWS + 1) { "ECG" }.joinToString(",")))
    }
}
