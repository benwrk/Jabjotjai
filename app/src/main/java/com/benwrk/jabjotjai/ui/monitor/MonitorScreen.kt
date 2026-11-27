package com.benwrk.jabjotjai.ui.monitor

import android.app.Activity
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
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
import com.benwrk.jabjotjai.monitor.EarlierTrends
import com.benwrk.jabjotjai.monitor.MonitorSnapshot
import com.benwrk.jabjotjai.monitor.MonitorViewModel
import com.benwrk.jabjotjai.monitor.Playback
import com.benwrk.jabjotjai.monitor.RowSignal
import com.benwrk.jabjotjai.monitor.ScaleLadder
import com.benwrk.jabjotjai.monitor.SweepSpeed
import com.benwrk.jabjotjai.monitor.TraceSnapshot
import com.benwrk.jabjotjai.monitor.TrendFilter
import com.benwrk.jabjotjai.monitor.TrendSnapshot
import com.benwrk.jabjotjai.monitor.chartGraphs
import com.benwrk.jabjotjai.monitor.heartRateFilter
import com.benwrk.jabjotjai.monitor.positionNs
import com.benwrk.jabjotjai.monitor.rrFilter
import com.benwrk.jabjotjai.ui.theme.MonitorColors
import com.benwrk.jabjotjai.ui.theme.MonitorMono
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

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
    val replayReady = viewModel.replayReady.collectAsState()
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
    // Just after leaving live, the live readings hold the moment until the past reader has it.
    val showLive = remember { derivedStateOf { playback.value is Playback.Live || !replayReady.value } }
    val waves = remember { derivedStateOf { if (showLive.value) liveWaves.value else replay.value } }
    val vitals = remember { derivedStateOf { if (showLive.value) live.value.snapshot else replay.value } }

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
    // Heart-rate and RR rows draw on the capture's own reading, which holds its whole history.
    val trends = remember { derivedStateOf { live.value.snapshot } }
    // The history of the files this capture continues: only ever this capture's, never a late result for another.
    val earlierTrends = Capture.earlierTrends.collectAsState()
    val session = running.file.nameWithoutExtension
    val earlier = remember(session) { derivedStateOf { earlierTrends.value.takeIf { it.session == session } ?: EarlierTrends.None } }
    // The whole resumed capture's start, once its earlier files are read; until then, this file's.
    val captureStartNs = (earlier.value as? EarlierTrends.Read)?.files?.firstOrNull()?.startNs ?: live.value.snapshot.header?.start?.monoNs
    val bands = remember(pxPerMm, session) { Bands(waves, trends, earlier, displayClock, pxPerMm, settings, scales, bandMm) }
    val playheadAtSecond = playback.value.positionNs(secondsClock.value)
    // The system bars and the camera cut-out, which every other screen keeps clear of whole. Here the
    // monitor, if set to, reaches under the cut-out from edge to edge, the bars above and below the
    // chart kept in from a side the camera overlaps (see ClearOfCutout) and the chart's labels moved
    // clear of it (see Band).
    val safe = ScaffoldDefaults.contentWindowInsets
    val underCutout = settings.value.chartUnderCutout
    val sides = (if (underCutout) WindowInsets.systemBars else safe).only(WindowInsetsSides.Horizontal)
    val cutout = if (underCutout) rememberCutoutInWindow() else emptyList()
    BoxWithConstraints(modifier.fillMaxSize().background(MonitorColors.Background).windowInsetsPadding(safe.only(WindowInsetsSides.Vertical))) {
        val wide = maxWidth >= WIDE_TOP_BAR
        CompositionLocalProvider(LocalCutout provides cutout) {
            Column(Modifier.fillMaxSize()) {
                ClearOfCutout(Modifier.fillMaxWidth().windowInsetsPadding(sides)) {
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
                }
                (readError ?: live.value.readError)?.let {
                    Text(
                        stringResource(R.string.monitor_read_error, it),
                        color = MonitorColors.Alarm,
                        modifier = Modifier.windowInsetsPadding(sides).padding(horizontal = 12.dp),
                    )
                }
                Waveforms(bands, Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(sides))
                ClearOfCutout(Modifier.fillMaxWidth().windowInsetsPadding(sides)) {
                    PlaybackBar(
                        playback = playback.value,
                        startNs = captureStartNs,
                        nowNs = secondsClock.value,
                        sessionSeconds = captureStartNs?.let { ((secondsClock.value - it) / 1_000_000_000L).coerceAtLeast(0) },
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
    val trends: State<MonitorSnapshot>,
    val earlier: State<EarlierTrends>,
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
                RowSignal.HR -> TrendBand(
                    stringResource(R.string.monitor_hr_trend, graph.span.minutes), stringResource(R.string.monitor_bpm), bands.settings.value.heartRateFilter, { listOfNotNull((bands.earlier.value as? EarlierTrends.Read)?.heartRate, bands.trends.value.heartRateTrend) }, bands, graph,
                    MonitorColors.Ecg, step = 10, minRange = 40, dots = false, graphModifier,
                )
                RowSignal.RR -> TrendBand(
                    stringResource(R.string.monitor_rr_trend, graph.span.minutes), stringResource(R.string.monitor_ms), bands.settings.value.rrFilter, { listOfNotNull((bands.earlier.value as? EarlierTrends.Read)?.rr, bands.trends.value.rrTrend) }, bands, graph,
                    MonitorColors.Text, step = 100, minRange = 400, dots = true, graphModifier,
                )
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

/**
 * One waveform band: its traces across the full width, the label at the top left and a thin rule
 * below. Where the chart reaches under the camera cut-out, the label moves right of the camera if
 * it would be under it, and what is drawn inside reads the cut-out in the band's own coordinates
 * from [LocalCutout], to move clear of it the same way.
 */
@Composable
private fun Band(label: @Composable () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val cutoutInWindow = LocalCutout.current
    var bandInWindow by remember { mutableStateOf(Rect.Zero) }
    var labelSize by remember { mutableStateOf(IntSize.Zero) }
    val cutout = remember(cutoutInWindow, bandInWindow) {
        cutoutInWindow.map { it.translate(-bandInWindow.left, -bandInWindow.top) }
    }
    val density = LocalDensity.current
    val labelShift = with(density) {
        val start = LABEL_START.toPx()
        val top = LABEL_TOP.toPx()
        clearanceRight(Rect(start, top, start + labelSize.width, top + labelSize.height), cutout, CUTOUT_GAP.toPx()).toDp()
    }
    Box(modifier.fillMaxWidth().onGloballyPositioned { bandInWindow = it.boundsInWindow() }) {
        CompositionLocalProvider(LocalCutout provides cutout) {
            content()
        }
        Box(Modifier.padding(start = LABEL_START + labelShift, top = LABEL_TOP).onSizeChanged { labelSize = it }) { label() }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(MonitorColors.Divider))
    }
}

private val LABEL_START = 8.dp
private val LABEL_TOP = 2.dp

/** How far clear of the camera cut-out a label or the calibration pulse is kept. */
internal val CUTOUT_GAP = 4.dp

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
    val cutout = LocalCutout.current
    Canvas(modifier) {
        val scale = bands.ecgScale.value
        val pxPerMicrovolt = ScaleLadder.ECG.mmPerSample(scale.index) * bands.pxPerMm
        val ceiling = LABEL_SPACE.toPx()
        val floor = size.height - 2.dp.toPx()
        // Where 0 µV is drawn: the inverse of bandOffset for a value of zero.
        val zero = (size.height / 2f + scale.centre * pxPerMicrovolt).coerceIn(ceiling, floor)
        val height = 1_000 * pxPerMicrovolt
        val top = maxOf(zero - height, ceiling)
        val foot = PULSE_FOOT_MM * bands.pxPerMm
        val width = PULSE_SECONDS * speed.mmPerSecond * bands.pxPerMm
        val nominalLeft = PULSE_LEFT_MM * bands.pxPerMm
        // Moved right of the camera cut-out if it would be under it.
        val left = nominalLeft + clearanceRight(Rect(nominalLeft - foot, top, nominalLeft + width + foot, zero), cutout, CUTOUT_GAP.toPx())
        val right = left + width
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
                delay(AUTO_SCALE_TICK_MS.milliseconds)
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
 *
 * Called inside a dialog or a sheet, which has a window of its own, it hides the bar in that
 * window: Material 3's sheet otherwise keeps the system bars visible for its window
 * (`ModalBottomSheet.android.kt`, material3 1.5.0 source), which brought the bar back and moved the
 * monitor down behind it. A dialog's window goes when the dialog closes, so the bar is not shown
 * again for it.
 */
@Composable
internal fun HideStatusBar() {
    val view = LocalView.current
    DisposableEffect(view) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val window = dialogWindow ?: generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, if (dialogWindow != null) it.decorView else view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose { if (dialogWindow == null) controller?.show(WindowInsetsCompat.Type.statusBars()) }
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
            delay(1_000.milliseconds)
        }
    }
    return clock
}

/** Physical scale from the density the phone reports, so 25 mm/s is 25 millimetres on the glass. */
@Composable
private fun rememberPxPerMm(): Float {
    val metrics = LocalResources.current.displayMetrics
    return remember(metrics.xdpi, metrics.ydpi) { (metrics.xdpi + metrics.ydpi) / 2f / 25.4f }
}

/**
 * A heart-rate or RR graph with its label, given once on a cascade's top graph. A line breaks after
 * [TREND_JOIN_NS] without a value, as heart rate comes about once a second. Holding a finger on it
 * reads the value under the finger, in [unit] (see [TrendReadout]).
 */
@Composable
private fun TrendBand(
    label: String,
    unit: String,
    filter: TrendFilter,
    trends: () -> List<TrendSnapshot>,
    bands: Bands,
    graph: ChartGraph,
    color: Color,
    step: Int,
    minRange: Int,
    dots: Boolean,
    modifier: Modifier,
) {
    Band(label = { if (graph.cascadeIndex == 0) Text(label, color = color, fontFamily = MonitorMono, style = MaterialTheme.typography.labelSmall) }, modifier) {
        Box(Modifier.fillMaxSize()) {
            TrendTrace(trends, bands.frameClock, graph, color, step, minRange, dots, maxJoinNs = TREND_JOIN_NS, filter = filter, modifier = Modifier.fillMaxSize())
            if (graph.cascadeIndex == 0) EarlierTrendsNote(bands.earlier.value, color)
            TrendReadout(
                trends,
                bands.frameClock,
                graph,
                unit,
                filter,
                wallMsAt = { monoNs -> bands.trends.value.header?.start?.let { it.wallMs + (monoNs - it.monoNs) / 1_000_000 } },
                color = color,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val TREND_JOIN_NS = 10_000_000_000L

/**
 * How the history of the files a capture continues stands, on a heart-rate or RR graph: while it is
 * read, how far, as a bar and a percentage; once read, how many earlier recordings could not be,
 * so a stretch missing for that reason never passes for a quiet one.
 */
@Composable
private fun BoxScope.EarlierTrendsNote(earlier: EarlierTrends, color: Color) {
    when (earlier) {
        is EarlierTrends.Reading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.monitor_trend_reading, (earlier.fraction * 100).roundToInt()),
                color = MonitorColors.Secondary,
                fontFamily = MonitorMono,
                style = MaterialTheme.typography.labelSmall,
            )
            LinearProgressIndicator(
                progress = { earlier.fraction },
                modifier = Modifier.padding(top = 4.dp).width(160.dp),
                color = color,
                trackColor = MonitorColors.Divider,
            )
        }
        is EarlierTrends.Read -> if (earlier.unreadable > 0) {
            Text(
                pluralStringResource(R.plurals.monitor_trend_unreadable, earlier.unreadable, earlier.unreadable),
                color = MonitorColors.Warning,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 2.dp),
            )
        }
        EarlierTrends.None -> Unit
    }
}
