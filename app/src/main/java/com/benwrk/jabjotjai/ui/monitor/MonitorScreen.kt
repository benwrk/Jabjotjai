package com.benwrk.jabjotjai.ui.monitor

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.capture.CaptureState
import com.benwrk.jabjotjai.monitor.HeartRate
import com.benwrk.jabjotjai.monitor.MonitorSnapshot
import com.benwrk.jabjotjai.monitor.MonitorViewModel
import com.benwrk.jabjotjai.monitor.Sweep
import com.benwrk.jabjotjai.monitor.TraceSnapshot
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.RawEvents
import com.benwrk.jabjotjai.ui.theme.MonitorColors
import kotlinx.coroutines.delay

/** The accelerometer bands span ±2 g on screen; the file keeps whatever range the strap used. */
private const val ACC_HALF_RANGE_MG = 2_000f

/** A heart rate older than this is shown as no value rather than as a current reading. */
private const val HR_STALE_NS = 5_000_000_000L

/**
 * The live monitor, laid out like a bedside patient monitor: waveforms sweeping on black, with the
 * heart-rate numeric in the same colour as the ECG. Landscape puts the numerics on the right;
 * portrait puts them above the waveforms.
 */
@Composable
fun MonitorScreen(
    viewModel: MonitorViewModel,
    running: CaptureState.Running,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KeepScreenOn()
    val snapshot = viewModel.snapshot.collectAsState()
    val readError by viewModel.readError.collectAsState()
    val frameClock = rememberFrameClock()
    val secondsClock = rememberSecondsClock()
    val pxPerMm = rememberPxPerMm()

    BoxWithConstraints(modifier.fillMaxSize().background(MonitorColors.Background)) {
        val landscape = maxWidth > maxHeight
        val numericsWidth = maxWidth * 0.26f
        Column(Modifier.fillMaxSize()) {
            StatusBar(snapshot.value, running, secondsClock.value, onStop)
            readError?.let {
                Text(
                    stringResource(R.string.monitor_read_error, it),
                    color = MonitorColors.Alarm,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Waveforms(snapshot, frameClock, pxPerMm, Modifier.weight(1f).fillMaxHeight())
                    Numerics(snapshot.value, secondsClock.value, vertical = true, Modifier.width(numericsWidth).fillMaxHeight())
                }
            } else {
                Numerics(snapshot.value, secondsClock.value, vertical = false, Modifier.fillMaxWidth())
                Waveforms(snapshot, frameClock, pxPerMm, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun StatusBar(snapshot: MonitorSnapshot, running: CaptureState.Running, nowMonoNs: Long, onStop: () -> Unit) {
    val (connection, connectionColor) = when (snapshot.connection) {
        RawEvents.CONNECTED -> stringResource(R.string.monitor_connected) to MonitorColors.Ecg
        RawEvents.CONNECTING -> stringResource(R.string.monitor_connecting) to MonitorColors.Warning
        RawEvents.DISCONNECTED -> stringResource(R.string.monitor_disconnected) to MonitorColors.Alarm
        else -> stringResource(R.string.monitor_waiting) to MonitorColors.Warning
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(running.sensorName, color = MonitorColors.Text, style = MaterialTheme.typography.titleSmall)
        Text(connection, color = connectionColor, style = MaterialTheme.typography.titleSmall)
        Text(elapsed(snapshot.header, nowMonoNs), color = MonitorColors.Secondary, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onStop, border = BorderStroke(1.dp, MonitorColors.Alarm)) {
            Text(stringResource(R.string.stop), color = MonitorColors.Alarm)
        }
    }
}

@Composable
private fun Waveforms(snapshot: State<MonitorSnapshot>, frameClock: State<Long>, pxPerMm: Float, modifier: Modifier) {
    Column(modifier) {
        Band(stringResource(R.string.monitor_ecg_label), MonitorColors.Ecg, Modifier.weight(2f)) {
            SweepTrace(
                snapshot = snapshot,
                nowMonoNs = frameClock,
                trace = { it.ecg },
                timing = { it.ecgTiming },
                color = MonitorColors.Ecg,
                pxPerMm = pxPerMm,
                offsetPx = { microvolts, _ -> microvolts * Sweep.MM_PER_MICROVOLT * pxPerMm },
                modifier = Modifier.fillMaxSize(),
            )
            CalibrationPulse(pxPerMm, Modifier.fillMaxSize())
        }
        AccBand("X", MonitorColors.AccX, snapshot, frameClock, pxPerMm, Modifier.weight(1f)) { it.accX }
        AccBand("Y", MonitorColors.AccY, snapshot, frameClock, pxPerMm, Modifier.weight(1f)) { it.accY }
        AccBand("Z", MonitorColors.AccZ, snapshot, frameClock, pxPerMm, Modifier.weight(1f)) { it.accZ }
    }
}

@Composable
private fun AccBand(
    axis: String,
    color: Color,
    snapshot: State<MonitorSnapshot>,
    frameClock: State<Long>,
    pxPerMm: Float,
    modifier: Modifier,
    trace: (MonitorSnapshot) -> TraceSnapshot,
) {
    Band(stringResource(R.string.monitor_acc_label, axis), color, modifier) {
        SweepTrace(
            snapshot = snapshot,
            nowMonoNs = frameClock,
            trace = trace,
            timing = { it.accTiming },
            color = color,
            pxPerMm = pxPerMm,
            offsetPx = { milliG, half -> milliG / ACC_HALF_RANGE_MG * half * 0.9f },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** One waveform band: its label at the top left and a thin rule below it. */
@Composable
private fun Band(label: String, color: Color, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth()) {
        content()
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(MonitorColors.Divider))
    }
}

/** The standard 1 mV calibration pulse at the left edge: 10 mm tall and 0.2 s wide at 25 mm/s. */
@Composable
private fun CalibrationPulse(pxPerMm: Float, modifier: Modifier) {
    Canvas(modifier) {
        val baseline = size.height / 2f
        val left = 8.dp.toPx()
        val height = 1_000 * Sweep.MM_PER_MICROVOLT * pxPerMm
        val width = 0.2f * Sweep.MM_PER_SECOND * pxPerMm
        val path = Path().apply {
            moveTo(left, baseline)
            lineTo(left, baseline - height)
            lineTo(left + width, baseline - height)
            lineTo(left + width, baseline)
        }
        drawPath(path, MonitorColors.Ecg.copy(alpha = 0.6f), style = Stroke(width = 1.dp.toPx()))
        drawLine(MonitorColors.Divider, Offset(0f, baseline), Offset(left, baseline))
    }
}

@Composable
private fun Numerics(snapshot: MonitorSnapshot, nowMonoNs: Long, vertical: Boolean, modifier: Modifier) {
    val heartRate = snapshot.heartRate?.takeIf { nowMonoNs - it.receivedMonoNs < HR_STALE_NS }
    if (vertical) {
        Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HeartRateNumeric(heartRate)
            Details(heartRate, snapshot)
        }
    } else {
        Row(modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
            HeartRateNumeric(heartRate)
            Details(heartRate, snapshot)
        }
    }
}

@Composable
private fun HeartRateNumeric(heartRate: HeartRate?) {
    Column {
        Text(stringResource(R.string.monitor_hr), color = MonitorColors.Ecg, style = MaterialTheme.typography.titleMedium)
        Text(
            heartRate?.bpm?.toString() ?: stringResource(R.string.monitor_no_value),
            color = MonitorColors.Ecg,
            fontSize = 72.sp,
            lineHeight = 72.sp,
            fontWeight = FontWeight.Light,
        )
        Text(stringResource(R.string.monitor_bpm), color = MonitorColors.Ecg, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Details(heartRate: HeartRate?, snapshot: MonitorSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            heartRate?.lastRrMs?.let { stringResource(R.string.monitor_rr, it) } ?: stringResource(R.string.monitor_rr_none),
            color = MonitorColors.Ecg,
        )
        when (heartRate?.contact) {
            true -> Text(stringResource(R.string.monitor_contact_ok), color = MonitorColors.Secondary)
            false -> Text(stringResource(R.string.monitor_contact_none), color = MonitorColors.Warning)
            null -> Unit
        }
        snapshot.batteryPct?.let { Text(stringResource(R.string.monitor_battery, it), color = MonitorColors.Secondary) }
        Text(stringResource(R.string.monitor_gaps, snapshot.gaps), color = if (snapshot.gaps > 0) MonitorColors.Warning else MonitorColors.Secondary)
    }
}

/** Time since the capture started, from the header's monotonic stamp, as `h:mm:ss`. */
private fun elapsed(header: HeaderRecord?, nowMonoNs: Long): String {
    val start = header?.start?.monoNs ?: return "0:00:00"
    val seconds = ((nowMonoNs - start) / 1_000_000_000L).coerceAtLeast(0)
    return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** The monotonic clock, updated every display frame, for the sweep cursor. */
@Composable
private fun rememberFrameClock(): State<Long> {
    val clock = remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { clock.longValue = SystemClock.elapsedRealtimeNanos() }
    }
    return clock
}

/** The monotonic clock, updated once a second, for the elapsed time and stale readings. */
@Composable
private fun rememberSecondsClock(): State<Long> {
    val clock = remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock.longValue = SystemClock.elapsedRealtimeNanos()
            delay(1_000)
        }
    }
    return clock
}

/** Physical scale from the density the phone reports, so 25 mm/s is 25 millimetres on the glass. */
@Composable
private fun rememberPxPerMm(): Float {
    val metrics = LocalContext.current.resources.displayMetrics
    return remember(metrics.xdpi, metrics.ydpi) { (metrics.xdpi + metrics.ydpi) / 2f / 25.4f }
}
