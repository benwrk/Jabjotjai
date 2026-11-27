package com.benwrk.jabjotjai.monitor

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.benwrk.jabjotjai.capture.Capture
import com.benwrk.jabjotjai.capture.CaptureState
import com.benwrk.jabjotjai.capture.LiveReading
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.RawLine
import com.benwrk.jabjotjai.raw.RawTail
import com.benwrk.jabjotjai.raw.UnreadableRawFileException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Feeds the monitor screen from the raw file of the running capture. It never receives data from
 * the capture directly: the file is the only source (`AGENTS.md`, "Live is a reader tailing the
 * raw file").
 *
 * At live, the waveforms come from its own tail, which starts near the end of the file so a sweep
 * fills at once, and everything that depends on the whole file (connection, heart rate, battery,
 * gaps, beats) comes from [Capture.live], read from the first line by the capture service.
 *
 * In the past, everything comes from a second reader, [replay], that starts a little before the
 * moment shown and reads on only as fast as the playhead moves, so it holds exactly what had been
 * received by then. It starts from a mark the live reader noted ([CaptureTimeline]), with the
 * state as it stood there, so jumping back does not mean reading the file from its start.
 */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableSnapshot = MutableStateFlow(MonitorSnapshot.EMPTY)

    /** The live waveforms and their timing; read the rest from [live]. */
    val snapshot: StateFlow<MonitorSnapshot> = mutableSnapshot.asStateFlow()

    val live: StateFlow<LiveReading> = Capture.live

    private val mutableReplay = MutableStateFlow(MonitorSnapshot.EMPTY)

    /** In the past: everything, as of the playhead. */
    val replay: StateFlow<MonitorSnapshot> = mutableReplay.asStateFlow()

    private val mutableReplayReady = MutableStateFlow(false)

    /**
     * Whether [replay] holds the moment shown. Not yet, just after leaving live: the past reader is
     * still reading up to the playhead, and the live readings still hold that moment, so the screen
     * draws those until then rather than nothing.
     */
    val replayReady: StateFlow<Boolean> = mutableReplayReady.asStateFlow()

    private val mutablePlayback = MutableStateFlow<Playback>(Playback.Live)
    val playback: StateFlow<Playback> = mutablePlayback.asStateFlow()

    /** Changes whenever the past reader has to start again: a jump, or leaving live. */
    private val replayStart = MutableStateFlow(0)

    private val mutableReadError = MutableStateFlow<String?>(null)
    val readError: StateFlow<String?> = mutableReadError.asStateFlow()

    val displaySettings: StateFlow<DisplaySettings> = DisplaySettingsStore.settings

    /**
     * How many sweeps of history the chart needs: as many as its longest cascade spans; one without
     * a cascade. The model keeps, and the readers read, [MonitorModel.DEFAULT_KEEP_NS] for
     * each, which holds a whole sweep even at the slowest speed.
     */
    private val sweepsKept = DisplaySettingsStore.settings.map { longestCascade(chartGraphs(it.rows)) }.distinctUntilChanged()

    fun updateDisplaySettings(change: (DisplaySettings) -> DisplaySettings) = DisplaySettingsStore.update(change)

    init {
        DisplaySettingsStore.init(application)
        val runningFile = Capture.state.map { (it as? CaptureState.Running)?.file }.distinctUntilChanged()
        viewModelScope.launch {
            runningFile.collectLatest {
                mutableSnapshot.value = MonitorSnapshot.EMPTY
                mutableReadError.value = null
                mutablePlayback.value = Playback.Live
            }
        }
        viewModelScope.launch {
            // Read again from further back when a cascade needs more sweeps kept.
            combine(runningFile, sweepsKept, ::Pair).collectLatest { (file, sweeps) ->
                if (file != null) follow(file, sweeps)
            }
        }
        viewModelScope.launch {
            combine(runningFile, replayStart) { file, _ -> file }.collectLatest { file ->
                if (file == null) return@collectLatest
                if (beforeScrub == null) {
                    if (mutablePlayback.value is Playback.Past) replay(file)
                } else {
                    // Collecting a StateFlow skips the values set while a read was running, so
                    // each read finishes and the newest moment under the finger is always read next.
                    scrubTarget.filterNotNull().collect { readMoment(file, it) }
                }
            }
        }
        viewModelScope.launch {
            // Faster-than-live playback that has caught up becomes live.
            while (true) {
                val current = mutablePlayback.value
                val next = current.caughtUp(nowNs())
                if (next != current) goLive()
                delay(CATCH_UP_CHECK_MS)
            }
        }
        viewModelScope.launch {
            combine(mutableReplay, mutablePlayback) { replay, playback -> replayBeatsOnRealClock(replay.beats, playback) }
                .collect(Capture::setReplayBeats)
        }
    }

    override fun onCleared() {
        Capture.setReplayBeats(null)
    }

    /** The playback before the current drag began, or null when not dragging. */
    private var beforeScrub: Playback? = null

    /** The moment under the finger while the timeline is dragged. */
    private val scrubTarget = MutableStateFlow<Long?>(null)

    /**
     * Shows [targetNs] while the timeline is being dragged, paused. The screen moves there once
     * that moment has been read, so it always shows a whole sweep, one read behind the finger;
     * see [readMoment].
     */
    fun scrub(targetNs: Long) {
        val start = Capture.live.value.snapshot.header?.start?.monoNs ?: return
        val now = nowNs()
        if (beforeScrub == null) {
            val before = mutablePlayback.value
            beforeScrub = before
            // Live stays on screen until the first moment has been read; the past holds still.
            if (before is Playback.Past) mutablePlayback.value = Playback.Past(before.positionNs(now), now, before.speed, paused = true)
            replayStart.value++
        }
        scrubTarget.value = targetNs.coerceIn(start, now)
    }

    /** Ends a drag at [targetNs]: jumps there and plays or stays paused as before the drag began. */
    fun endScrub(targetNs: Long) {
        val before = beforeScrub ?: mutablePlayback.value
        beforeScrub = null
        scrubTarget.value = null
        val start = Capture.live.value.snapshot.header?.start?.monoNs ?: return
        val now = nowNs()
        val base = if (before is Playback.Live) before else Playback.Past(targetNs, now, before.speed, before.paused)
        mutablePlayback.value = base.seek(targetNs, start, now)
        replayStart.value++
    }

    /** Moves the playhead [byNs] from where it is, back if negative; see [Playback.jump]. */
    fun jump(byNs: Long) {
        val start = Capture.live.value.snapshot.header?.start?.monoNs ?: return
        val before = mutablePlayback.value
        mutablePlayback.value = before.jump(byNs, start, nowNs())
        if (before is Playback.Live) leaveLive() else replayStart.value++
    }

    fun setSpeed(speed: Float) {
        val before = mutablePlayback.value
        mutablePlayback.value = before.withSpeed(speed, nowNs())
        if (before is Playback.Live && mutablePlayback.value is Playback.Past) leaveLive()
    }

    fun togglePause() {
        val before = mutablePlayback.value
        mutablePlayback.value = before.togglePause(nowNs())
        if (before is Playback.Live) leaveLive()
    }

    /** Starts the past reader, with the live readings on screen until it reaches the playhead. */
    private fun leaveLive() {
        mutableReplayReady.value = false
        replayStart.value++
    }

    fun goLive() {
        mutablePlayback.value = Playback.Live
        replayStart.value++
    }

    private suspend fun follow(file: File, sweeps: Int) {
        val model = MonitorModel(MonitorModel.DEFAULT_KEEP_NS * sweeps)
        try {
            model.setHeader(awaitHeader(file))
            RawTail(file).lines(RawTail.Start.NearEnd(TAIL_START_BYTES * sweeps)).collect { line ->
                model.accept(line)
                mutableSnapshot.value = model.snapshot()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            mutableReadError.value = e.message ?: e.toString()
        }
    }

    /**
     * Reads the past from a little before the playhead and keeps pace with it: a line is taken
     * only once the playhead has reached the time it was received, so the model holds exactly what
     * had arrived by then. Lines up to the playhead are taken at once, and published together.
     */
    private suspend fun replay(file: File) {
        val sweeps = sweepsKept.first()
        val (model, start) = modelFrom(mutablePlayback.value.positionNs(nowNs()) - REPLAY_LEAD_NS * sweeps, sweeps) ?: return
        // Nothing is published until the read reaches the playhead: the screen keeps what it shows.
        reportingReadErrors {
            RawTail(file).linesWithOffsets(start).collect { (_, line) ->
                val time = (line as? RawLine.Record)?.record?.timeMonoNs()
                if (time != null && time > mutablePlayback.value.positionNs(nowNs())) {
                    mutableReplay.value = model.snapshot()
                    mutableReplayReady.value = true
                    awaitPlayhead(time)
                }
                model.accept(line)
            }
            mutableReplay.value = model.snapshot()
        }
    }

    /**
     * Reads the [SCRUB_LEAD_NS] up to [targetNs], then shows it: the screen and the paused
     * playhead move there together, so what is drawn always matches the moment it is drawn at.
     */
    private suspend fun readMoment(file: File, targetNs: Long) {
        val sweeps = sweepsKept.first()
        val (model, start) = modelFrom(targetNs - SCRUB_LEAD_NS * sweeps, sweeps) ?: return
        reportingReadErrors {
            RawTail(file).linesWithOffsets(start)
                .takeWhile { (_, line) -> ((line as? RawLine.Record)?.record?.timeMonoNs() ?: Long.MIN_VALUE) <= targetNs }
                .collect { (_, line) -> model.accept(line) }
            // A drag that ended while this read ran has already set where the playhead goes.
            if (beforeScrub == null) return@reportingReadErrors
            mutableReplay.value = model.snapshot()
            mutableReplayReady.value = true
            val now = nowNs()
            mutablePlayback.value = Playback.Past(targetNs, now, mutablePlayback.value.speed, paused = true)
        }
    }

    /**
     * A model for reading from [fromNs]: it starts at the last mark the capture's reader noted at
     * or before then, with the state as it stood there, and the place in the file to read from.
     */
    private fun modelFrom(fromNs: Long, sweeps: Int): Pair<MonitorModel, RawTail.Start>? {
        val live = Capture.live.value
        val timeline = live.timeline ?: return null
        val header = live.snapshot.header ?: return null
        val mark = timeline.markAtOrBefore(fromNs)
        val model = MonitorModel(MonitorModel.DEFAULT_KEEP_NS * sweeps)
        model.setHeader(header)
        model.startFrom(if (mark == null) CaptureStateAt.INITIAL else timeline.stateAt(mark.monoNs - 1))
        return model to (if (mark == null) RawTail.Start.Beginning else RawTail.Start.At(mark.offset))
    }

    private suspend fun reportingReadErrors(read: suspend () -> Unit) {
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            mutableReadError.value = e.message ?: e.toString()
        } catch (e: UnreadableRawFileException) {
            mutableReadError.value = e.message ?: e.toString()
        }
    }

    /** Waits until the playhead reaches [timeNs]: sleeping for as long as that takes at the playing speed. */
    private suspend fun awaitPlayhead(timeNs: Long) {
        while (true) {
            val playback = mutablePlayback.value
            val position = playback.positionNs(nowNs())
            if (playback is Playback.Live || position >= timeNs) return
            val waitMs = if (playback.paused) MAX_WAIT_MS else ((timeNs - position) / playback.speed / 1e6).toLong()
            delay(waitMs.coerceIn(1, MAX_WAIT_MS))
        }
    }

    private suspend fun awaitHeader(file: File): HeaderRecord {
        while (true) {
            withContext(Dispatchers.IO) { RawTail.readHeader(file) }?.let { return it }
            delay(HEADER_POLL_MS)
        }
    }

    private fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()

    private companion object {
        /** About 20 seconds of data at the rates 0.1 records: enough to fill a sweep at once. */
        const val TAIL_START_BYTES = 256L * 1024
        const val HEADER_POLL_MS = 20L

        /** How far before the playhead the past reader starts: what the model keeps, so a sweep fills. */
        const val REPLAY_LEAD_NS = MonitorModel.DEFAULT_KEEP_NS

        /** While dragging: about one sweep across a phone at the slower speeds, so each step reads little. */
        const val SCRUB_LEAD_NS = 10_000_000_000L

        const val MAX_WAIT_MS = 50L
        const val CATCH_UP_CHECK_MS = 200L
    }
}

/**
 * The beats the beep should sound while the monitor shows the past, moved from the capture's
 * clock onto the real one at the playing speed: null at live, when the beep follows the live
 * reading, and none while paused or faster than [MAX_BEEP_SPEED]. Faster than that, beats come
 * close enough together that 80 ms beeps would run into one tone.
 */
fun replayBeatsOnRealClock(beats: List<Long>, playback: Playback): List<Long>? = when (playback) {
    Playback.Live -> null
    is Playback.Past ->
        if (playback.paused || playback.speed > MAX_BEEP_SPEED) {
            emptyList()
        } else {
            beats.map { playback.anchorNowNs + ((it - playback.anchorNs) / playback.speed.toDouble()).toLong() }
        }
}

/** The fastest playback the beep sounds at. */
const val MAX_BEEP_SPEED = 2f
