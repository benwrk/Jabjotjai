package com.benwrk.jabjotjai.monitor

import android.content.Context

/**
 * Keeps [DisplaySettings] between runs. They describe only how the screen draws, never what is
 * recorded, so they live in shared preferences rather than in the raw file.
 */
class DisplaySettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The saved settings; anything missing or no longer valid falls back to its default. */
    fun load(): DisplaySettings {
        val defaults = DisplaySettings()
        return DisplaySettings(
            sweep = SweepSpeed.entries.firstOrNull { it.name == prefs.getString(SWEEP, null) } ?: defaults.sweep,
            ecgAuto = prefs.getBoolean(ECG_AUTO, defaults.ecgAuto),
            ecgGainIndex = prefs.getInt(ECG_GAIN, defaults.ecgGainIndex).takeIf { it in 0 until ScaleLadder.ECG.size } ?: defaults.ecgGainIndex,
            accAuto = prefs.getBoolean(ACC_AUTO, defaults.accAuto),
            accScaleIndex = prefs.getInt(ACC_SCALE, defaults.accScaleIndex).takeIf { it in 0 until ScaleLadder.ACC.size } ?: defaults.accScaleIndex,
        )
    }

    fun save(settings: DisplaySettings) {
        prefs.edit()
            .putString(SWEEP, settings.sweep.name)
            .putBoolean(ECG_AUTO, settings.ecgAuto)
            .putInt(ECG_GAIN, settings.ecgGainIndex)
            .putBoolean(ACC_AUTO, settings.accAuto)
            .putInt(ACC_SCALE, settings.accScaleIndex)
            .apply()
    }

    private companion object {
        const val FILE = "display"
        const val SWEEP = "sweep"
        const val ECG_AUTO = "ecg_auto"
        const val ECG_GAIN = "ecg_gain_index"
        const val ACC_AUTO = "acc_auto"
        const val ACC_SCALE = "acc_scale_index"
    }
}
