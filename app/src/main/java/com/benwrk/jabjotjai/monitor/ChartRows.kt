package com.benwrk.jabjotjai.monitor

/** What a row of the chart shows: a waveform, or, if [trend], a value plotted over minutes. */
enum class RowSignal(val trend: Boolean = false) {
    ECG,

    /** The accelerometer, X, Y and Z together on one scale. */
    ACC,
    ACC_X,
    ACC_Y,
    ACC_Z,

    /** Heart rate as the strap reported it. */
    HR(trend = true),

    /** Each RR interval the strap reported. */
    RR(trend = true),
}

/** The time across a heart-rate or RR row. */
enum class TrendSpan(val minutes: Int) {
    MIN_1(1),
    MIN_5(5),
    MIN_15(15),
    MIN_30(30),
    MIN_60(60),
    ;

    val ns: Long get() = minutes * 60_000_000_000L
}

/** How a heart-rate or RR row moves: sweeping like the waveforms, or scrolling with the newest value at its right edge. */
enum class TrendMotion { SWEEP, SCROLL }

/** How a row stands to the row above it, when both show the same signal. */
enum class RowRelation {
    /** A graph of its own, at its own speed. */
    SEPARATE,

    /** The trace carries on from the row above: one sweep folded across both. */
    CASCADED,

    /** One taller graph with the row above. */
    JOINED,
}

/**
 * One row of the chart: what it shows, how it stands to the row above, and its sweep speed, or for a
 * heart-rate or RR row its span and motion, which count only where it starts a graph or a cascade. A relation other than [RowRelation.SEPARATE]
 * only counts when the row above shows the same signal.
 */
data class ChartRow(
    val signal: RowSignal,
    val relation: RowRelation = RowRelation.SEPARATE,
    val speed: SweepSpeed = SweepSpeed.MM_25,
    val span: TrendSpan = TrendSpan.MIN_15,
    val motion: TrendMotion = TrendMotion.SWEEP,
)

/**
 * One graph of the chart, [rows] tall, sweeping at [speed], or for a heart-rate or RR graph across
 * [span] as [motion] says. Graphs in a cascade are one sweep
 * [cascadeSize] graphs long, folded across them top to bottom, as a bedside monitor cascades its
 * ECG: the trace runs on from each graph into the next and one cursor moves through them all. This
 * graph is number [cascadeIndex] of its cascade, from 0 at the top.
 */
data class ChartGraph(
    val signal: RowSignal,
    val rows: Int,
    val speed: SweepSpeed = SweepSpeed.MM_25,
    val cascadeIndex: Int = 0,
    val cascadeSize: Int = 1,
    val span: TrendSpan = TrendSpan.MIN_15,
    val motion: TrendMotion = TrendMotion.SWEEP,
)

/**
 * The graphs [rows] make: a joined row makes the graph above it taller, a cascaded row adds a graph
 * to the cascade above it at that cascade's speed, and a separate row, or one whose row above
 * shows another signal, starts a graph of its own at its own speed.
 */
fun chartGraphs(rows: List<ChartRow>): List<ChartGraph> {
    // Each cascade: its signal, its speed and the heights of its graphs, top to bottom. A graph
    // that is not cascaded is a cascade of one.
    class Cascade(val first: ChartRow, val heights: MutableList<Int>)
    val cascades = mutableListOf<Cascade>()
    for (row in rows) {
        val above = cascades.lastOrNull()?.takeIf { it.first.signal == row.signal }
        when {
            above == null || row.relation == RowRelation.SEPARATE -> cascades += Cascade(row, mutableListOf(1))
            row.relation == RowRelation.JOINED -> above.heights[above.heights.lastIndex]++
            else -> above.heights += 1
        }
    }
    return cascades.flatMap { cascade ->
        cascade.heights.mapIndexed { index, height ->
            val first = cascade.first
            ChartGraph(first.signal, height, first.speed, index, cascade.heights.size, first.span, first.motion)
        }
    }
}

/**
 * The most sweeps any waveform cascade of [graphs] spans, so the model can keep that much; heart-rate
 * and RR graphs draw on the capture's whole history instead.
 */
fun longestCascade(graphs: List<ChartGraph>): Int = graphs.filter { !it.signal.trend }.maxOfOrNull { it.cascadeSize } ?: 1

/**
 * The rows a fresh install shows: two ECG rows cascaded into one sweep at 25 mm/s, then heart-rate
 * and RR rows of 15 minutes each, sweeping. Settings kept by an earlier version stay as they were.
 */
val DEFAULT_CHART_ROWS = listOf(
    ChartRow(RowSignal.ECG),
    ChartRow(RowSignal.ECG, RowRelation.CASCADED),
    ChartRow(RowSignal.HR, span = TrendSpan.MIN_15, motion = TrendMotion.SWEEP),
    ChartRow(RowSignal.RR, span = TrendSpan.MIN_15, motion = TrendMotion.SWEEP),
)

/** The most rows the chart takes. */
const val MAX_CHART_ROWS = 6

/**
 * The rows as kept in settings, comma separated: each row's signal, after `>` if cascaded or `+`
 * if joined, and after `@` its speed, or for a heart-rate or RR row its span and, after `~`, its
 * motion, such as `ECG@MM_25,>ECG@MM_25,ACC@MM_12_5,HR@MIN_15~SWEEP`. A cascaded or joined row keeps
 * its own setting too, because below a row of another signal it starts a graph of its own at that
 * setting (see [chartGraphs]).
 */
fun encodeChartRows(rows: List<ChartRow>): String = rows.joinToString(",") { row ->
    val relation = when (row.relation) {
        RowRelation.SEPARATE -> ""
        RowRelation.CASCADED -> ">"
        RowRelation.JOINED -> "+"
    }
    val setting = if (row.signal.trend) "${row.span.name}~${row.motion.name}" else row.speed.name
    "$relation${row.signal.name}@$setting"
}

/**
 * The rows [text] keeps, or null if it is not a list [encodeChartRows] would write. Text without
 * any `@` was written by 0.4, which had one speed, given as [legacySpeed], and cascaded every
 * unjoined row below another of its signal; it is read that way, so a layout set in 0.4 stays.
 */
fun decodeChartRows(text: String, legacySpeed: SweepSpeed = SweepSpeed.MM_25): List<ChartRow>? {
    val legacy = '@' !in text
    var previous: RowSignal? = null
    val rows = text.split(",").map { part ->
        val relation = when {
            part.startsWith("+") -> RowRelation.JOINED
            part.startsWith(">") -> RowRelation.CASCADED
            legacy && part.removePrefix("+").let { name -> RowSignal.entries.any { it.name == name && it == previous } } -> RowRelation.CASCADED
            else -> RowRelation.SEPARATE
        }
        val body = part.removePrefix("+").removePrefix(">")
        val signal = RowSignal.entries.firstOrNull { it.name == body.substringBefore('@') } ?: return null
        val setting = body.substringAfter('@', "")
        val speed = if (legacy) legacySpeed else SweepSpeed.entries.firstOrNull { it.name == setting } ?: legacySpeed
        val span = TrendSpan.entries.firstOrNull { it.name == setting.substringBefore('~') } ?: TrendSpan.MIN_15
        val motion = TrendMotion.entries.firstOrNull { it.name == setting.substringAfter('~', "") } ?: TrendMotion.SWEEP
        previous = signal
        ChartRow(signal, relation, speed, span, motion)
    }
    return rows.takeIf { it.size in 1..MAX_CHART_ROWS }
}
