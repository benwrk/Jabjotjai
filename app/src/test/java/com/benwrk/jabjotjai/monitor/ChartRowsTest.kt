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
        // Every row keeps its own speed, a joined or cascaded one too. Until this was kept, such a
        // row read back at the default speed, which showed below a row of another signal, where
        // its own speed is the one drawn.
        assertEquals("ECG@MM_12_5,+ECG@MM_25,>ECG@MM_25,ACC_X@MM_6_25", text)
        assertEquals(rows, decodeChartRows(text))
    }

    @Test
    fun aRowBelowAnotherSignalKeepsItsOwnSpeedAcrossARestart() {
        val rows = listOf(ChartRow(ECG), ChartRow(ACC_X, JOINED, MM_6_25), ChartRow(ECG, CASCADED, MM_12_5))
        val readBack = requireNotNull(decodeChartRows(encodeChartRows(rows)))
        assertEquals(rows, readBack)
        assertEquals(chartGraphs(rows), chartGraphs(readBack))
    }

    @Test
    fun aJoinedOrCascadedRowKeptWithoutItsSpeedStillReadsAtTheDefault() {
        // As 0.5 and 0.6 kept it.
        assertEquals(listOf(ChartRow(ECG, speed = MM_12_5), ChartRow(ECG, CASCADED, MM_25)), decodeChartRows("ECG@MM_12_5,>ECG"))
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

    @Test
    fun heartRateAndRrRowsKeepTheirSpanAndMotionAndCascadeLikeWaveforms() {
        val rows = listOf(
            ChartRow(RowSignal.HR, span = TrendSpan.MIN_60, motion = TrendMotion.SCROLL),
            ChartRow(RowSignal.HR, CASCADED, span = TrendSpan.MIN_1),
            ChartRow(RowSignal.RR, span = TrendSpan.MIN_5),
        )
        assertEquals(
            listOf(
                ChartGraph(RowSignal.HR, 1, MM_25, 0, 2, TrendSpan.MIN_60, TrendMotion.SCROLL),
                ChartGraph(RowSignal.HR, 1, MM_25, 1, 2, TrendSpan.MIN_60, TrendMotion.SCROLL),
                ChartGraph(RowSignal.RR, 1, span = TrendSpan.MIN_5),
            ),
            chartGraphs(rows),
        )
        // Heart-rate and RR graphs draw on the capture's whole history, so they need no waveform sweeps kept.
        assertEquals(1, longestCascade(chartGraphs(rows)))
        val text = encodeChartRows(rows)
        // A cascaded row keeps its own span and motion, as a waveform row keeps its speed.
        assertEquals("HR@MIN_60~SCROLL,>HR@MIN_1~SWEEP,RR@MIN_5~SWEEP", text)
        assertEquals(rows, decodeChartRows(text))
    }
}
