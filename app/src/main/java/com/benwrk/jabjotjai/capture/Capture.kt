package com.benwrk.jabjotjai.capture

import android.content.Context
import com.benwrk.jabjotjai.monitor.MonitorSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Whether a capture is running, as the rest of the app sees it. */
sealed interface CaptureState {
    data object Idle : CaptureState

    /** Capturing into [file]. What the strap is doing is read from that file, not from here. */
    data class Running(val file: File, val sensorId: String, val sensorName: String) : CaptureState

    /** The last capture stopped because of [message]. */
    data class Failed(val message: String) : CaptureState
}

/**
 * What the capture service's reader has read so far.
 *
 * @param readError why the reader stopped, if the raw file could not be read
 */
data class LiveReading(val snapshot: MonitorSnapshot, val readError: String?) {
    companion object {
        val EMPTY = LiveReading(MonitorSnapshot.EMPTY, readError = null)
    }
}

/** Starts and stops [CaptureService], and publishes its state for the screens. */
object Capture {

    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = mutableState.asStateFlow()

    private val mutableLive = MutableStateFlow(LiveReading.EMPTY)

    /**
     * The running capture's raw file as read from its first line, by one reader in the capture
     * service. Connection, heart rate, battery, gaps and beats come from here, so every part of the
     * app sees the same values whenever it starts looking; waveforms are read by the monitor itself.
     */
    val live: StateFlow<LiveReading> = mutableLive.asStateFlow()

    private val mutableMonitorVisible = MutableStateFlow(false)

    /** Whether the monitor is on screen, which the beat beep follows unless it is set to keep going. */
    val monitorVisible: StateFlow<Boolean> = mutableMonitorVisible.asStateFlow()

    private val mutableBeepError = MutableStateFlow<String?>(null)

    /** Why the beat beep cannot sound, or null if nothing is stopping it. */
    val beepError: StateFlow<String?> = mutableBeepError.asStateFlow()

    internal fun publish(state: CaptureState) {
        mutableState.value = state
    }

    internal fun publishLive(reading: LiveReading) {
        mutableLive.value = reading
    }

    internal fun publishBeepError(message: String?) {
        mutableBeepError.value = message
    }

    fun setMonitorVisible(visible: Boolean) {
        mutableMonitorVisible.value = visible
    }

    /** @param sensorAddress the Bluetooth address the strap was seen advertising from in the scan */
    fun start(context: Context, sensorId: String, sensorName: String, sensorAddress: String) {
        context.startForegroundService(CaptureService.startIntent(context, sensorId, sensorName, sensorAddress))
    }

    fun stop(context: Context) {
        context.startService(CaptureService.stopIntent(context))
    }
}
