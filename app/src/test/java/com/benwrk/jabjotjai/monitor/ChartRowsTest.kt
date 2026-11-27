package com.benwrk.jabjotjai.monitor

import com.benwrk.jabjotjai.monitor.RowRelation.CASCADED
import com.benwrk.jabjotjai.monitor.RowRelation.JOINED
import com.benwrk.jabjotjai.monitor.RowSignal.ACC
import com.benwrk.jabjotjai.monitor.RowSignal.ACC_X
import com.benwrk.jabjotjai.monitor.RowSignal.ECG
import com.benwrk.jabjotjai.monitor.SweepSpeed.MM_12_5
import com.benwrk.jabjotjai.monitor.SweepSpeed.MM_25
import com.benwrk.jabjotjai.monitor.SweepSpeed.MM_6_25
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartRowsTest {

    @Test
    fun theDefaultIsOneEcgGraphAboveOneAccelerometerGraph() {
        assertEquals(listOf(ChartGraph(ECG, 1), ChartGraph(ACC, 1)), chartGraphs(DEFAULT_CHART_ROWS))
    }

    @Test
    fun aJoinedRowMakesTheGraphAboveTaller() {
        val rows = listOf(ChartRow(ECG), ChartRow(ECG, JOINED), ChartRow(ACC))
        assertEquals(listOf(ChartGraph(ECG, 2), ChartGraph(ACC, 1)), chartGraphs(rows))
    }

    // 0.4 drew a cascade as each graph a whole sweep further back, which gave every graph its own
    // cursor gap; a bedside monitor folds one long sweep across the graphs, so each graph needs
    // only its place in the cascade and the cascade's length.
    @Test
    fun cascadedRowsAreOneSweepFoldedTopToBottomAtTheSpeedOfTheFirst() {
        val rows = listOf(ChartRow(ECG, speed = MM_12_5), ChartRow(ECG, CASCADED, MM_25), ChartRow(ECG, CASCADED), ChartRow(ACC))
        assertEquals(
            listOf(
                ChartGraph(ECG, 1, MM_12_5, 0, 3),
                ChartGraph(ECG, 1, MM_12_5, 1, 3),
                ChartGraph(ECG, 1, MM_12_5, 2, 3),
                ChartGraph(ACC, 1),
            ),
            chartGraphs(rows),
        )
        assertEquals(3, longestCascade(chartGraphs(rows)))
    }

    @Test
    fun aCascadeCanHoldJoinedGraphs() {
        val rows = listOf(ChartRow(ECG), ChartRow(ECG, JOINED), ChartRow(ECG, CASCADED))
        assertEquals(listOf(ChartGraph(ECG, 2, MM_25, 0, 2), ChartGraph(ECG, 1, MM_25, 1, 2)), chartGraphs(rows))
    }

    @Test
    fun aSeparateRowOfTheSameSignalIsItsOwnGraphAtItsOwnSpeed() {
        val rows = listOf(ChartRow(ECG, speed = MM_25), ChartRow(ECG, speed = MM_6_25))
        assertEquals(listOf(ChartGraph(ECG, 1, MM_25), ChartGraph(ECG, 1, MM_6_25)), chartGraphs(rows))
        assertEquals(1, longestCascade(chartGraphs(rows)))
    }

    @Test
    fun aRelationToADifferentSignalIsIgnored() {
        val rows = listOf(ChartRow(ECG), ChartRow(ACC_X, JOINED, MM_6_25), ChartRow(ECG, CASCADED, MM_12_5))
        assertEquals(listOf(ChartGraph(ECG, 1), ChartGraph(ACC_X, 1, MM_6_25), ChartGraph(ECG, 1, MM_12_5)), chartGraphs(rows))
    }

    @Test
    fun rowsAreKeptAsTextAndReadBack() {
        val rows = listOf(ChartRow(ECG, speed = MM_12_5), ChartRow(ECG, JOINED), ChartRow(ECG, CASCADED), ChartRow(ACC_X, speed = MM_6_25))
        val text = encodeChartRows(rows)
        assertEquals("ECG@MM_12_5,+ECG,>ECG,ACC_X@MM_6_25", text)
        // A joined or cascaded row keeps no speed of its own, so it reads back at the default.
        assertEquals(listOf(rows[0], rows[1].copy(speed = MM_25), rows[2].copy(speed = MM_25), rows[3]), decodeChartRows(text))
    }

    @Test
    fun rowsKeptBy04CascadeAsTheyDidThenAtThatVersionsOneSpeed() {
        assertEquals(
            listOf(ChartRow(ECG, speed = MM_12_5), ChartRow(ECG, CASCADED, MM_12_5), ChartRow(ACC, speed = MM_12_5)),
            decodeChartRows("ECG,ECG,ACC", legacySpeed = MM_12_5),
        )
        assertEquals(
            listOf(ChartRow(ECG, speed = MM_12_5), ChartRow(ECG, JOINED, MM_12_5), ChartRow(ACC, speed = MM_12_5)),
            decodeChartRows("ECG,+ECG,ACC", legacySpeed = MM_12_5),
        )
    }

    @Test
    fun textThatIsNotARowListReadsAsNothing() {
        assertNull(decodeChartRows(""))
        assertNull(decodeChartRows("ECG,PPG"))
        assertNull(decodeChartRows(List(MAX_CHART_ROWS + 1) { "ECG" }.joinToString(",")))
    }
}
