package com.benwrk.jabjotjai.ui.monitor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.monitor.ChartRow
import com.benwrk.jabjotjai.monitor.MAX_CHART_ROWS
import com.benwrk.jabjotjai.monitor.RowRelation
import com.benwrk.jabjotjai.monitor.RowSignal
import com.benwrk.jabjotjai.monitor.SweepSpeed
import com.benwrk.jabjotjai.monitor.TrendMotion
import com.benwrk.jabjotjai.monitor.TrendSpan
import com.benwrk.jabjotjai.monitor.chartGraphs
import com.benwrk.jabjotjai.ui.theme.MonitorColors

/**
 * The chart's rows, top to bottom: what each shows; below a row of the same signal, whether it is
 * separate from it, cascaded from it or joined to it (see `chartGraphs`); and, where a row starts a
 * graph of its own, its sweep speed, or for a heart-rate or RR row its span and whether it sweeps or
 * scrolls. Rows are added at the bottom, showing what the last row shows
 * at its speed, and removed from the bottom.
 */
@Composable
fun RowsEditor(rows: List<ChartRow>, onChange: (List<ChartRow>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.display_rows), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.display_rows_hint), style = MaterialTheme.typography.bodySmall, color = MonitorColors.Secondary)
        rows.forEachIndexed { index, row ->
            val change = { changed: ChartRow -> onChange(rows.replaced(index, changed)) }
            Chips(stringResource(R.string.display_row, index + 1)) {
                for (signal in RowSignal.entries) {
                    FilterChip(selected = row.signal == signal, onClick = { change(row.copy(signal = signal)) }, label = { Text(stringResource(signal.label)) })
                }
            }
            val sameAbove = index > 0 && rows[index - 1].signal == row.signal
            if (sameAbove) {
                Chips("") {
                    for (relation in RowRelation.entries) {
                        FilterChip(selected = row.relation == relation, onClick = { change(row.copy(relation = relation)) }, label = { Text(stringResource(relation.label)) })
                    }
                }
            }
            if ((!sameAbove || row.relation == RowRelation.SEPARATE) && row.signal.trend) {
                Chips("") {
                    for (span in TrendSpan.entries) {
                        FilterChip(
                            selected = row.span == span,
                            onClick = { change(row.copy(span = span)) },
                            label = { Text(stringResource(R.string.display_row_span, span.minutes)) },
                        )
                    }
                }
                Chips("") {
                    for (motion in TrendMotion.entries) {
                        FilterChip(selected = row.motion == motion, onClick = { change(row.copy(motion = motion)) }, label = { Text(stringResource(motion.label)) })
                    }
                }
            } else if (!sameAbove || row.relation == RowRelation.SEPARATE) {
                Chips("") {
                    for (speed in SweepSpeed.entries) {
                        FilterChip(
                            selected = row.speed == speed,
                            onClick = { change(row.copy(speed = speed)) },
                            label = { Text(stringResource(R.string.display_row_speed, plainNumber(speed.mmPerSecond))) },
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    val last = chartGraphs(rows).last()
                    onChange(rows + ChartRow(last.signal, speed = last.speed, span = last.span, motion = last.motion))
                },
                enabled = rows.size < MAX_CHART_ROWS,
            ) { Text(stringResource(R.string.display_row_add)) }
            TextButton(onClick = { onChange(rows.dropLast(1)) }, enabled = rows.size > 1) {
                Text(stringResource(R.string.display_row_remove))
            }
        }
    }
}

/** A line of chips after a fixed-width title, so the chips of every row line up. */
@Composable
private fun Chips(title: String, chips: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, modifier = Modifier.width(56.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = chips)
    }
}

private fun List<ChartRow>.replaced(index: Int, row: ChartRow): List<ChartRow> = toMutableList().also { it[index] = row }

private val RowSignal.label: Int
    get() = when (this) {
        RowSignal.ECG -> R.string.signal_ecg
        RowSignal.ACC -> R.string.signal_acc
        RowSignal.ACC_X -> R.string.signal_acc_x
        RowSignal.ACC_Y -> R.string.signal_acc_y
        RowSignal.ACC_Z -> R.string.signal_acc_z
        RowSignal.HR -> R.string.signal_hr
        RowSignal.RR -> R.string.signal_rr
    }

private val TrendMotion.label: Int
    get() = when (this) {
        TrendMotion.SWEEP -> R.string.display_row_sweep
        TrendMotion.SCROLL -> R.string.display_row_scroll
    }

private val RowRelation.label: Int
    get() = when (this) {
        RowRelation.SEPARATE -> R.string.display_row_separate
        RowRelation.CASCADED -> R.string.display_row_cascaded
        RowRelation.JOINED -> R.string.display_row_joined
    }
