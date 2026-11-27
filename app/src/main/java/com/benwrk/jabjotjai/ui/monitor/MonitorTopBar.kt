package com.benwrk.jabjotjai.ui.monitor

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.monitor.MonitorSnapshot
import com.benwrk.jabjotjai.monitor.currentHeartRate
import com.benwrk.jabjotjai.raw.RawEvents
import com.benwrk.jabjotjai.ui.theme.MonitorColors
import com.benwrk.jabjotjai.ui.theme.MonitorMono
import com.benwrk.jabjotjai.ui.theme.inCells
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * The one bar above the full-width waveforms, standing in for the system status bar the monitor
 * hides: the strap's id and connection, the phone's time and battery, warnings, the strap's
 * contact, battery, gaps and last RR interval, heart rate with the beat heart, and Stop, which asks
 * first. Every icon says what it is when tapped or held. On a narrow screen it takes two rows.
 * Numbers are in a monospaced face, as on a bedside monitor, so they do not shift as they change.
 *
 * The connection is always the capture's as it is now; the vitals are at the playhead.
 *
 * @param playheadNs the playhead, on the capture's clock, for whether heart rate is still current
 * @param beepError why the beat beep stopped, or null while it works or is off
 */
@Composable
fun MonitorTopBar(
    sensorId: String,
    sensorName: String,
    live: MonitorSnapshot,
    vitals: State<MonitorSnapshot>,
    frameClock: State<Long>,
    playheadNs: Long,
    beepError: String?,
    wide: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = vitals.value
    val heartRate = snapshot.currentHeartRate(playheadNs)
    val start: @Composable () -> Unit = {
        Strap(sensorId, sensorName, live.connection)
        PhoneStatus()
    }
    val warnings: @Composable () -> Unit = {
        BatteryOptimisationWarning()
        beepError?.let { BeepWarning(it) }
    }
    val vitalsGrid: @Composable () -> Unit = {
        VitalsGrid(heartRate?.contact, snapshot.batteryPercentage, snapshot.gaps, heartRate?.lastRrMs)
    }
    val heart: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeartRateReadout(heartRate?.bpm)
            WithTip(stringResource(R.string.tip_heart), Modifier.padding(start = 4.dp)) {
                BeatHeart({ vitals.value.beats }, frameClock, Modifier.size(24.dp))
            }
        }
    }
    val rowModifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
    val spacing = Arrangement.spacedBy(16.dp)
    if (wide) {
        Row(modifier.then(rowModifier), horizontalArrangement = spacing, verticalAlignment = Alignment.CenterVertically) {
            start()
            Spacer(Modifier.weight(1f))
            warnings()
            vitalsGrid()
            heart()
            StopButton(onStop)
        }
    } else {
        Column(modifier) {
            Row(rowModifier, horizontalArrangement = spacing, verticalAlignment = Alignment.CenterVertically) {
                start()
                Spacer(Modifier.weight(1f))
                warnings()
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

/** The strap's id, without the model name every strap shares, which its tip gives; and its connection as an icon. */
@Composable
private fun Strap(sensorId: String, sensorName: String, connection: String?) {
    val (icon, color, state) = when (connection) {
        RawEvents.CONNECTED -> Triple(R.drawable.ic_link_connected, MonitorColors.Ecg, R.string.monitor_connected)
        RawEvents.CONNECTING -> Triple(R.drawable.ic_link_searching, MonitorColors.Warning, R.string.monitor_connecting)
        RawEvents.DISCONNECTED -> Triple(R.drawable.ic_link_lost, MonitorColors.Alarm, R.string.monitor_disconnected)
        else -> Triple(R.drawable.ic_link_searching, MonitorColors.Warning, R.string.monitor_waiting)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        WithTip(sensorName) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_sensor), contentDescription = null, tint = MonitorColors.Text, modifier = Modifier.size(18.dp))
                Text(sensorId, color = MonitorColors.Text, fontFamily = MonitorMono, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
            }
        }
        WithTip(stringResource(state), Modifier.padding(start = 6.dp), tint = color) {
            Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
    }
}

/** The phone's date, time and battery, which the hidden system status bar would otherwise show. */
@Composable
private fun PhoneStatus() {
    val context = LocalContext.current
    val clock = remember(context) { clockFormat(context) }
    val clockCells = remember(clock) { widestTime(clock) }
    val status by produceState(readPhoneStatus(context, clock), context, clock) {
        while (true) {
            value = readPhoneStatus(context, clock)
            // Just after the next second starts, so the seconds change when the phone's clock does.
            delay(1_000L - System.currentTimeMillis() % 1_000L)
        }
    }
    Column {
        WithTip(stringResource(R.string.tip_clock)) {
            IconValue(R.drawable.ic_clock, status.time, clockCells, MonitorColors.Secondary)
        }
        val level = percent(status.batteryPercentage)
        WithTip(stringResource(if (status.charging) R.string.tip_phone_battery_charging else R.string.tip_phone_battery, level)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconSlot { BatteryGlyph(status.batteryPercentage, if (status.charging) BatteryMark.CHARGING else BatteryMark.NONE, batteryColor(status.batteryPercentage)) }
                Value(level, PERCENT_CELLS, batteryColor(status.batteryPercentage), alignEnd = false)
            }
        }
    }
}

