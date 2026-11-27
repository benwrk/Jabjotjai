package com.benwrk.jabjotjai.capture

import com.benwrk.jabjotjai.raw.AccRecord
import com.benwrk.jabjotjai.raw.EcgRecord
import com.benwrk.jabjotjai.raw.EventRecord
import com.benwrk.jabjotjai.raw.HrRecord
import com.benwrk.jabjotjai.raw.RawEvents
import com.benwrk.jabjotjai.raw.RawStreams
import com.benwrk.jabjotjai.raw.Stamp
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.PolarBleApi.PolarDeviceDataType
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleDisconnectInfo
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarSensorSetting
import com.polar.sdk.api.model.PolarSensorSetting.SettingType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * Connects to one H10 and turns everything the SDK reports into records in [session]: connection
 * events, device information and battery as events, and the ECG, accelerometer and heart-rate
 * streams as frames, each stamped with the phone's clocks when it arrives.
 *
 * Everything runs on [scope], which must be single-threaded: SDK callbacks are stamped on arrival
 * and then handed over to it, so the session sees one ordered sequence of calls.
 *
 * Every SDK call names the strap by the Bluetooth [address] the scan saw it advertising from, not
 * by [deviceId]. The SDK resolves a device id to the first session with that id
 * (`PolarServiceClientUtils.sessionByDeviceId`, SDK 9.0.0), and a phone that has bonded the same
 * strap at two addresses holds two such sessions; resolving to the stale one leaves the connection
 * pending for good (observed 2026-11-27 on a phone with two bonds for one H10).
 */
