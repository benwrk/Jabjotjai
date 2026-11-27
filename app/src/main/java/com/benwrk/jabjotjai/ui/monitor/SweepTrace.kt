package com.benwrk.jabjotjai.ui.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.benwrk.jabjotjai.monitor.FrameTiming
import com.benwrk.jabjotjai.monitor.MonitorSnapshot
import com.benwrk.jabjotjai.monitor.Sweep
import com.benwrk.jabjotjai.monitor.TraceSnapshot

/**
 * One waveform drawn as a bedside-monitor sweep (see [Sweep]). The snapshot and the clock are
 * read only while drawing, so a new frame or a clock tick redraws the trace without recomposing
 * the screen.
 *
 * @param offsetPx how far above the band's centre a sample value is drawn, given half the band's height
 */
@Composable
fun SweepTrace(
    snapshot: State<MonitorSnapshot>,
    nowMonoNs: State<Long>,
    trace: (MonitorSnapshot) -> TraceSnapshot,
    timing: (MonitorSnapshot) -> FrameTiming?,
    color: Color,
    pxPerMm: Float,
    offsetPx: (value: Int, halfHeightPx: Float) -> Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val current = snapshot.value
        val frameTiming = timing(current) ?: return@Canvas
        val samples = trace(current)
        if (samples.size == 0 || size.width <= 0f) return@Canvas

        val windowNs = Sweep.windowNs(size.width / pxPerMm)
        val cursorNs = Sweep.cursorNs(frameTiming, nowMonoNs.value)
        val half = size.height / 2f
        val path = Path()
        for (run in Sweep.runs(samples.tNs, cursorNs, windowNs, eraseNs = windowNs / ERASE_FRACTION)) {
            for (i in run) {
                val x = Sweep.xFraction(samples.tNs[i], windowNs) * size.width
                val y = (half - offsetPx(samples.values[i], half)).coerceIn(0f, size.height)
                if (i == run.first) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round))
    }
}

/** The blank stretch ahead of the cursor, as a fraction of the sweep: one fortieth. */
private const val ERASE_FRACTION = 40L
