package com.benwrk.jabjotjai.monitor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps [DisplaySettings] between runs. They describe only how the screen draws and sounds, never
 * what is recorded, so they live in shared preferences rather than in the raw file.
 *
 * One copy is shared by the whole process, because the capture service needs them as well as the
 * screen: it is the one that beeps. Call [init] before reading [settings].
 */
object DisplaySettingsStore {

    private const val FILE = "display"
    private const val SWEEP = "sweep"
    private const val ECG_AUTO = "ecg_auto"
    private const val ECG_GAIN = "ecg_gain_index"
    private const val ACC_AUTO = "acc_auto"
    private const val ACC_SCALE = "acc_scale_index"
    private const val CALIBRATION_PULSE = "calibration_pulse"
    private const val BEEP = "beep"
    private const val BEEP_IN_BACKGROUND = "beep_in_background"
    private const val BEEP_VOLUME = "beep_volume"
    private const val TIME_FROM_START = "time_from_start"
    private const val CHART_ROWS = "chart_rows"

    private val mutableSettings = MutableStateFlow(DisplaySettings())
    val settings: StateFlow<DisplaySettings> = mutableSettings.asStateFlow()

    private var context: Context? = null

    /** Loads the saved settings, once per process; later calls do nothing. */
    @Synchronized
    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        mutableSettings.value = load(context.applicationContext)
    }

    @Synchronized
    fun update(change: (DisplaySettings) -> DisplaySettings) {
        val updated = change(mutableSettings.value)
        mutableSettings.value = updated
        context?.let { save(it, updated) }
    }

    /** The saved settings; anything missing or no longer valid falls back to its default. */
    private fun load(context: Context): DisplaySettings {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val defaults = DisplaySettings()
        val sweep = SweepSpeed.entries.firstOrNull { it.name == prefs.getString(SWEEP, null) } ?: defaults.sweep
        return DisplaySettings(
            sweep = sweep,
            ecgAuto = prefs.getBoolean(ECG_AUTO, defaults.ecgAuto),
            ecgGainIndex = prefs.getInt(ECG_GAIN, defaults.ecgGainIndex).takeIf { it in 0 until ScaleLadder.ECG.size } ?: defaults.ecgGainIndex,
            accAuto = prefs.getBoolean(ACC_AUTO, defaults.accAuto),
            accScaleIndex = prefs.getInt(ACC_SCALE, defaults.accScaleIndex).takeIf { it in 0 until ScaleLadder.ACC.size } ?: defaults.accScaleIndex,
            calibrationPulse = prefs.getBoolean(CALIBRATION_PULSE, defaults.calibrationPulse),
            beep = prefs.getBoolean(BEEP, defaults.beep),
            beepInBackground = prefs.getBoolean(BEEP_IN_BACKGROUND, defaults.beepInBackground),
            beepVolume = prefs.getFloat(BEEP_VOLUME, defaults.beepVolume).coerceIn(0f, 1f),
            timeFromStart = prefs.getBoolean(TIME_FROM_START, defaults.timeFromStart),
            // Rows kept by 0.4 had the one sweep speed of their day.
            rows = prefs.getString(CHART_ROWS, null)?.let { decodeChartRows(it, legacySpeed = sweep) } ?: defaults.rows,
        )
    }

    private fun save(context: Context, settings: DisplaySettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(SWEEP, settings.sweep.name)
            .putBoolean(ECG_AUTO, settings.ecgAuto)
            .putInt(ECG_GAIN, settings.ecgGainIndex)
            .putBoolean(ACC_AUTO, settings.accAuto)
            .putInt(ACC_SCALE, settings.accScaleIndex)
            .putBoolean(CALIBRATION_PULSE, settings.calibrationPulse)
            .putBoolean(BEEP, settings.beep)
            .putBoolean(BEEP_IN_BACKGROUND, settings.beepInBackground)
            .putFloat(BEEP_VOLUME, settings.beepVolume)
            .putBoolean(TIME_FROM_START, settings.timeFromStart)
            .putString(CHART_ROWS, encodeChartRows(settings.rows))
            .apply()
    }
}
