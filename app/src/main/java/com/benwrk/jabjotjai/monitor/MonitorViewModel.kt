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
import kotlinx.coroutines.flow.map
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

    private val mutablePlayback = MutableStateFlow<Playback>(Playback.Live)
    val playback: StateFlow<Playback> = mutablePlayback.asStateFlow()

    /** Changes whenever the past reader has to start again: a jump, or leaving live. */
    private val replayStart = MutableStateFlow(0)

    private val mutableReadError = MutableStateFlow<String?>(null)
    val readError: StateFlow<String?> = mutableReadError.asStateFlow()

    val displaySettings: StateFlow<DisplaySettings> = DisplaySettingsStore.settings

    fun updateDisplaySettings(change: (DisplaySettings) -> DisplaySettings) = DisplaySettingsStore.update(change)

    init {
        DisplaySettingsStore.init(application)
        val runningFile = Capture.state.map { (it as? CaptureState.Running)?.file }.distinctUntilChanged()
        viewModelScope.launch {
            runningFile.collectLatest { file ->
                mutableSnapshot.value = MonitorSnapshot.EMPTY
                mutableReadError.value = null
                mutablePlayback.value = Playback.Live
                if (file != null) follow(file)
            }
        }
        viewModelScope.launch {
            combine(runningFile, replayStart) { file, _ -> file }.collectLatest { file ->
                if (file != null && mutablePlayback.value is Playback.Past) replay(file)
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

    /** Jumps to [targetNs] of the capture, or to live if that is within a second of now. */
    fun seek(targetNs: Long) {
        val start = Capture.live.value.snapshot.header?.start?.monoNs ?: return
        mutablePlayback.value = mutablePlayback.value.seek(targetNs, start, nowNs())
        replayStart.value++
    }

    fun setSpeed(speed: Float) {
        val before = mutablePlayback.value
        mutablePlayback.value = before.withSpeed(speed, nowNs())
        if (before is Playback.Live && mutablePlayback.value is Playback.Past) replayStart.value++
    }

    fun togglePause() {
        val before = mutablePlayback.value
        mutablePlayback.value = before.togglePause(nowNs())
        if (before is Playback.Live) replayStart.value++
    }

    fun goLive() {
        mutablePlayback.value = Playback.Live
        replayStart.value++
    }

    private suspend fun follow(file: File) {
        val model = MonitorModel()
        try {
            model.setHeader(awaitHeader(file))
            RawTail(file).lines(RawTail.Start.NearEnd(TAIL_START_BYTES)).collect { line ->
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
        val live = Capture.live.value
        val timeline = live.timeline ?: return
        val header = live.snapshot.header ?: return
        val from = mutablePlayback.value.positionNs(nowNs()) - REPLAY_LEAD_NS
        val mark = timeline.markAtOrBefore(from)
        val model = MonitorModel()
        model.setHeader(header)
        model.startFrom(if (mark == null) CaptureStateAt.INITIAL else timeline.stateAt(mark.monoNs - 1))
        mutableReplay.value = model.snapshot()
        val start = if (mark == null) RawTail.Start.Beginning else RawTail.Start.At(mark.offset)
        try {
            RawTail(file).linesWithOffsets(start).collect { (_, line) ->
                val time = (line as? RawLine.Record)?.record?.timeMonoNs()
                if (time != null && time > mutablePlayback.value.positionNs(nowNs())) {
                    mutableReplay.value = model.snapshot()
                    awaitPlayhead(time)
                }
                model.accept(line)
            }
            mutableReplay.value = model.snapshot()
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
