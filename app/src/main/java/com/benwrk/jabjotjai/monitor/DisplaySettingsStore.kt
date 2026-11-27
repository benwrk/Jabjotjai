package com.benwrk.jabjotjai.monitor

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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
    private const val CHART_UNDER_CUTOUT = "chart_under_cutout"
    private const val TREND_ROBUST_SCALE = "trend_robust_scale"
    private const val TREND_HIDE_NO_CONTACT = "trend_hide_no_contact"

    /** The longest RR an RR row scales to, in ms, with 0 for none. */
    private const val RR_CEILING_MS = "rr_ceiling_ms"

    private val mutableSettings = MutableStateFlow(DisplaySettings())
    val settings: StateFlow<DisplaySettings> = mutableSettings.asStateFlow()

    /**
     * Where the settings are kept, once [init] has run. The preferences themselves are held rather
     * than a context, which is all they were needed for.
     */
    private var prefs: SharedPreferences? = null

    /** Loads the saved settings, once per process; later calls do nothing. */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val opened = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = opened
        mutableSettings.value = load(opened)
    }

    @Synchronized
    fun update(change: (DisplaySettings) -> DisplaySettings) {
        val updated = change(mutableSettings.value)
        mutableSettings.value = updated
        prefs?.let { save(it, updated) }
    }

    /** The saved settings; anything missing or no longer valid falls back to its default. */
    private fun load(prefs: SharedPreferences): DisplaySettings {
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
            chartUnderCutout = prefs.getBoolean(CHART_UNDER_CUTOUT, defaults.chartUnderCutout),
            trendRobustScale = prefs.getBoolean(TREND_ROBUST_SCALE, defaults.trendRobustScale),
            trendHideNoContact = prefs.getBoolean(TREND_HIDE_NO_CONTACT, defaults.trendHideNoContact),
            rrCeilingMs = when (val kept = prefs.getInt(RR_CEILING_MS, defaults.rrCeilingMs ?: 0)) {
                0 -> null
                in RR_CEILINGS_MS -> kept
                else -> defaults.rrCeilingMs
            },
        )
    }

    private fun save(prefs: SharedPreferences, settings: DisplaySettings) {
        prefs.edit {
            putString(SWEEP, settings.sweep.name)
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
                .putBoolean(CHART_UNDER_CUTOUT, settings.chartUnderCutout)
                .putBoolean(TREND_ROBUST_SCALE, settings.trendRobustScale)
                .putBoolean(TREND_HIDE_NO_CONTACT, settings.trendHideNoContact)
                .putInt(RR_CEILING_MS, settings.rrCeilingMs ?: 0)
        }
    }
}