private data class PhoneStatusValues(val time: String, val batteryPercentage: Int?, val charging: Boolean)

/**
 * The date and the time to the second as the phone is set to show them: the date in Android's own
 * short form for the user's language and region, and the time in their 12- or 24-hour choice.
 * `DateFormat.getDateFormat` gives "the date in short form according to the context's locale", and
 * `is24HourFormat` the user's clock preference (`android.text.format.DateFormat`, SDK 37 source,
 * read 2026-11-27). Seconds need a pattern of their own, since the system's time format has none.
 */
private fun clockFormat(context: Context): SimpleDateFormat {
    val locale = context.resources.configuration.locales[0]
    val date = (DateFormat.getDateFormat(context) as? SimpleDateFormat)?.toPattern()
        ?: DateFormat.getBestDateTimePattern(locale, "yMd")
    val time = DateFormat.getBestDateTimePattern(locale, if (DateFormat.is24HourFormat(context)) "Hms" else "hms")
    return SimpleDateFormat("$date $time", locale)
}

/** The most characters [format] takes: every month, a one- and a two-digit day, and two-digit hours. */
private fun widestTime(format: SimpleDateFormat): Int {
    val calendar = Calendar.getInstance()
    return (0..11).flatMap { month -> listOf(1, 28).flatMap { day -> listOf(10, 11, 22, 23).map { hour -> Triple(month, day, hour) } } }
        .maxOf { (month, day, hour) ->
            calendar.set(2026, month, day, hour, 59, 59)
            format.format(calendar.time).length
        }
}

/**
 * The date and time, the battery as a whole percentage, or null when the phone cannot say
 * (`getIntProperty` returns `Integer.MIN_VALUE` on any error for apps targeting API 28 or later),
 * and whether it is charging: plugged in and the level going up, or full (`BatteryManager`, SDK
 * 37 source, read 2026-11-27).
 */
private fun readPhoneStatus(context: Context, clock: SimpleDateFormat): PhoneStatusValues {
    val battery = context.getSystemService(BatteryManager::class.java)
    return PhoneStatusValues(
        time = clock.format(Date()),
        batteryPercentage = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 },
        charging = battery?.isCharging == true,
    )
}

/**
 * Shown only while battery optimisation is on, which may refuse the restart that resumes a capture
 * Android stopped (decision `0013`); its tip explains it, so it can be turned off before a night's
 * recording.
 */
