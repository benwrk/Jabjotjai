package com.benwrk.jabjotjai.ui.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.benwrk.jabjotjai.monitor.FrameTiming
import com.benwrk.jabjotjai.monitor.MonitorSnapshot
import com.benwrk.jabjotjai.monitor.Sweep
import com.benwrk.jabjotjai.monitor.TraceSnapshot
import com.benwrk.jabjotjai.ui.theme.MonitorColors

/**
 * One waveform drawn as a bedside-monitor sweep (see [Sweep]). The snapshot, the clock and the
 * lambdas' own state are read only while drawing, so a new frame, a clock tick or a scale change
 * redraws the trace without recomposing the screen.
 *
 * A sample beyond the band's edge is drawn on the edge, and each such stretch is drawn again in
 * [MonitorColors.Clipped], so a peak cut off by the display cannot pass for a flat-topped wave.
 *
 * @param mmPerSecond the sweep speed, read while drawing
 * @param offsetPx how far above the band's centre a sample value is drawn
 * @param cascadeIndex this graph's place in its cascade, from 0 at the top
 * @param cascadeSize how many graphs the cascade folds one sweep across; 1 without a cascade
 */
@Composable
fun SweepTrace(
    snapshot: State<MonitorSnapshot>,
    nowMonoNs: State<Long>,
    trace: (MonitorSnapshot) -> TraceSnapshot,
    timing: (MonitorSnapshot) -> FrameTiming?,
    color: Color,
    pxPerMm: Float,
    mmPerSecond: () -> Float,
    offsetPx: (value: Int) -> Float,
    cascadeIndex: Int = 0,
    cascadeSize: Int = 1,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val current = snapshot.value
        val frameTiming = timing(current) ?: return@Canvas
        val samples = trace(current)
        if (samples.size == 0 || size.width <= 0f) return@Canvas

        // One graph's width of time; a cascade is one sweep that many graphs long (see Sweep.cascadeAcross).
        val rowWindowNs = Sweep.windowNs(size.width / pxPerMm, mmPerSecond())
        val windowNs = rowWindowNs * cascadeSize
        val cursorNs = Sweep.cursorNs(frameTiming, nowMonoNs.value)
        val half = size.height / 2f
        val path = Path()
        val clipped = Path()
        val dotPx = 1.dp.toPx()
        for (run in Sweep.runs(samples.tNs, cursorNs, windowNs, eraseNs = rowWindowNs / ERASE_FRACTION)) {
            var clipStartX = Float.NaN
            var clipEdgeY = Float.NaN
            var lastX = 0f
            var drawing = false
            for (i in run) {
                val along = Sweep.cascadeAcross(samples.tNs[i], rowWindowNs, cascadeSize)
                val row = along.toInt().coerceAtMost(cascadeSize - 1)
                if (row != cascadeIndex) {
                    // In another graph of the cascade: end this stretch here.
                    if (!clipEdgeY.isNaN()) clipped.edge(clipStartX, lastX, clipEdgeY, dotPx)
                    clipEdgeY = Float.NaN
                    drawing = false
                    continue
                }
                val x = (along - row) * size.width
                val unclipped = half - offsetPx(samples.values[i])
                val y = unclipped.coerceIn(0f, size.height)
                if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
                drawing = true

                val edge = if (unclipped != y) y else Float.NaN
                if (edge != clipEdgeY && !clipEdgeY.isNaN()) clipped.edge(clipStartX, lastX, clipEdgeY, dotPx)
                if (!edge.isNaN() && edge != clipEdgeY) clipStartX = x
                clipEdgeY = edge
                lastX = x
            }
            if (!clipEdgeY.isNaN()) clipped.edge(clipStartX, lastX, clipEdgeY, dotPx)
        }
        // The same stroke for both, so a clipped stretch reads as the trace changing colour.
        val stroke = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round)
        drawPath(path, color, style = stroke)
        drawPath(clipped, MonitorColors.Clipped, style = stroke)
    }
}

/** A clipped stretch along the band's edge; one clipped sample still gets a visible mark. */
private fun Path.edge(fromX: Float, toX: Float, y: Float, minLengthPx: Float) {
    moveTo(fromX, y)
    lineTo(maxOf(toX, fromX + minLengthPx), y)
}

/** The blank stretch ahead of the cursor, as a fraction of the sweep: one fortieth. */
internal const val ERASE_FRACTION = 40L
