package com.benwrk.jabjotjai.ui.picker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.benwrk.jabjotjai.capture.PolarApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/** A strap seen in a scan, with the Bluetooth address it was advertising from. */
data class FoundSensor(val id: String, val name: String, val address: String, val rssiDbm: Int)

/** Scans for H10 straps for a while and lists them, strongest signal first. */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableFound = MutableStateFlow<List<FoundSensor>>(emptyList())
    val found: StateFlow<List<FoundSensor>> = mutableFound.asStateFlow()

    private val mutableScanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = mutableScanning.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    private var job: Job? = null

    fun scan() {
        job?.cancel()
        mutableFound.value = emptyList()
        mutableError.value = null
        job = viewModelScope.launch {
            mutableScanning.value = true
            try {
                withTimeoutOrNull(SCAN_MS.milliseconds) {
                    PolarApi.get(getApplication()).searchForDevice().collect { info ->
                        if (!info.name.contains(H10_NAME)) return@collect
                        val sensor = FoundSensor(id = info.deviceId, name = info.name, address = info.address, rssiDbm = info.rssi)
                        mutableFound.update { list -> (list.filterNot { it.id == sensor.id } + sensor).sortedByDescending { it.rssiDbm } }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableError.value = e.message ?: e.toString()
            } finally {
                mutableScanning.value = false
            }
        }
    }

    fun stopScan() {
        job?.cancel()
        job = null
    }

    override fun onCleared() = stopScan()

    private companion object {
        const val SCAN_MS = 30_000L

        /** H10s advertise as "Polar H10 <device id>". */
        const val H10_NAME = "H10"
    }
}
