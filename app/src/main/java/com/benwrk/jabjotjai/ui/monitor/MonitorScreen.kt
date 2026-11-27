package com.benwrk.jabjotjai.ui.monitor

import android.app.Activity
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.capture.Capture
import com.benwrk.jabjotjai.capture.CaptureState
import com.benwrk.jabjotjai.monitor.AutoScale
import com.benwrk.jabjotjai.monitor.BandScale
import com.benwrk.jabjotjai.monitor.ChartGraph
import com.benwrk.jabjotjai.monitor.DisplaySettings
import com.benwrk.jabjotjai.monitor.MonitorSnapshot
import com.benwrk.jabjotjai.monitor.MonitorViewModel
import com.benwrk.jabjotjai.monitor.Playback
import com.benwrk.jabjotjai.monitor.RowSignal
import com.benwrk.jabjotjai.monitor.SweepSpeed
import com.benwrk.jabjotjai.monitor.chartGraphs
import com.benwrk.jabjotjai.monitor.ScaleLadder
import com.benwrk.jabjotjai.monitor.TraceSnapshot
import com.benwrk.jabjotjai.monitor.elapsedSeconds
import com.benwrk.jabjotjai.monitor.positionNs
import com.benwrk.jabjotjai.ui.theme.MonitorColors
import com.benwrk.jabjotjai.ui.theme.MonitorMono
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
    HideStatusBar()
    ReportMonitorVisible()
    val liveWaves = viewModel.snapshot.collectAsState()
    val live = viewModel.live.collectAsState()
    val replay = viewModel.replay.collectAsState()
    val playback = viewModel.playback.collectAsState()
    val readError by viewModel.readError.collectAsState()
    val beepError by Capture.beepError.collectAsState()
    val settings = viewModel.displaySettings.collectAsState()
    MediaVolumeKeys(enabled = settings.value.beep)
    val frameClock = rememberFrameClock()
    val secondsClock = rememberSecondsClock()
    val pxPerMm = rememberPxPerMm()

    // What is drawn follows the playhead: the live readers at live, the past reader otherwise.
    // derivedStateOf makes these states that change only when their source does.
    val displayClock = remember { derivedStateOf { playback.value.positionNs(frameClock.value) } }
    val waves = remember { derivedStateOf { if (playback.value is Playback.Live) liveWaves.value else replay.value } }
    val vitals = remember { derivedStateOf { if (playback.value is Playback.Live) live.value.snapshot else replay.value } }

    // The height of each signal's shortest graph, which its automatic scale fits the trace to.
    val bandMm = remember { RowSignal.entries.associateWith { mutableFloatStateOf(0f) } }
    val ecgScale = rememberBandScale(waves, ScaleLadder.ECG, settings.value.ecgAuto, settings.value.ecgGainIndex, bandMm.getValue(RowSignal.ECG)) {
        listOf(it.ecg)
    }
    val accScale = rememberBandScale(waves, ScaleLadder.ACC, settings.value.accAuto, settings.value.accScaleIndex, bandMm.getValue(RowSignal.ACC)) {
        listOf(it.accX, it.accY, it.accZ)
    }
    // A single axis has a scale of its own, so it is centred on its own level rather than all three's.
    val axisScales = ACC_AXES.associate { axis ->
        axis.signal to rememberBandScale(waves, ScaleLadder.ACC, settings.value.accAuto, settings.value.accScaleIndex, bandMm.getValue(axis.signal)) {
            listOf(axis.trace(it))
        }
    }
    var showDisplaySheet by rememberSaveable { mutableStateOf(false) }

    // Every argument is a remembered state object, so one holder serves for as long as the scale holds.
    val scales = mapOf(RowSignal.ECG to ecgScale, RowSignal.ACC to accScale) + axisScales
    val bands = remember(pxPerMm) { Bands(waves, displayClock, pxPerMm, settings, scales, bandMm) }
    val playheadAtSecond = playback.value.positionNs(secondsClock.value)
    BoxWithConstraints(modifier.fillMaxSize().background(MonitorColors.Background)) {
        val wide = maxWidth >= WIDE_TOP_BAR
        Column(Modifier.fillMaxSize()) {
            MonitorTopBar(
                sensorId = running.sensorId,
                sensorName = running.sensorName,
                live = live.value.snapshot,
                vitals = vitals,
                frameClock = displayClock,
                playheadNs = playheadAtSecond,
                beepError = beepError,
                wide = wide,
                onStop = onStop,
            )
            (readError ?: live.value.readError)?.let {
                Text(
                    stringResource(R.string.monitor_read_error, it),
                    color = MonitorColors.Alarm,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Waveforms(bands, Modifier.weight(1f).fillMaxWidth())
            PlaybackBar(
                playback = playback.value,
                startNs = live.value.snapshot.header?.start?.monoNs,
                nowNs = secondsClock.value,
                sessionSeconds = live.value.snapshot.elapsedSeconds(secondsClock.value),
                settings = settings.value,
                onSettings = viewModel::updateDisplaySettings,
                onOpenSettings = { showDisplaySheet = true },
                onTogglePause = viewModel::togglePause,
                onScrub = viewModel::scrub,
                onSeek = viewModel::endScrub,
                onSpeed = viewModel::setSpeed,
                onLive = viewModel::goLive,
                onJump = viewModel::jump,
                modifier = Modifier.fillMaxWidth(),
            )
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
    val scales: Map<RowSignal, State<BandScale>>,
    val bandMm: Map<RowSignal, MutableFloatState>,
) {
    val ecgScale: State<BandScale> get() = scales.getValue(RowSignal.ECG)

}

/**
 * The chart: its graphs top to bottom (see [chartGraphs]), each as tall as its rows. Each signal's
 * automatic scale fits its shortest graph, so the trace fits in every graph of it. A cascade's
 * label and calibration pulse are drawn once, on its top graph, where its strip starts.
 */
@Composable
private fun Waveforms(bands: Bands, modifier: Modifier) {
    val graphs = chartGraphs(bands.settings.value.rows)
    val heightsMm = remember(graphs) { mutableStateMapOf<Int, Float>() }
    Column(modifier) {
        graphs.forEachIndexed { index, graph ->
            val graphModifier = Modifier.weight(graph.rows.toFloat()).onSizeChanged { size ->
                heightsMm[index] = size.height / bands.pxPerMm
                bands.bandMm.getValue(graph.signal).floatValue =
                    graphs.indices.filter { graphs[it].signal == graph.signal }.minOf { heightsMm[it] ?: Float.MAX_VALUE }
            }
            when (graph.signal) {
                RowSignal.ECG -> EcgBand(bands, graph, graphModifier)
                RowSignal.ACC -> AccBand(bands, ACC_AXES, bands.scales.getValue(RowSignal.ACC), graph, graphModifier)
                else -> {
                    val axis = ACC_AXES.first { it.signal == graph.signal }
                    AccBand(bands, listOf(axis), bands.scales.getValue(axis.signal), graph, graphModifier)
                }
            }
        }
    }
}

@Composable
private fun EcgBand(bands: Bands, graph: ChartGraph, modifier: Modifier) {
    val scale = bands.ecgScale.value
    val gain = ScaleLadder.ECG.mmPerUnit(scale.index)
    val label = stringResource(
        R.string.monitor_ecg_label,
        plainNumber(ecgMultiple(scale.index)),
        plainNumber(gain),
        plainNumber(graph.speed.mmPerSecond),
    ) + if (bands.settings.value.ecgAuto) " " + stringResource(R.string.monitor_auto) else ""
    Band(label = { if (graph.cascadeIndex == 0) Text(label, color = MonitorColors.Ecg, fontFamily = MonitorMono, style = MaterialTheme.typography.labelSmall) }, modifier) {
        SweepTrace(
            snapshot = bands.snapshot,
            nowMonoNs = bands.frameClock,
            trace = { it.ecg },
            timing = { it.ecgTiming },
            color = MonitorColors.Ecg,
            pxPerMm = bands.pxPerMm,
            mmPerSecond = { graph.speed.mmPerSecond },
            offsetPx = bandOffset(ScaleLadder.ECG, bands.ecgScale, bands.pxPerMm),
            cascadeIndex = graph.cascadeIndex,
            cascadeSize = graph.cascadeSize,
            modifier = Modifier.fillMaxSize(),
        )
        if (bands.settings.value.calibrationPulse && graph.cascadeIndex == 0) CalibrationPulse(bands, graph.speed, Modifier.fillMaxSize())
    }
}

/**
 * The accelerometer: [axes] on one scale and around one centre, so with all three each axis keeps
 * its true level (gravity included) relative to the others, and one axis alone is centred on its own.
 */
@Composable
private fun AccBand(bands: Bands, axes: List<AccAxis>, bandScale: State<BandScale>, graph: ChartGraph, modifier: Modifier) {
    val scale = bandScale.value
    val title = stringResource(R.string.monitor_acc_label)
    val scaleText = stringResource(R.string.monitor_acc_scale, plainNumber(ScaleLadder.ACC.mmPerUnit(scale.index))) +
        " " + stringResource(R.string.monitor_speed, plainNumber(graph.speed.mmPerSecond)) +
        if (bands.settings.value.accAuto) " " + stringResource(R.string.monitor_auto) else ""
    val label = buildAnnotatedString {
        withStyle(SpanStyle(color = MonitorColors.Text)) { append(title) }
        for (axis in axes) {
            append(" ")
            withStyle(SpanStyle(color = axis.color)) { append(axis.name) }
        }
        withStyle(SpanStyle(color = MonitorColors.Text)) { append(" $scaleText") }
    }
    Band(label = { if (graph.cascadeIndex == 0) Text(label, fontFamily = MonitorMono, style = MaterialTheme.typography.labelSmall) }, modifier) {
        val offset = bandOffset(ScaleLadder.ACC, bandScale, bands.pxPerMm)
        for (axis in axes) {
            SweepTrace(
                snapshot = bands.snapshot,
                nowMonoNs = bands.frameClock,
                trace = axis.trace,
                timing = { it.accTiming },
                color = axis.color,
                pxPerMm = bands.pxPerMm,
                mmPerSecond = { graph.speed.mmPerSecond },
                offsetPx = offset,
                cascadeIndex = graph.cascadeIndex,
                cascadeSize = graph.cascadeSize,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private class AccAxis(val signal: RowSignal, val name: String, val color: Color, val trace: (MonitorSnapshot) -> TraceSnapshot)

private val ACC_AXES = listOf(
    AccAxis(RowSignal.ACC_X, "X", MonitorColors.AccX) { it.accX },
    AccAxis(RowSignal.ACC_Y, "Y", MonitorColors.AccY) { it.accY },
    AccAxis(RowSignal.ACC_Z, "Z", MonitorColors.AccZ) { it.accZ },
)

/** How far above a band's middle line a sample is drawn, at the band's current scale. */
private fun bandOffset(ladder: ScaleLadder, scale: State<BandScale>, pxPerMm: Float): (Int) -> Float = { value ->
    val current = scale.value
    (value - current.centre) * ladder.mmPerSample(current.index) * pxPerMm
}

/** One waveform band: its traces across the full width, the label at the top left and a thin rule below. */
@Composable
private fun Band(label: @Composable () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth()) {
        content()
        Box(Modifier.padding(start = 8.dp, top = 2.dp)) { label() }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(MonitorColors.Divider))
    }
}

/** Where the calibration pulse starts, from the band's left edge, and how far its feet reach either side. */
private const val PULSE_LEFT_MM = 3f
private const val PULSE_FOOT_MM = 1.5f

/** The calibration pulse lasts 0.2 s, as on ECG paper. */
private const val PULSE_SECONDS = 0.2f

/**
 * The 1 mV calibration pulse, 0.2 s wide, at the band's current gain and sweep speed, drawn over
 * the left of the trace. It rises from the line where the trace draws 0 mV, so a wave can be held
 * up against it: the band's middle at a fixed gain, and wherever automatic scaling has moved the
 * trace otherwise. If 0 mV is off the band it stands on the edge instead, and where 1 mV is taller
 * than the room under the label its top is cut there and marked as clipped, like the trace.
 */
@Composable
private fun CalibrationPulse(bands: Bands, speed: SweepSpeed, modifier: Modifier) {
    Canvas(modifier) {
        val scale = bands.ecgScale.value
        val pxPerMicrovolt = ScaleLadder.ECG.mmPerSample(scale.index) * bands.pxPerMm
        val ceiling = LABEL_SPACE.toPx()
        val floor = size.height - 2.dp.toPx()
        // Where 0 µV is drawn: the inverse of bandOffset for a value of zero.
        val zero = (size.height / 2f + scale.centre * pxPerMicrovolt).coerceIn(ceiling, floor)
        val height = 1_000 * pxPerMicrovolt
        val top = maxOf(zero - height, ceiling)
        val left = PULSE_LEFT_MM * bands.pxPerMm
        val right = left + PULSE_SECONDS * speed.mmPerSecond * bands.pxPerMm
        val foot = PULSE_FOOT_MM * bands.pxPerMm
        val path = Path().apply {
            moveTo(left - foot, zero)
            lineTo(left, zero)
            lineTo(left, top)
            lineTo(right, top)
            lineTo(right, zero)
            lineTo(right + foot, zero)
        }
        drawPath(path, MonitorColors.Ecg.copy(alpha = 0.6f), style = Stroke(width = 1.dp.toPx()))
        if (zero - height < ceiling) {
            drawLine(MonitorColors.Clipped, Offset(left, top), Offset(right, top), strokeWidth = 1.dp.toPx())
        }
    }
}

/**
 * Heart rate, contact, battery and gaps at the playhead: from [Capture.live] at live, read from the
 * file's first line, and from the past reader otherwise. [nowMonoNs] is the playhead, for staleness.
 */
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

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Hides the system status bar while the monitor is on screen, so the traces get its height; the
 * top bar shows the time and battery instead. A swipe from the top edge shows it for a moment.
 */
@Composable
private fun HideStatusBar() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.statusBars()) }
    }
}

/** From this width the top bar fits on one row; below it, it takes two. */
private val WIDE_TOP_BAR = 600.dp

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
