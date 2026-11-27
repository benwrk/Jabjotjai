package com.benwrk.jabjotjai.ui.monitor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.monitor.DisplaySettings
import com.benwrk.jabjotjai.monitor.ScaleLadder
import com.benwrk.jabjotjai.monitor.SweepSpeed
import com.benwrk.jabjotjai.ui.theme.MonitorColors
import java.math.BigDecimal

/**
 * The monitor's display controls. A gain step is shown selected whenever it is the one in use,
 * whether the user chose it or auto scaling did; choosing a step turns auto off, and turning auto
 * off keeps the step auto had chosen, so the trace does not jump.
 *
 * @param ecgIndex the ECG step in use now
 * @param accIndex the accelerometer step in use now
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySheet(
    settings: DisplaySettings,
    ecgIndex: Int,
    accIndex: Int,
    onChange: ((DisplaySettings) -> DisplaySettings) -> Unit,
    onDismiss: () -> Unit,
) {
    // Fully open at once: half open, a landscape phone shows only the first row or two.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.display_title), style = MaterialTheme.typography.titleMedium)

            RowsEditor(settings.rows) { rows -> onChange { it.copy(rows = rows) } }

            Choices(stringResource(R.string.display_sweep)) {
                for (speed in SweepSpeed.entries) {
                    FilterChip(
                        selected = settings.sweep == speed,
                        onClick = { onChange { it.copy(sweep = speed) } },
                        label = { Text(plainNumber(speed.mmPerSecond)) },
                    )
                }
            }

            Choices(stringResource(R.string.display_ecg_gain)) {
                FilterChip(
                    selected = settings.ecgAuto,
                    onClick = { onChange { if (it.ecgAuto) it.copy(ecgAuto = false, ecgGainIndex = ecgIndex) else it.copy(ecgAuto = true) } },
                    label = { Text(stringResource(R.string.display_auto)) },
                )
                for (i in 0 until ScaleLadder.ECG.size) {
                    FilterChip(
                        selected = i == ecgIndex,
                        onClick = { onChange { it.copy(ecgAuto = false, ecgGainIndex = i) } },
                        label = { Text(stringResource(R.string.display_gain_step, plainNumber(ecgMultiple(i)))) },
                    )
                }
            }

            Choices(stringResource(R.string.display_acc_scale)) {
                FilterChip(
                    selected = settings.accAuto,
                    onClick = { onChange { if (it.accAuto) it.copy(accAuto = false, accScaleIndex = accIndex) else it.copy(accAuto = true) } },
                    label = { Text(stringResource(R.string.display_auto)) },
                )
                for (i in 0 until ScaleLadder.ACC.size) {
                    FilterChip(
                        selected = i == accIndex,
                        onClick = { onChange { it.copy(accAuto = false, accScaleIndex = i) } },
                        label = { Text(plainNumber(ScaleLadder.ACC.mmPerUnit(i))) },
                    )
                }
            }

            Toggle(
                title = stringResource(R.string.display_pulse),
                hint = stringResource(R.string.display_pulse_hint),
                checked = settings.calibrationPulse,
                onCheckedChange = { shown -> onChange { it.copy(calibrationPulse = shown) } },
            )

            // The beep itself is switched on and its volume set on the playback bar.
            Toggle(
                title = stringResource(R.string.display_beep_background),
                hint = stringResource(R.string.display_beep_background_hint),
                checked = settings.beepInBackground,
                enabled = settings.beep,
                onCheckedChange = { keep -> onChange { it.copy(beepInBackground = keep) } },
            )
        }
    }
}

@Composable
private fun Toggle(title: String, hint: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) Color.Unspecified else MonitorColors.Secondary)
            Text(hint, color = MonitorColors.Secondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun Choices(title: String, chips: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = chips)
    }
}

/** An ECG step as a multiple of the conventional 10 mm/mV: ×0.25 to ×4. */
fun ecgMultiple(index: Int): Float = ScaleLadder.ECG.mmPerUnit(index) / 10f

/** A setting's value without trailing zeros: 6.25, 12.5, 25. */
fun plainNumber(value: Float): String = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
