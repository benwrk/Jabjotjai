package com.benwrk.jabjotjai.ui.monitor

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.raw.RawEvents
import com.benwrk.jabjotjai.monitor.MonitorSnapshot
import com.benwrk.jabjotjai.monitor.currentHeartRate
import com.benwrk.jabjotjai.ui.theme.MonitorColors
import kotlinx.coroutines.delay
import java.util.Date

/**
 * The one bar above the full-width waveforms, standing in for the system status bar the monitor
 * hides: the strap and its connection, the phone's time and battery, a warning while battery
 * optimisation is on, the strap's contact, battery, gaps and last RR interval, heart rate with the
 * beat heart, and Stop, which asks first. On a narrow screen it takes two rows.
 *
 * The connection is always the capture's as it is now; the vitals are at the playhead.
 *
 * @param playheadNs the playhead, on the capture's clock, for whether heart rate is still current
 */
@Composable
fun MonitorTopBar(
    sensorName: String,
    live: MonitorSnapshot,
    vitals: State<MonitorSnapshot>,
    frameClock: State<Long>,
    playheadNs: Long,
    wide: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = vitals.value
    val heartRate = snapshot.currentHeartRate(playheadNs)
    val start: @Composable () -> Unit = {
        Strap(sensorName, live.connection)
        PhoneStatus()
    }
    val vitalsGrid: @Composable () -> Unit = {
        VitalsGrid(heartRate?.contact, snapshot.batteryPercentage, snapshot.gaps, heartRate?.lastRrMs)
    }
    val heart: @Composable () -> Unit = {
        HeartRateReadout(heartRate?.bpm)
        BeatHeart({ vitals.value.beats }, frameClock, Modifier.size(24.dp))
    }
    val rowModifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
    val spacing = Arrangement.spacedBy(16.dp)
    if (wide) {
        Row(modifier.then(rowModifier), horizontalArrangement = spacing, verticalAlignment = Alignment.CenterVertically) {
            start()
            Spacer(Modifier.weight(1f))
            BatteryOptimisationWarning()
            vitalsGrid()
            heart()
            StopButton(onStop)
        }
    } else {
        Column(modifier) {
            Row(rowModifier, horizontalArrangement = spacing, verticalAlignment = Alignment.CenterVertically) {
                start()
                Spacer(Modifier.weight(1f))
                BatteryOptimisationWarning()
                StopButton(onStop)
            }
            Row(rowModifier, horizontalArrangement = spacing, verticalAlignment = Alignment.CenterVertically) {
                vitalsGrid()
                Spacer(Modifier.weight(1f))
                heart()
            }
        }
    }
}

