package com.benwrk.jabjotjai.monitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.benwrk.jabjotjai.capture.Capture
import com.benwrk.jabjotjai.capture.CaptureState
import com.benwrk.jabjotjai.capture.LiveReading
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.RawTail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
 * The waveforms come from its own tail, which starts near the end of the file so a sweep fills at
 * once. Everything that depends on the whole file (connection, heart rate, battery, gaps, beats)
 * comes from [Capture.live], read from the first line by the capture service: a tail that starts
 * near the end has not seen the connection event, and would count only the gaps after it.
 */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableSnapshot = MutableStateFlow(MonitorSnapshot.EMPTY)

    /** The waveforms and their timing; read the rest from [live]. */
    val snapshot: StateFlow<MonitorSnapshot> = mutableSnapshot.asStateFlow()

    val live: StateFlow<LiveReading> = Capture.live

    private val mutableReadError = MutableStateFlow<String?>(null)
    val readError: StateFlow<String?> = mutableReadError.asStateFlow()

    val displaySettings: StateFlow<DisplaySettings> = DisplaySettingsStore.settings

    fun updateDisplaySettings(change: (DisplaySettings) -> DisplaySettings) = DisplaySettingsStore.update(change)

    init {
        DisplaySettingsStore.init(application)
        viewModelScope.launch {
            Capture.state
                .map { (it as? CaptureState.Running)?.file }
                .distinctUntilChanged()
                .collectLatest { file ->
                    mutableSnapshot.value = MonitorSnapshot.EMPTY
                    mutableReadError.value = null
                    if (file != null) follow(file)
                }
        }
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

    private suspend fun awaitHeader(file: File): HeaderRecord {
        while (true) {
            withContext(Dispatchers.IO) { RawTail.readHeader(file) }?.let { return it }
            delay(HEADER_POLL_MS)
        }
    }

    private companion object {
        /** About 20 seconds of data at the rates 0.1 records: enough to fill a sweep at once. */
        const val TAIL_START_BYTES = 256L * 1024
        const val HEADER_POLL_MS = 20L
    }
}