@Composable
private fun BatteryOptimisationWarning() {
    val context = LocalContext.current
    val optimised = !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    if (!optimised) return
    WithTip(stringResource(R.string.monitor_battery_optimised_detail), tint = MonitorColors.Warning) {
        Icon(painterResource(R.drawable.ic_battery_alert), contentDescription = null, tint = MonitorColors.Warning, modifier = Modifier.size(24.dp))
    }
}

/** Shown only when the beat beep could not play: the phone would not open an audio track, or it failed. */
@Composable
private fun BeepWarning(error: String) {
    WithTip(stringResource(R.string.monitor_beep_unavailable, error), tint = MonitorColors.Warning) {
        Icon(painterResource(R.drawable.ic_volume_off), contentDescription = null, tint = MonitorColors.Warning, modifier = Modifier.size(24.dp))
    }
}

/**
 * Skin contact as the strap with OK, OFF when the strap reports none, or a dash while unknown.
 * The gap count, amber when there are any; the strap's battery; the last RR interval.
 */
@Composable
private fun VitalsGrid(contact: Boolean?, batteryPercentage: Int?, gaps: Int, lastRrMs: Int?) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            val (contactTip, contactWord, contactColor) = when (contact) {
                true -> Triple(R.string.monitor_contact_ok, R.string.monitor_contact_ok_short, MonitorColors.Text)
                false -> Triple(R.string.monitor_contact_none, R.string.monitor_contact_off_short, MonitorColors.Warning)
                null -> Triple(R.string.monitor_contact_unknown, R.string.monitor_no_value, DARK)
            }
            WithTip(stringResource(contactTip)) {
                IconValue(R.drawable.ic_contact, stringResource(contactWord), CONTACT_CELLS, contactColor)
            }
            val gapColor = if (gaps > 0) MonitorColors.Warning else MonitorColors.Secondary
            WithTip(stringResource(R.string.tip_gaps, gaps)) {
                IconValue(R.drawable.ic_gap, gaps.toString(), GAP_CELLS, gapColor)
            }
        }
        Column {
            val level = percent(batteryPercentage)
            WithTip(stringResource(R.string.tip_strap_battery, level)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconSlot { BatteryGlyph(batteryPercentage, BatteryMark.SENSOR, batteryColor(batteryPercentage)) }
                    Value(level, PERCENT_CELLS, batteryColor(batteryPercentage))
                }
            }
            val rr = lastRrMs?.toString() ?: stringResource(R.string.monitor_no_value)
            WithTip(stringResource(R.string.tip_rr, rr)) {
                IconValue(R.drawable.ic_rr, rr, RR_CELLS, MonitorColors.Ecg)
            }
        }
    }
}

@Composable
private fun IconValue(icon: Int, value: String, cells: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconSlot { Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(ICON)) }
        Value(value, cells, color)
    }
}

/** Every icon in the same width, centred, so the values after them start in one column. */
@Composable
private fun IconSlot(icon: @Composable () -> Unit) {
    Box(Modifier.width(ICON_SLOT), contentAlignment = Alignment.Center) { icon() }
}

/**
 * [value] in [cells] characters, so a longer or shorter value does not move what is beside it:
 * right-aligned, so numbers in a column line up, or left-aligned under a longer value above it.
 */