class PolarH10Link(
    private val api: PolarBleApi,
    private val deviceId: String,
    private val address: String,
    private val session: CaptureSession,
    private val clock: PhoneClock,
    private val scope: CoroutineScope,
) {

    /** A stream's collection, and the connection it was started on. */
    private class RunningStream(val connection: Int, val job: Job)

    private val streams = HashMap<String, RunningStream>()

    /** Counts the strap's connections, so a stream started on an earlier one is known to be stale. */
    private var connection = 0
    private var stopped = false

    fun connect() {
        api.setApiCallback(callback)
        api.connectToDevice(address)
    }

    /** Stops every stream, disconnects, and ends the session. Call it on [scope]. */
    suspend fun stop(reason: String) {
        if (stopped) return
        stopped = true
        streams.values.forEach { it.job.cancelAndJoin() }
        streams.clear()
        api.setApiCallback(SilentCallback)
        try {
            api.disconnectFromDevice(address)
        } catch (_: Exception) {
            // The session ends below either way; the disconnect failing changes nothing it records.
        }
        session.end(clock.stamp(), reason)
    }

    /** Stamps now, on the caller's thread, then runs [block] on the capture thread. */
    private fun onCaptureThread(block: (Stamp) -> Unit) {
        val stamp = clock.stamp()
        scope.launch { if (!stopped) block(stamp) }
    }

    private fun event(stamp: Stamp, event: String) = EventRecord(wallMs = stamp.wallMs, monoNs = stamp.monoNs, event = event)

    /** Callbacks that carry device info: the address tells this strap's session from a stale one. */
    private fun isThisStrap(info: PolarDeviceInfo) = info.address.equals(address, ignoreCase = true)

    /**
     * Callbacks that carry only an identifier get the session's device id, or its address when it
     * has none (`BDBleApiImpl`, SDK 9.0.0, lines 3152 and 3342). Only the connected session raises them.
     */
    private fun isThisStrap(identifier: String) = identifier == deviceId || identifier.equals(address, ignoreCase = true)

    private val callback = object : PolarBleApiCallback() {

        override fun blePowerStateChanged(powered: Boolean) = onCaptureThread {
            session.event(event(it, RawEvents.BLE_POWER).copy(value = if (powered) "on" else "off"))
        }

        override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
            if (isThisStrap(polarDeviceInfo)) onCaptureThread { session.event(event(it, RawEvents.CONNECTING)) }
        }

        override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
            if (!isThisStrap(polarDeviceInfo)) return
            onCaptureThread {
                connection++
                session.event(event(it, RawEvents.CONNECTED))
            }
        }

        // The SDK calls both disconnect overloads, one after the other (BDBleApiImpl, SDK 9.0.0,
        // lines 1666 and 1668), so only the typed one is recorded.
        override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo, info: PolarBleDisconnectInfo) {
            if (!isThisStrap(polarDeviceInfo)) return
            onCaptureThread {
                // Cancelled now, while nothing is connected, so cancelling cannot switch off what
                // the next connection's streams switch on (see [startStream]).
                streams.values.forEach { running -> running.job.cancel() }
                streams.clear()
                session.disconnected(
                    event(it, RawEvents.DISCONNECTED).copy(
                        reason = info.reason.name,
                        recovery = info.recoveryAction.name,
                        gattStatus = info.gattStatus,
                        deviceCommand = info.deviceCommand?.name,
                    ),
                )
            }
        }

        override fun bleSdkFeatureReady(identifier: String, feature: PolarBleSdkFeature) {
            if (!isThisStrap(identifier)) return
            onCaptureThread {
                session.event(event(it, RawEvents.FEATURE_READY).copy(feature = feature.name))
                when (feature) {
                    PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING -> {
                        startStream(RawStreams.ECG)
                        startStream(RawStreams.ACC)
                    }
                    PolarBleSdkFeature.FEATURE_HR -> startStream(RawStreams.HR)
                    else -> Unit
                }
            }
        }

        // The SDK calls both device-information overloads for each item (BDBleApiImpl, SDK 9.0.0,
        // lines 3293 and 3301), so only the key-value one is recorded.
        override fun disInformationReceived(identifier: String, disInfo: DisInfo) {
            if (!isThisStrap(identifier)) return
            onCaptureThread { session.event(event(it, RawEvents.DEVICE_INFO).copy(key = disInfo.key, value = disInfo.value)) }
        }

        override fun batteryLevelReceived(identifier: String, level: Int) {
            if (!isThisStrap(identifier)) return
            onCaptureThread { session.event(event(it, RawEvents.BATTERY).copy(levelPct = level)) }
        }

        // Health-thermometer service: abstract in the SDK's base class, and not a feature this app enables.
        override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) = Unit
    }

    /**
     * Starts one stream, at most once per connection.
     *
     * The SDK reports a feature ready more than once on a single connection: `FEATURE_HR` three
     * times and `FEATURE_POLAR_ONLINE_STREAMING` twice, in each of three captures on the test strap
     * (SDK 9.0.0, 2026-11-27). Restarting the stream on each report lost heart rate for a whole
     * capture: logcat showed the Heart Rate Measurement characteristic's notifications switched on
     * four times and then off, last, and no heart rate arrived afterwards. In a capture where they
     * ended switched on, heart rate arrived. Why cancelling a collection switches them off after
     * the new one switched them on is not confirmed. So a stream already running on this connection
     * is left alone; one left from an earlier connection is replaced.
     *
     * A stream that fails, or ends, while the strap stays connected is recorded and started again
     * on the same connection (see [keepStreaming]), each try writing its `settings` and
     * `stream_started` again. The collection that failed has ended by then rather than being
     * cancelled, which is the case above; that the strap streams again after such a restart has
     * not yet been seen.
     */
    private fun startStream(stream: String) {
        val running = streams[stream]
        if (running != null && running.connection == connection && running.job.isActive) return
        running?.job?.cancel()
        val startedOn = connection
        streams[stream] = RunningStream(startedOn, scope.launch {
            keepStreaming(
                attempt = {
                    when (stream) {
                        RawStreams.ECG -> streamEcg()
                        RawStreams.ACC -> streamAcc()
                        RawStreams.HR -> streamHr()
                    }
                },
                onError = { message -> session.streamError(streamError(stream, message)) },
                wanted = { !stopped && connection == startedOn },
                nowMs = { clock.monoNs() / 1_000_000 },
            )
        })
    }

    private fun streamError(stream: String, message: String) =
        event(clock.stamp(), RawEvents.STREAM_ERROR).copy(stream = stream, message = message)

    private suspend fun streamEcg() {
        val available = api.requestStreamSettings(address, PolarDeviceDataType.ECG)
        val selected = StreamSettings.choose(available.settings, preferred = emptyMap())
        recordSettings(RawStreams.ECG, available, selected)
        api.startEcgStreaming(address, PolarSensorSetting(selected)).collect { data ->
            val stamp = clock.stamp()
            val samples = data.samples.map {
                it as? EcgSample ?: throw IllegalStateException("unexpected ECG sample type ${it::class.simpleName}")
            }
            stored { session.frame(EcgRecord(stamp.wallMs, stamp.monoNs, samples.map { it.timeStamp }, samples.map { it.voltage })) }
        }
    }

    private suspend fun streamAcc() {
        val available = api.requestStreamSettings(address, PolarDeviceDataType.ACC)
        val selected = StreamSettings.choose(available.settings, preferred = ACC_PREFERRED)
        recordSettings(RawStreams.ACC, available, selected)
        api.startAccStreaming(address, PolarSensorSetting(selected)).collect { data ->
            val stamp = clock.stamp()
            val samples = data.samples
            stored {
                session.frame(
                    AccRecord(
                        wallMs = stamp.wallMs,
                        monoNs = stamp.monoNs,
                        tNs = samples.map { it.timeStamp },
                        xMg = samples.map { it.x },
                        yMg = samples.map { it.y },
                        zMg = samples.map { it.z },
                    ),
                )
            }
        }
    }

    private suspend fun streamHr() {
        stored { session.event(event(clock.stamp(), RawEvents.STREAM_STARTED).copy(stream = RawStreams.HR)) }
        api.startHrStreaming(address).collect { data ->
            val stamp = clock.stamp()
            for (sample in data.samples) {
                stored {
                    session.frame(
                        HrRecord(
                            wallMs = stamp.wallMs,
                            monoNs = stamp.monoNs,
                            bpm = sample.hr,
                            rr1024 = sample.rrs,
                            rrMs = sample.rrsMs,
                            rrAvailable = sample.rrAvailable,
                            contact = sample.contactStatus,
                            contactSupported = sample.contactStatusSupported,
                            correctedBpm = sample.correctedHr,
                            ppgQuality = sample.ppgQuality,
                        ),
                    )
                }
            }
        }
    }

    private fun recordSettings(stream: String, available: PolarSensorSetting, selected: Map<SettingType, Int>) {
        val stamp = clock.stamp()
        stored {
            session.event(
                event(stamp, RawEvents.SETTINGS).copy(
                    stream = stream,
                    available = available.settings.entries.associate { (type, values) -> nameOf(type, stream) to values.sorted() },
                    selected = selected.entries.associate { (type, value) -> nameOf(type, stream) to value },
                ),
            )
            session.event(event(stamp, RawEvents.STREAM_STARTED).copy(stream = stream))
        }
    }

    /** Setting names as `docs/raw-format.md` defines them. */
    private fun nameOf(type: SettingType, stream: String): String = when (type) {
        SettingType.SAMPLE_RATE -> "rate_hz"
        SettingType.RESOLUTION -> "bits"
        SettingType.CHANNELS -> "channels"
        SettingType.RANGE -> if (stream == RawStreams.ACC) "range_g" else "range"
    }

    private object SilentCallback : PolarBleApiCallback() {
        override fun disInformationReceived(identifier: String, disInfo: DisInfo) = Unit
        override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) = Unit
    }

    companion object {
        /** 200 Hz and 8 G: the highest rate the H10 offers, and a range a run does not clip (`docs/spec.md`). */
        private val ACC_PREFERRED = mapOf(SettingType.SAMPLE_RATE to 200, SettingType.RANGE to 8)
    }
}
