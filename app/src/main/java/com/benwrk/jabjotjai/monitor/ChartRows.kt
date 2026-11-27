package com.benwrk.jabjotjai.monitor

/** What a row of the chart shows. */
enum class RowSignal {
    ECG,

    /** The accelerometer, X, Y and Z together on one scale. */
    ACC,
    ACC_X,
    ACC_Y,
    ACC_Z,
}

/**
 * One row of the chart: what it shows, and whether it is joined to the row above into one taller
 * graph, which only counts when the row above shows the same signal.
 */
data class ChartRow(val signal: RowSignal, val joinedAbove: Boolean = false)

/**
 * One graph of the chart, [rows] tall. Graphs of one signal next to each other that are not joined
 * make a cascade, as on a bedside monitor: one sweep [cascadeSize] graphs long, folded across them
 * top to bottom, so the trace runs on from each graph into the next and one cursor moves through
 * them all. This graph is number [cascadeIndex] of its cascade, from 0 at the top.
 */
data class ChartGraph(val signal: RowSignal, val rows: Int, val cascadeIndex: Int = 0, val cascadeSize: Int = 1)

/** The graphs [rows] make: joined rows merged, unjoined neighbours of one signal cascaded. */
fun chartGraphs(rows: List<ChartRow>): List<ChartGraph> {
    // Each run of one signal: the heights of its graphs, top to bottom.
    val runs = mutableListOf<Pair<RowSignal, MutableList<Int>>>()
    for (row in rows) {
        val run = runs.lastOrNull()
        when {
            run == null || run.first != row.signal -> runs += row.signal to mutableListOf(1)
            row.joinedAbove -> run.second[run.second.lastIndex]++
            else -> run.second += 1
        }
    }
    return runs.flatMap { (signal, heights) ->
        heights.mapIndexed { index, rows -> ChartGraph(signal, rows, cascadeIndex = index, cascadeSize = heights.size) }
    }
}

/** The most sweeps any cascade of [graphs] spans, so the model can keep that much. */
fun longestCascade(graphs: List<ChartGraph>): Int = graphs.maxOfOrNull { it.cascadeSize } ?: 1

/** The layout 0.3 had: one ECG row above one accelerometer row. */
val DEFAULT_CHART_ROWS = listOf(ChartRow(RowSignal.ECG), ChartRow(RowSignal.ACC))

/** The most rows the chart takes. */
const val MAX_CHART_ROWS = 6

/**
 * The rows as kept in settings: each row's signal, comma separated, with a `+` before a row joined
 * to the one above, such as `ECG,+ECG,ACC`.
 */
fun encodeChartRows(rows: List<ChartRow>): String =
    rows.joinToString(",") { (if (it.joinedAbove) "+" else "") + it.signal.name }

/** The rows [text] keeps, or null if it is not a list [encodeChartRows] would write. */
fun decodeChartRows(text: String): List<ChartRow>? {
    val rows = text.split(",").map { part ->
        val joined = part.startsWith("+")
        val signal = RowSignal.entries.firstOrNull { it.name == part.removePrefix("+") } ?: return null
        ChartRow(signal, joined)
    }
    return rows.takeIf { it.size in 1..MAX_CHART_ROWS }
}