@Composable
private fun Value(value: String, cells: Int, color: Color, alignEnd: Boolean = true) {
    Text(if (alignEnd) value.inCells(cells) else value.padEnd(cells), color = color, fontFamily = MonitorMono, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
}

/** What a battery glyph carries inside it, to tell the phone's from the strap's. */
private enum class BatteryMark { NONE, CHARGING, SENSOR }

/**
 * A battery on its side, filled to [percentage] from the left as the system status bar draws it,
 * carrying [mark]: the strap's has the strap's sensor icon in it, the phone's a bolt while
 * charging. The mark is cut out of the fill once that reaches past the middle, and drawn in the
 * battery's colour before then, so it shows either way.
 */
@Composable
private fun BatteryGlyph(percentage: Int?, mark: BatteryMark, color: Color) {
    val sensor = painterResource(R.drawable.ic_sensor)
    Canvas(Modifier.size(width = 18.dp, height = 10.dp)) {
        val width = size.width
        val height = size.height
        val stroke = 1.dp.toPx()
        val body = Size(width * 0.9f, height)
        val level = (percentage ?: 0).coerceIn(0, 100)
        drawRect(color, Offset(body.width, height * 0.3f), Size(width - body.width, height * 0.4f))
        drawRoundRect(color, Offset.Zero, body, CornerRadius(1.5.dp.toPx()), style = Stroke(stroke))
        drawRect(color, Offset(stroke, stroke), Size((body.width - 2 * stroke) * level / 100f, height - 2 * stroke))
        val centre = Offset(body.width / 2, height / 2)
        val markColor = if (level >= 50) MonitorColors.Background else color
        when (mark) {
            BatteryMark.NONE -> Unit
            BatteryMark.CHARGING -> {
                val bolt = Path().apply {
                    moveTo(centre.x + height * 0.15f, height * 0.1f)
                    lineTo(centre.x - height * 0.28f, height * 0.56f)
                    lineTo(centre.x, height * 0.56f)
                    lineTo(centre.x - height * 0.15f, height * 0.9f)
                    lineTo(centre.x + height * 0.28f, height * 0.44f)
                    lineTo(centre.x, height * 0.44f)
                    close()
                }
                drawPath(bolt, markColor)
            }
            BatteryMark.SENSOR -> {
                val side = height
                translate(left = centre.x - side / 2, top = centre.y - side / 2) {
                    with(sensor) { draw(Size(side, side), colorFilter = ColorFilter.tint(markColor)) }
                }
            }
        }
    }
}

/** Amber when low, as the system status bar warns. */
private fun batteryColor(percentage: Int?): Color = if (percentage != null && percentage <= LOW_BATTERY_PERCENTAGE) MonitorColors.Warning else MonitorColors.Secondary

@Composable
private fun percent(percentage: Int?): String =
    if (percentage == null) stringResource(R.string.monitor_no_value) else stringResource(R.string.monitor_percent, percentage)

@Composable
private fun HeartRateReadout(bpm: Int?) {
    WithTip(stringResource(R.string.tip_hr)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (bpm?.toString() ?: stringResource(R.string.monitor_no_value)).inCells(HEART_RATE_CELLS),
                color = MonitorColors.Ecg,
                fontSize = 44.sp,
                lineHeight = 44.sp,
                fontFamily = MonitorMono,
                fontWeight = FontWeight.Light,
            )
            // The labels sit between the number and the beat heart.
            Column(Modifier.padding(start = 6.dp)) {
                Text(stringResource(R.string.monitor_hr), color = MonitorColors.Ecg, style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.monitor_bpm), color = MonitorColors.Ecg, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Stop, after a question, so a stray tap does not end a night's recording. */
@Composable
private fun StopButton(onStop: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    WithTip(stringResource(R.string.monitor_stop_tip), tint = MonitorColors.Alarm, onClick = { asking = true }) {
        Box(Modifier.size(40.dp).border(BorderStroke(1.dp, MonitorColors.Alarm), CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_stop), contentDescription = null, tint = MonitorColors.Alarm, modifier = Modifier.size(20.dp))
        }
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

private val ICON = 16.dp
private val ICON_SLOT = 18.dp

/** An icon that is off: there, so the grid keeps its shape, but barely visible. */
private val DARK = Color(0xFF3A3A3A)

private const val LOW_BATTERY_PERCENTAGE = 15

/** Room for 3-digit heart rates, 100%, 4-digit RR intervals in ms and 3-digit gap counts. */
private const val HEART_RATE_CELLS = 3
private const val PERCENT_CELLS = 4
private const val RR_CELLS = 4
private const val GAP_CELLS = 3
private const val CONTACT_CELLS = 3

