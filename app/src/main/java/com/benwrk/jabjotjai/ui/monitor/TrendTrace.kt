package com.benwrk.jabjotjai.ui.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benwrk.jabjotjai.monitor.ChartGraph
import com.benwrk.jabjotjai.monitor.Sweep
import com.benwrk.jabjotjai.monitor.TrendMotion
import com.benwrk.jabjotjai.monitor.TrendSnapshot
import com.benwrk.jabjotjai.monitor.trendRange
import com.benwrk.jabjotjai.ui.theme.MonitorColors
import com.benwrk.jabjotjai.ui.theme.MonitorMono

/**
 * A heart-rate or RR graph: [trends], each a series oldest first, such as the history of the files a
 * capture continues and its own, up to [nowMonoNs], the playhead, across the graph's span. It
 * sweeps like the waveforms, folded across a cascade the same way, or scrolls with the newest value
 * at the right edge. A cascade is read as one strip: its scale covers what the whole strip shows,
 * rounded out to [step], so every graph of it reads alike; the scale's ends are written at the right.
 * Values join into a line, or with [dots] stand alone, one per beat; a line never crosses a break
 * (a gap or a disconnection) or [maxJoinNs] without a value.
 */
@Composable
fun TrendTrace(
    trends: () -> List<TrendSnapshot>,
    nowMonoNs: State<Long>,
    graph: ChartGraph,
    color: Color,
    step: Int,
    minRange: Int,
    dots: Boolean,
    maxJoinNs: Long,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier) {
        val series = trends()
        if (series.all { it.size == 0 } || size.width <= 0f) return@Canvas
        val now = nowMonoNs.value
        val rowNs = graph.span.ns
        val stripNs = rowNs * graph.cascadeSize
        val sweeping = graph.motion == TrendMotion.SWEEP
        // The oldest time the strip shows: a sweep leaves a gap before its cursor, as the waveforms do.
        val earliest = now - stripNs + if (sweeping) rowNs / ERASE_FRACTION else 0L
        val range = trendRange(series, earliest, now, step, minRange) ?: return@Canvas
        val (low, high) = range

        val path = Path()
        val dotRadius = 1.5.dp.toPx()
        for (points in series) {
            var drawing = false
            var lastX = 0f
            var lastT = Long.MIN_VALUE
            for (i in 0 until points.size) {
                val t = points.tNs[i]
                val value = points.values[i]
                if (t <= earliest || t > now || value == TrendSnapshot.BREAK) {
                    drawing = false
                    continue
                }
                // How far along the strip, in graphs: its whole part is the graph, the rest how far across it.
                val along = if (sweeping) Sweep.cascadeAcross(t, rowNs, graph.cascadeSize) else (t - (now - stripNs)).toFloat() / rowNs
                val row = along.toInt().coerceIn(0, graph.cascadeSize - 1)
                if (row != graph.cascadeIndex) {
                    drawing = false
                    continue
                }
                val x = (along - row) * size.width
                val y = size.height * (1f - (value - low).toFloat() / (high - low))
                if (dots) {
                    drawCircle(color, dotRadius, Offset(x, y))
                } else {
                    // A wrap to the left edge, or too long without a value, starts a new line.
                    if (drawing && x >= lastX && t - lastT <= maxJoinNs) path.lineTo(x, y) else path.moveTo(x, y)
                    drawing = true
                }
                lastX = x
                lastT = t
            }
        }
        if (!dots) drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))

        val style = TextStyle(color = MonitorColors.Secondary, fontSize = 10.sp, fontFamily = MonitorMono)
        val top = textMeasurer.measure(high.toString(), style)
        val bottom = textMeasurer.measure(low.toString(), style)
        val margin = 4.dp.toPx()
        drawText(top, topLeft = Offset(size.width - top.size.width - margin, margin))
        drawText(bottom, topLeft = Offset(size.width - bottom.size.width - margin, size.height - bottom.size.height - margin))
    }
}
