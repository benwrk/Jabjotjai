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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.capture.CaptureState
import com.benwrk.jabjotjai.monitor.AutoScale
import com.benwrk.jabjotjai.monitor.BandScale
import com.benwrk.jabjotjai.monitor.DisplaySettings
import com.benwrk.jabjotjai.monitor.HeartRate
import com.benwrk.jabjotjai.monitor.MonitorSnapshot
import com.benwrk.jabjotjai.monitor.MonitorViewModel
import com.benwrk.jabjotjai.monitor.ScaleLadder
import com.benwrk.jabjotjai.monitor.TraceSnapshot
import com.benwrk.jabjotjai.monitor.currentHeartRate
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.RawEvents
import com.benwrk.jabjotjai.ui.theme.MonitorColors
import kotlinx.coroutines.delay

/** How often an automatic scale looks at its trace again. */
private const val AUTO_SCALE_TICK_MS = 250L

/** The height a band's label takes at its top, which the calibration pulse stays below. */
private val LABEL_SPACE = 20.dp

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
    val settings = viewModel.displaySettings.collectAsState()
    val frameClock = rememberFrameClock()
    val secondsClock = rememberSecondsClock()
    val pxPerMm = rememberPxPerMm()

    val ecgBandMm = remember { mutableFloatStateOf(0f) }
    val accBandMm = remember { mutableFloatStateOf(0f) }
    val ecgScale = rememberBandScale(snapshot, ScaleLadder.ECG, settings.value.ecgAuto, settings.value.ecgGainIndex, ecgBandMm) {
        listOf(it.ecg)
    }
    val accScale = rememberBandScale(snapshot, ScaleLadder.ACC, settings.value.accAuto, settings.value.accScaleIndex, accBandMm) {
        listOf(it.accX, it.accY, it.accZ)
    }
    var beepError by remember { mutableStateOf<String?>(null) }
    BeatBeeps(snapshot, settings.value.beep, onUnavailable = { beepError = it })
    var showDisplaySheet by rememberSaveable { mutableStateOf(false) }

    // Every argument is a remembered state object, so one holder serves for as long as the scale holds.
    val bands = remember(pxPerMm) { Bands(snapshot, frameClock, pxPerMm, settings, ecgScale, accScale, ecgBandMm, accBandMm) }
    BoxWithConstraints(modifier.fillMaxSize().background(MonitorColors.Background)) {
        val landscape = maxWidth > maxHeight
        val numericsWidth = maxWidth * 0.26f
        Column(Modifier.fillMaxSize()) {
            StatusBar(snapshot.value, running, secondsClock.value, onDisplay = { showDisplaySheet = true }, onStop)
            readError?.let {
                Text(
                    stringResource(R.string.monitor_read_error, it),
                    color = MonitorColors.Alarm,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Waveforms(bands, Modifier.weight(1f).fillMaxHeight())
                    Numerics(snapshot, frameClock, secondsClock.value, beepError, vertical = true, Modifier.width(numericsWidth).fillMaxHeight())
                }
            } else {
                Numerics(snapshot, frameClock, secondsClock.value, beepError, vertical = false, Modifier.fillMaxWidth())
                Waveforms(bands, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    if (showDisplaySheet) {
        DisplaySheet(
            settings = settings.value,
            ecgIndex = ecgScale.value.index,
            accIndex = accScale.value.index,
            onChange = viewModel::updateDisplaySettings,
            onDismiss = { showDisplaySheet = false },
        )
    }
}

/** What the waveform bands draw from, gathered so the two layouts pass one value. */
private class Bands(
    val snapshot: State<MonitorSnapshot>,
    val frameClock: State<Long>,
    val pxPerMm: Float,
    val settings: State<DisplaySettings>,
    val ecgScale: State<BandScale>,
    val accScale: State<BandScale>,
    val ecgBandMm: MutableFloatState,
    val accBandMm: MutableFloatState,
) {
    val mmPerSecond: () -> Float = { settings.value.sweep.mmPerSecond }
}

@Composable
private fun StatusBar(snapshot: MonitorSnapshot, running: CaptureState.Running, nowMonoNs: Long, onDisplay: () -> Unit, onStop: () -> Unit) {
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
        TextButton(onClick = onDisplay) { Text(stringResource(R.string.display_open), color = MonitorColors.Text) }
        OutlinedButton(onClick = onStop, border = BorderStroke(1.dp, MonitorColors.Alarm)) {
            Text(stringResource(R.string.stop), color = MonitorColors.Alarm)
        }
    }
}

/** The ECG band above one accelerometer band holding all three axes, each half the height. */
@Composable
private fun Waveforms(bands: Bands, modifier: Modifier) {
    Column(modifier) {
        EcgBand(bands, Modifier.weight(1f).onSizeChanged { bands.ecgBandMm.floatValue = it.height / bands.pxPerMm })
        AccBand(bands, Modifier.weight(1f).onSizeChanged { bands.accBandMm.floatValue = it.height / bands.pxPerMm })
    }
}

@Composable
private fun EcgBand(bands: Bands, modifier: Modifier) {
    val scale = bands.ecgScale.value
    val gain = ScaleLadder.ECG.mmPerUnit(scale.index)
    val label = stringResource(
        R.string.monitor_ecg_label,
        plainNumber(ecgMultiple(scale.index)),
        plainNumber(gain),
        plainNumber(bands.settings.value.sweep.mmPerSecond),
    ) + if (bands.settings.value.ecgAuto) " " + stringResource(R.string.monitor_auto) else ""
    Band(
        label = { Text(label, color = MonitorColors.Ecg, style = MaterialTheme.typography.labelSmall) },
        bands = bands,
        modifier = modifier,
        margin = { CalibrationPulse(bands, Modifier.fillMaxSize()) },
    ) {
        SweepTrace(
            snapshot = bands.snapshot,
            nowMonoNs = bands.frameClock,
            trace = { it.ecg },
            timing = { it.ecgTiming },
            color = MonitorColors.Ecg,
            pxPerMm = bands.pxPerMm,
            mmPerSecond = bands.mmPerSecond,
            offsetPx = bandOffset(ScaleLadder.ECG, bands.ecgScale, bands.pxPerMm),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * X, Y and Z on one scale and around one centre, so each axis keeps its true level (gravity
 * included) relative to the others.
 */
@Composable
private fun AccBand(bands: Bands, modifier: Modifier) {
    val scale = bands.accScale.value
    val title = stringResource(R.string.monitor_acc_label)
    val scaleText = stringResource(R.string.monitor_acc_scale, plainNumber(ScaleLadder.ACC.mmPerUnit(scale.index))) +
        if (bands.settings.value.accAuto) " " + stringResource(R.string.monitor_auto) else ""
    val label = buildAnnotatedString {
        withStyle(SpanStyle(color = MonitorColors.Text)) { append(title) }
        for (axis in ACC_AXES) {
            append(" ")
            withStyle(SpanStyle(color = axis.color)) { append(axis.name) }
        }
        withStyle(SpanStyle(color = MonitorColors.Text)) { append(" $scaleText") }
    }
    Band(label = { Text(label, style = MaterialTheme.typography.labelSmall) }, bands = bands, modifier = modifier) {
        val offset = bandOffset(ScaleLadder.ACC, bands.accScale, bands.pxPerMm)
        for (axis in ACC_AXES) {
            SweepTrace(
                snapshot = bands.snapshot,
                nowMonoNs = bands.frameClock,
                trace = axis.trace,
                timing = { it.accTiming },
                color = axis.color,
                pxPerMm = bands.pxPerMm,
                mmPerSecond = bands.mmPerSecond,
                offsetPx = offset,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private class AccAxis(val name: String, val color: Color, val trace: (MonitorSnapshot) -> TraceSnapshot)

private val ACC_AXES = listOf(
    AccAxis("X", MonitorColors.AccX) { it.accX },
    AccAxis("Y", MonitorColors.AccY) { it.accY },
    AccAxis("Z", MonitorColors.AccZ) { it.accZ },
)

/** How far above a band's middle line a sample is drawn, at the band's current scale. */
private fun bandOffset(ladder: ScaleLadder, scale: State<BandScale>, pxPerMm: Float): (Int) -> Float = { value ->
    val current = scale.value
    (value - current.centre) * ladder.mmPerSample(current.index) * pxPerMm
}

/**
 * One waveform band: a margin at the left that the sweep never draws over, then the traces, with
 * the label at the top left and a thin rule below. Every band has the same margin, set by the
 * calibration pulse's width, so the bands stay aligned in time.
 */
@Composable
private fun Band(
    label: @Composable () -> Unit,
    bands: Bands,
    modifier: Modifier,
    margin: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val marginWidth = with(LocalDensity.current) { (pulseMarginMm(bands.mmPerSecond()) * bands.pxPerMm).toDp() }
    Box(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(marginWidth).fillMaxHeight()) { margin() }
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
        Box(Modifier.padding(start = 8.dp, top = 2.dp)) { label() }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(MonitorColors.Divider))
    }
}

/** The space either side of the calibration pulse in the band's margin. */
private const val PULSE_SIDE_MM = 2f

/** The calibration pulse lasts 0.2 s, as on ECG paper. */
private const val PULSE_SECONDS = 0.2f

private fun pulseMarginMm(mmPerSecond: Float): Float = PULSE_SECONDS * mmPerSecond + 2 * PULSE_SIDE_MM

/**
 * The 1 mV calibration pulse, 0.2 s wide, at the band's current gain and sweep speed. It rises
 * from the line where the trace draws 0 mV, so a wave can be held up against it: the band's middle
 * at a fixed gain, and wherever automatic scaling has moved the trace otherwise. If 0 mV is off
 * the band it stands on the edge instead, and where 1 mV is taller than the room under the label
 * its top is cut there and marked as clipped, like the trace.
 */
@Composable
private fun CalibrationPulse(bands: Bands, modifier: Modifier) {
    Canvas(modifier) {
        val scale = bands.ecgScale.value
        val pxPerMicrovolt = ScaleLadder.ECG.mmPerSample(scale.index) * bands.pxPerMm
        val ceiling = LABEL_SPACE.toPx()
        val floor = size.height - 2.dp.toPx()
        // Where 0 µV is drawn: the inverse of bandOffset for a value of zero.
        val zero = (size.height / 2f + scale.centre * pxPerMicrovolt).coerceIn(ceiling, floor)
        val height = 1_000 * pxPerMicrovolt
        val top = maxOf(zero - height, ceiling)
        val left = PULSE_SIDE_MM * bands.pxPerMm
        val right = left + PULSE_SECONDS * bands.mmPerSecond() * bands.pxPerMm
        val path = Path().apply {
            moveTo(0f, zero)
            lineTo(left, zero)
            lineTo(left, top)
            lineTo(right, top)
            lineTo(right, zero)
            lineTo(size.width, zero)
        }
        drawPath(path, MonitorColors.Ecg.copy(alpha = 0.6f), style = Stroke(width = 1.dp.toPx()))
        if (zero - height < ceiling) {
            drawLine(MonitorColors.Clipped, Offset(left, top), Offset(right, top), strokeWidth = 1.dp.toPx())
        }
    }
}

@Composable
private fun Numerics(
    snapshot: State<MonitorSnapshot>,
    frameClock: State<Long>,
    nowMonoNs: Long,
    beepError: String?,
    vertical: Boolean,
    modifier: Modifier,
) {
    val heartRate = snapshot.value.currentHeartRate(nowMonoNs)
    if (vertical) {
        Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HeartRateNumeric(heartRate, snapshot, frameClock)
            Details(heartRate, snapshot.value, beepError)
        }
    } else {
        Row(modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
            HeartRateNumeric(heartRate, snapshot, frameClock)
            Details(heartRate, snapshot.value, beepError)
        }
    }
}

@Composable
private fun HeartRateNumeric(heartRate: HeartRate?, snapshot: State<MonitorSnapshot>, frameClock: State<Long>) {
    Column {
        Text(stringResource(R.string.monitor_hr), color = MonitorColors.Ecg, style = MaterialTheme.typography.titleMedium)
        Row {
            Text(
                heartRate?.bpm?.toString() ?: stringResource(R.string.monitor_no_value),
                color = MonitorColors.Ecg,
                fontSize = 72.sp,
                lineHeight = 72.sp,
                fontWeight = FontWeight.Light,
            )
            BeatHeart(snapshot, frameClock, Modifier.padding(start = 6.dp, top = 12.dp).size(24.dp))
        }
        Text(stringResource(R.string.monitor_bpm), color = MonitorColors.Ecg, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Details(heartRate: HeartRate?, snapshot: MonitorSnapshot, beepError: String?) {
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
        beepError?.let { Text(stringResource(R.string.monitor_beep_unavailable, it), color = MonitorColors.Warning) }
    }
}

/**
 * A band's scale: fixed at [fixedIndex] around zero, or chosen by [AutoScale] from the band's
 * recent [traces]. Saved across the activity being recreated, such as on rotation, so an automatic
 * scale does not start over. Auto scaling runs only while the screen is showing.
 */
@Composable
private fun rememberBandScale(
    snapshot: State<MonitorSnapshot>,
    ladder: ScaleLadder,
    auto: Boolean,
    fixedIndex: Int,
    bandMm: State<Float>,
    traces: (MonitorSnapshot) -> List<TraceSnapshot>,
): State<BandScale> {
    val scale = rememberSaveable(stateSaver = BandScaleSaver) { mutableStateOf(BandScale(fixedIndex, centre = 0)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(auto, fixedIndex, lifecycleOwner) {
        if (!auto) {
            scale.value = BandScale(fixedIndex, centre = 0)
            return@LaunchedEffect
        }
        val autoScale = AutoScale(ladder, initial = scale.value)
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                scale.value = autoScale.update(traces(snapshot.value), bandMm.value, SystemClock.elapsedRealtimeNanos())
                delay(AUTO_SCALE_TICK_MS)
            }
        }
    }
    return scale
}

private val BandScaleSaver = listSaver<BandScale, Int>(
    save = { listOf(it.index, it.centre) },
    restore = { BandScale(index = it[0], centre = it[1]) },
)

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
