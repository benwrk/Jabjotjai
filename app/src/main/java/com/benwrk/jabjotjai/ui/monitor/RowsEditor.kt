package com.benwrk.jabjotjai.ui.monitor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.benwrk.jabjotjai.monitor.RowSignal
import com.benwrk.jabjotjai.ui.theme.MonitorColors

/**
 * The chart's rows, top to bottom: what each shows, and, below a row of the same signal, whether
 * it joins that row into one taller graph or carries the trace on from it (see `chartGraphs`).
 * Rows are added at the bottom, showing what the last row shows, and removed from the bottom.
 */
@Composable
fun RowsEditor(rows: List<ChartRow>, onChange: (List<ChartRow>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.display_rows), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.display_rows_hint), style = MaterialTheme.typography.bodySmall, color = MonitorColors.Secondary)
        rows.forEachIndexed { index, row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.display_row, index + 1), modifier = Modifier.width(56.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (signal in RowSignal.entries) {
                        FilterChip(
                            selected = row.signal == signal,
                            onClick = { onChange(rows.replaced(index, row.copy(signal = signal))) },
                            label = { Text(stringResource(signal.label)) },
                        )
                    }
                }
            }
            if (index > 0 && rows[index - 1].signal == row.signal) {
                Row(Modifier.padding(start = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = row.joinedAbove, onCheckedChange = { joined -> onChange(rows.replaced(index, row.copy(joinedAbove = joined))) })
                    Text(
                        stringResource(if (row.joinedAbove) R.string.display_row_joined else R.string.display_row_cascaded),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onChange(rows + ChartRow(rows.last().signal)) }, enabled = rows.size < MAX_CHART_ROWS) {
                Text(stringResource(R.string.display_row_add))
            }
            TextButton(onClick = { onChange(rows.dropLast(1)) }, enabled = rows.size > 1) {
                Text(stringResource(R.string.display_row_remove))
            }
        }
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
    }
