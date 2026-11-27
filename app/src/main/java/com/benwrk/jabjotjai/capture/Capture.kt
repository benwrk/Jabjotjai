package com.benwrk.jabjotjai.capture

import android.content.Context
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

/** Starts and stops [CaptureService], and publishes its state for the screens. */
object Capture {

    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = mutableState.asStateFlow()

    internal fun publish(state: CaptureState) {
        mutableState.value = state
    }

    /** @param sensorAddress the Bluetooth address the strap was seen advertising from in the scan */
    fun start(context: Context, sensorId: String, sensorName: String, sensorAddress: String) {
        context.startForegroundService(CaptureService.startIntent(context, sensorId, sensorName, sensorAddress))
    }

    fun stop(context: Context) {
        context.startService(CaptureService.stopIntent(context))
    }
}