@Composable
private fun Strap(sensorName: String, connection: String?) {
    val (text, color) = when (connection) {
        RawEvents.CONNECTED -> stringResource(R.string.monitor_connected) to MonitorColors.Ecg
        RawEvents.CONNECTING -> stringResource(R.string.monitor_connecting) to MonitorColors.Warning
        RawEvents.DISCONNECTED -> stringResource(R.string.monitor_disconnected) to MonitorColors.Alarm
        else -> stringResource(R.string.monitor_waiting) to MonitorColors.Warning
    }
    Column {
        Text(sensorName, color = MonitorColors.Text, style = MaterialTheme.typography.bodyMedium)
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The phone's time and battery, which the hidden system status bar would otherwise show. */
@Composable
private fun PhoneStatus() {
    val context = LocalContext.current
    val status by produceState(readPhoneStatus(context), context) {
        while (true) {
            value = readPhoneStatus(context)
            // Just after the next second starts, so the minute changes when the phone's clock does.
            delay(1_000L - System.currentTimeMillis() % 1_000L)
        }
    }
    Column {
        IconValue(R.drawable.ic_clock, null, status.time, MonitorColors.Secondary)
        IconValue(R.drawable.ic_battery, stringResource(R.string.monitor_phone_battery), percent(status.batteryPercentage), MonitorColors.Secondary)
    }
}

private data class PhoneStatusValues(val time: String, val batteryPercentage: Int?)

/**
 * The time in the user's 12- or 24-hour format, and the battery as a whole percentage, or null
 * when the phone cannot say: `getIntProperty` returns `Integer.MIN_VALUE` on any error for apps
 * targeting API 28 or later (`BatteryManager`, SDK 37 source, read 2026-11-27).
 */
private fun readPhoneStatus(context: Context): PhoneStatusValues {
    val capacity = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return PhoneStatusValues(DateFormat.getTimeFormat(context).format(Date()), capacity?.takeIf { it in 0..100 })
}

/**
 * Shown only while battery optimisation is on, which may refuse the restart that resumes a capture
 * Android stopped (decision `0013`); a tap explains it, so it can be turned off before a night's
 * recording.
 */
@Composable
private fun BatteryOptimisationWarning() {
    val context = LocalContext.current
    val optimised = !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    if (!optimised) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(
                painterResource(R.drawable.ic_battery_alert),
                contentDescription = stringResource(R.string.monitor_battery_optimised),
                tint = MonitorColors.Warning,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.monitor_battery_optimised_detail),
                color = MonitorColors.Warning,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Skin contact as an icon, lit with contact and dark without or while unknown; the strap's battery,
 * the gap count and the last RR interval as icons with their values, gaps amber when there are any.
 */
@Composable
private fun VitalsGrid(contact: Boolean?, batteryPercentage: Int?, gaps: Int, lastRrMs: Int?) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            val contactDescription = when (contact) {
                true -> R.string.monitor_contact_ok
                false -> R.string.monitor_contact_none
                null -> R.string.monitor_contact_unknown
            }
            IconValue(R.drawable.ic_contact, stringResource(contactDescription), null, if (contact == true) MonitorColors.Text else DARK)
            IconValue(R.drawable.ic_gap, stringResource(R.string.monitor_gaps_label), gaps.toString(), if (gaps > 0) MonitorColors.Warning else MonitorColors.Secondary)
        }
        Column {
            IconValue(R.drawable.ic_battery, stringResource(R.string.monitor_strap_battery), percent(batteryPercentage), MonitorColors.Secondary)
            IconValue(R.drawable.ic_rr, stringResource(R.string.monitor_rr_label), lastRrMs?.toString() ?: stringResource(R.string.monitor_no_value), MonitorColors.Ecg)
        }
    }
}

@Composable
private fun IconValue(icon: Int, description: String?, value: String?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = description, tint = color, modifier = Modifier.size(16.dp))
        if (value != null) {
            Text(value, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun percent(pct: Int?): String =
    if (pct == null) stringResource(R.string.monitor_no_value) else stringResource(R.string.monitor_percent, pct)

@Composable
private fun HeartRateReadout(bpm: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.monitor_hr), color = MonitorColors.Ecg, style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.monitor_bpm), color = MonitorColors.Ecg, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            bpm?.toString() ?: stringResource(R.string.monitor_no_value),
            color = MonitorColors.Ecg,
            fontSize = 44.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.Light,
            // Room for three digits, so the heart beside it stays put as the rate changes.
            modifier = Modifier.padding(start = 6.dp).widthIn(min = 76.dp),
        )
    }
}

/** Stop, after a question, so a stray tap does not end a night's recording. */
@Composable
private fun StopButton(onStop: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { asking = true }, border = BorderStroke(1.dp, MonitorColors.Alarm)) {
        Text(stringResource(R.string.stop), color = MonitorColors.Alarm)
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.monitor_stop_title)) },
            text = { Text(stringResource(R.string.monitor_stop_body)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    onStop()
                }) { Text(stringResource(R.string.stop), color = MonitorColors.Alarm) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text(stringResource(R.string.monitor_stop_keep)) }
            },
        )
    }
}

/** An icon that is off: there, so the grid keeps its shape, but barely visible. */
private val DARK = Color(0xFF3A3A3A)
