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
        assertEquals(listOf(ChartGraph(ECG, 1), ChartGraph(ACC, 1)), chartGraphs(DEFAULT_CHART_ROWS))
    }

    @Test
    fun joinedRowsAreOneTallerGraph() {
        val rows = listOf(ChartRow(ECG), ChartRow(ECG, joinedAbove = true), ChartRow(ACC))
        assertEquals(listOf(ChartGraph(ECG, 2), ChartGraph(ACC, 1)), chartGraphs(rows))
    }

    // A cascade was first drawn as each graph showing a whole sweep further back, which gave every
    // graph its own cursor gap; a bedside monitor folds one long sweep across the graphs instead, so
    // each graph only needs its place in the cascade and the cascade's length.
    @Test
    fun unjoinedRowsOfOneSignalCascadeAsOneSweepFoldedTopToBottom() {
        val rows = listOf(ChartRow(ECG), ChartRow(ECG), ChartRow(ECG), ChartRow(ACC))
        assertEquals(
            listOf(ChartGraph(ECG, 1, 0, 3), ChartGraph(ECG, 1, 1, 3), ChartGraph(ECG, 1, 2, 3), ChartGraph(ACC, 1)),
            chartGraphs(rows),
        )
        assertEquals(3, longestCascade(chartGraphs(rows)))
    }

    @Test
    fun aCascadeCanHoldJoinedGraphs() {
        val rows = listOf(ChartRow(ECG), ChartRow(ECG, joinedAbove = true), ChartRow(ECG))
        assertEquals(listOf(ChartGraph(ECG, 2, 0, 2), ChartGraph(ECG, 1, 1, 2)), chartGraphs(rows))
    }

    @Test
    fun aJoinToADifferentSignalIsIgnored() {
        val rows = listOf(ChartRow(ECG), ChartRow(ACC_X, joinedAbove = true))
        assertEquals(listOf(ChartGraph(ECG, 1), ChartGraph(ACC_X, 1)), chartGraphs(rows))
    }

    @Test
    fun theSameSignalApartIsNotACascade() {
        val rows = listOf(ChartRow(ECG), ChartRow(ACC), ChartRow(ECG))
        assertEquals(listOf(ChartGraph(ECG, 1), ChartGraph(ACC, 1), ChartGraph(ECG, 1)), chartGraphs(rows))
        assertEquals(1, longestCascade(chartGraphs(rows)))
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
